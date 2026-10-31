package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/util/jsontime"

	"go.mau.fi/mautrix-linkedin/pkg/linkedingo"
)

// LinkedIn is the user's LinkedIn messages, logged in with linkedin.com's session cookies (li_at and JSESSIONID)
// and kept up to date over LinkedIn's real-time stream (mautrix-linkedin's linkedingo client). Thread keys are
// conversation URNs, message IDs message URNs, contact IDs profile IDs.
type LinkedIn struct {
	dataDir  string
	listener Listener
	log      zerolog.Logger
	jar      *linkedingo.StringCookieJar
	// The login browser's User-Agent and client hints: LinkedIn ends a session that comes back from another browser
	headers http.Header
	// The login's li_at, which saved cookies must have grown from to be used
	loginLiAt string
	// Profile ID → the one-to-one conversation with them; new-chat key → the conversation its first message created
	directChats sync.Map
	started     sync.Map

	lock   sync.Mutex
	client *linkedingo.Client
	cancel context.CancelFunc
	selfID string

	// Profile ID → true once its contact event was sent
	contacts sync.Map
	// Message URN → its conversation and sending time, for replies and older pages
	messages sync.Map
	// Conversation URN → time of its oldest loaded message
	oldest sync.Map
	// "message" → our reaction on it
	ownReactions sync.Map
	typingTimers sync.Map
}

type linkedInMessageInfo struct {
	conversation linkedingo.URN
	sentAt       time.Time
	senderID     string
	text         string
}

const (
	linkedInPageSize   = 20
	linkedInConvoPages = 3
	// Login values named so are the login browser's headers, not cookies
	linkedInHeaderPrefix = "hyd.header."
	// How often the cookies LinkedIn refreshes are saved
	linkedInCookieSaveInterval = 5 * time.Minute
	// The name the app gives its voice recordings
	linkedInVoiceFileName = "Voice message"
	// Thread keys of chats not created yet: the first message creates them
	linkedInNewChatPrefix = "new:"
	linkedInTypingTimeout = 6 * time.Second
	linkedInMaxDownload   = 25 << 20
)

// NewLinkedIn takes the login as a JSON object of linkedin.com cookies: "li_at" and "JSESSIONID".
func NewLinkedIn(cookiesJSON string, dataDir string, listener Listener) (*LinkedIn, error) {
	var cookies map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &cookies); err != nil {
		return nil, fmt.Errorf("invalid login: %w", err)
	}
	if cookies["li_at"] == "" || cookies["JSESSIONID"] == "" {
		return nil, errors.New("missing the LinkedIn cookies")
	}
	var header []string
	headers := make(http.Header)
	for name, value := range cookies {
		if headerName, ok := strings.CutPrefix(name, linkedInHeaderPrefix); ok {
			headers.Set(headerName, value)
		} else if name != "g_state" { // Google's sign-in state, which linkedingo can't parse (as mautrix-linkedin)
			header = append(header, name+"="+value)
		}
	}
	jar, err := linkedingo.NewJarFromCookieHeader(strings.Join(header, "; "))
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "linkedin").Logger().Level(zerolog.InfoLevel)
	l := &LinkedIn{dataDir: dataDir, listener: listener, log: log, jar: jar, headers: headers, loginLiAt: cookies["li_at"]}
	if saved := l.loadSavedCookies(); saved != nil {
		l.jar = saved
	}
	return l, nil
}

// savedLinkedInCookies is the cookie jar as LinkedIn last left it (it refreshes cookies as it goes), kept for the
// login it grew from: a new login starts from its own cookies.
type savedLinkedInCookies struct {
	LoginLiAt string                      `json:"loginLiAt"`
	Jar       *linkedingo.StringCookieJar `json:"jar"`
}

func (l *LinkedIn) cookiesPath() string { return filepath.Join(l.dataDir, "cookies.json") }

func (l *LinkedIn) loadSavedCookies() *linkedingo.StringCookieJar {
	data, err := readSecretFile(l.cookiesPath())
	if err != nil {
		return nil
	}
	var saved savedLinkedInCookies
	if json.Unmarshal(data, &saved) != nil || saved.LoginLiAt != l.loginLiAt || saved.Jar == nil ||
		saved.Jar.GetCookie("li_at") == "" {
		return nil
	}
	return saved.Jar
}

// saveCookies keeps the cookies LinkedIn refreshed, so the next start doesn't send ones it has since replaced.
func (l *LinkedIn) saveCookies() {
	data, err := json.Marshal(savedLinkedInCookies{LoginLiAt: l.loginLiAt, Jar: l.jar})
	if err != nil {
		return
	}
	if err := writeSecretFile(l.cookiesPath(), data); err != nil {
		l.log.Error().Err(err).Msg("Failed to save the LinkedIn cookies")
	}
}

func (l *LinkedIn) saveCookiesPeriodically(ctx context.Context) {
	ticker := time.NewTicker(linkedInCookieSaveInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			l.saveCookies()
		}
	}
}

// Connect checks the login, loads the conversations and opens the real-time stream.
func (l *LinkedIn) Connect() error {
	l.lock.Lock()
	if l.cancel != nil {
		l.lock.Unlock()
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(l.log.WithContext(context.Background()))
	l.cancel = cancel
	l.lock.Unlock()

	probe := linkedingo.NewClient(ctx, linkedingo.NewURN(""), l.jar, "", "", "", linkedingo.Handlers{})
	probe.SetBrowserHeaders(l.headers)
	profile, err := probe.GetCurrentUserProfile(ctx)
	if err != nil || profile.MiniProfile.EntityURN.ID() == "" {
		l.resetCancel()
		if err == nil || isLinkedInAuthError(err) {
			return errors.New("LinkedIn session expired, log in again")
		}
		return fmt.Errorf("failed to load LinkedIn: %w", err)
	}
	l.selfID = profile.MiniProfile.EntityURN.ID()
	name := strings.TrimSpace(profile.MiniProfile.FirstName + " " + profile.MiniProfile.LastName)
	l.emit(map[string]any{"type": "self", "id": l.selfID, "name": name})

	client := linkedingo.NewClient(ctx, linkedingo.NewURN(l.selfID), l.jar, "", "", "", linkedingo.Handlers{
		ClientConnection: func(ctx context.Context, _ *linkedingo.ClientConnection) {
			l.emitState("connected", "")
		},
		TransientDisconnect: func(ctx context.Context, err error) { l.emitState("reconnecting", errString(err)) },
		BadCredentials: func(ctx context.Context, err error) {
			l.emitState("error", "LinkedIn session expired, log in again")
		},
		UnknownError:   func(ctx context.Context, err error) { l.emitState("reconnecting", errString(err)) },
		DecoratedEvent: l.handleEvent,
	})
	client.SetBrowserHeaders(l.headers)
	l.lock.Lock()
	l.client = client
	l.lock.Unlock()
	if err := client.RealtimeConnect(ctx); err != nil {
		l.resetCancel()
		return fmt.Errorf("failed to connect to LinkedIn: %w", err)
	}
	l.saveCookies()
	go l.saveCookiesPeriodically(ctx)
	go l.syncConversations(ctx)
	return nil
}

// isLinkedInAuthError: LinkedIn turned the session down (it deletes li_at when it's no longer valid).
func isLinkedInAuthError(err error) bool {
	msg := err.Error()
	return strings.Contains(msg, "no longer valid") || strings.Contains(msg, "cookie was deleted") ||
		strings.Contains(msg, "401") || strings.Contains(msg, "403")
}

// Disconnect closes the real-time stream; Connect can be called again.
func (l *LinkedIn) Disconnect() {
	l.lock.Lock()
	client := l.client
	l.client = nil
	l.lock.Unlock()
	if client != nil {
		client.RealtimeDisconnect()
		l.saveCookies()
	}
	l.resetCancel()
	l.emitState("disconnected", "")
}

func (l *LinkedIn) resetCancel() {
	l.lock.Lock()
	defer l.lock.Unlock()
	if l.cancel != nil {
		l.cancel()
		l.cancel = nil
	}
}

func (l *LinkedIn) current() (*linkedingo.Client, error) {
	l.lock.Lock()
	defer l.lock.Unlock()
	if l.client == nil {
		return nil, errors.New("not connected")
	}
	return l.client, nil
}

// --- conversations ---

func (l *LinkedIn) syncConversations(ctx context.Context) {
	client, err := l.current()
	if err != nil {
		return
	}
	// The latest conversations come from the sync-token query: the "updated before" one can answer with an empty
	// first page for an inbox that has conversations, so it only pages back from there
	latest, err := client.GetConversationsBySyncToken(ctx)
	if err != nil {
		l.log.Error().Err(err).Msg("Failed to load LinkedIn conversations")
		return
	}
	if latest == nil || len(latest.Elements) == 0 {
		return
	}
	before := l.emitConversations(ctx, latest.Elements, time.Now())
	for page := 1; page < linkedInConvoPages && ctx.Err() == nil; page++ {
		older, err := client.GetConversationsUpdatedBefore(ctx, before)
		if err != nil {
			l.log.Error().Err(err).Msg("Failed to load older LinkedIn conversations")
			return
		}
		if older == nil || len(older.Elements) == 0 {
			return
		}
		next := l.emitConversations(ctx, older.Elements, before)
		if !next.Before(before) {
			return
		}
		before = next
	}
}

// emitConversations sends conversations and their latest messages and returns the oldest activity among them (or
// before, if none is older).
func (l *LinkedIn) emitConversations(ctx context.Context, convs []linkedingo.Conversation, before time.Time) time.Time {
	for i := range convs {
		conv := &convs[i]
		l.emitConversation(conv)
		for j := range conv.Messages.Elements {
			l.emitMessage(ctx, conv.EntityURN, &conv.Messages.Elements[j])
		}
		if t := conv.LastActivityAt.Time; !t.IsZero() && t.Before(before) {
			before = t
		}
	}
	return before
}

func (l *LinkedIn) emitConversation(conv *linkedingo.Conversation) {
	key := conv.EntityURN.String()
	var names []string
	var others []linkedingo.MessagingParticipant
	for _, p := range conv.ConversationParticipants {
		id := p.EntityURN.ID()
		l.emitParticipant(p)
		if id == l.selfID {
			continue
		}
		others = append(others, p)
		names = append(names, linkedInName(p))
		if len(conv.ConversationParticipants) == 2 && conv.Title == "" {
			l.directChats.Store(id, key)
		}
		l.emit(map[string]any{"type": "participant", "threadKey": key, "contactId": id})
	}
	name := conv.Title
	if name == "" {
		name = strings.Join(names, ", ")
	}
	isGroup := conv.GroupChat || len(others) > 1
	if !isGroup && len(others) == 1 {
		if avatar := linkedInAvatar(others[0]); avatar != "" {
			l.emit(map[string]any{"type": "avatar", "threadKey": key, "url": avatar})
		}
	}
	lastActivity := conv.LastActivityAt.UnixMilli()
	lastRead := conv.LastReadAt.UnixMilli()
	if conv.Read {
		lastRead = lastActivity
	}
	l.emit(map[string]any{
		"type": "thread", "key": key, "name": name, "isGroup": isGroup, "isEncrypted": false,
		"lastActivityMs": lastActivity, "lastReadMs": lastRead,
	})
}

func linkedInName(p linkedingo.MessagingParticipant) string {
	switch {
	case p.ParticipantType.Member != nil:
		m := p.ParticipantType.Member
		return strings.TrimSpace(m.FirstName.Text + " " + m.LastName.Text)
	case p.ParticipantType.Organization != nil:
		return p.ParticipantType.Organization.Name.Text
	}
	return ""
}

func linkedInImageURL(vi *linkedingo.VectorImage) string {
	if vi == nil || vi.RootURL == "" || len(vi.Artifacts) == 0 {
		return ""
	}
	return vi.RootURL + vi.GetLargestArtifact().FileIdentifyingURLPathSegment
}

func linkedInAvatar(p linkedingo.MessagingParticipant) string {
	switch {
	case p.ParticipantType.Member != nil:
		return linkedInImageURL(p.ParticipantType.Member.ProfilePicture)
	case p.ParticipantType.Organization != nil:
		return linkedInImageURL(p.ParticipantType.Organization.Logo)
	}
	return ""
}

func (l *LinkedIn) emitParticipant(p linkedingo.MessagingParticipant) {
	id := p.EntityURN.ID()
	if id == "" {
		return
	}
	if _, sent := l.contacts.LoadOrStore(id, true); sent {
		return
	}
	name := linkedInName(p)
	first := name
	if p.ParticipantType.Member != nil {
		first = firstNonEmpty(p.ParticipantType.Member.FirstName.Text, name)
	}
	l.emit(map[string]any{"type": "contact", "id": id, "name": name, "firstName": first,
		"avatarUrl": linkedInAvatar(p)})
}

// --- messages ---

func (l *LinkedIn) emitMessage(ctx context.Context, conversation linkedingo.URN, msg *linkedingo.Message) {
	if msg.EntityURN.IsEmpty() {
		return
	}
	if conversation.IsEmpty() {
		conversation = firstURN(msg.ConversationURN, msg.Conversation.EntityURN, msg.BackendConversationURN)
	}
	id := msg.EntityURN.String()
	key := conversation.String()
	if msg.MessageBodyRenderFormat == linkedingo.MessageBodyRenderFormatRecalled {
		l.emit(map[string]any{"type": "unsend", "threadKey": key, "messageId": id})
		return
	}
	l.emitParticipant(msg.Sender)
	sender := msg.Sender.EntityURN.ID()
	sentAt := msg.DeliveredAt.Time
	text := msg.Body.Text
	l.messages.Store(id, linkedInMessageInfo{conversation: conversation, sentAt: sentAt, senderID: sender, text: text})
	if previous, ok := l.oldest.Load(key); !ok || sentAt.Before(previous.(time.Time)) {
		l.oldest.Store(key, sentAt)
	}
	kind := "text"
	var replyTo string
	var media []linkedingo.RenderContent
	for _, rc := range msg.RenderContent {
		switch {
		case rc.RepliedMessageContent != nil:
			replyTo = rc.RepliedMessageContent.OriginalMessage.EntityURN.String()
		case rc.ForwardedMessage != nil:
			text = firstNonEmpty(text, rc.ForwardedMessage.ForwardedBody.Text)
		case linkedInMediaKind(rc) != "other":
			media = append(media, rc)
		}
		// Other content (e.g. the card on a sponsored or LinkedIn Learning message) is left out: the text says it
	}
	if len(media) > 0 {
		kind = linkedInMediaKind(media[0])
	}
	if text == "" && kind == "text" {
		kind = "other"
	}
	event := map[string]any{
		"type": "message", "threadKey": key, "id": id, "otid": id, "senderId": sender, "text": text, "kind": kind,
		"timestampMs": sentAt.UnixMilli(),
	}
	if replyTo != "" {
		event["replyToId"] = replyTo
	}
	if msg.MessageBodyRenderFormat == linkedingo.MessageBodyRenderFormatEdited {
		event["isEdited"] = true
	}
	l.emit(event)
	for _, r := range msg.ReactionSummaries {
		if r.ViewerReacted {
			l.emitReaction(key, id, l.selfID, r.Emoji, true)
		}
	}
	if len(media) > 0 {
		go l.downloadMedia(key, id, kind, media[0])
	}
}

func firstURN(urns ...linkedingo.URN) linkedingo.URN {
	for _, u := range urns {
		if !u.IsEmpty() {
			return u
		}
	}
	return linkedingo.URN{}
}

func linkedInMediaKind(rc linkedingo.RenderContent) string {
	switch {
	case rc.VectorImage != nil, rc.ExternalMedia != nil:
		return "image"
	case rc.Video != nil:
		return "video"
	case rc.Audio != nil:
		return "audio"
	case rc.File != nil:
		kind, _ := mediaKind(rc.File.MediaType)
		return kind
	}
	return "other"
}

// downloadMedia saves an attachment (LinkedIn's media links need the login) and points the app to the copy.
func (l *LinkedIn) downloadMedia(threadKey, messageID, kind string, rc linkedingo.RenderContent) {
	client, err := l.current()
	if err != nil {
		return
	}
	var link, mimeType, fileName string
	var width, height int
	var durationMs int64
	switch {
	case rc.VectorImage != nil:
		link = linkedInImageURL(rc.VectorImage)
		a := rc.VectorImage.GetLargestArtifact()
		width, height, mimeType = a.Width, a.Height, "image/jpeg"
	case rc.ExternalMedia != nil:
		link, width, height = rc.ExternalMedia.Media.URL, rc.ExternalMedia.Media.OriginalWidth, rc.ExternalMedia.Media.OriginalHeight
		mimeType = "image/gif"
	case rc.Video != nil && len(rc.Video.ProgressiveStreams) > 0 && len(rc.Video.ProgressiveStreams[0].StreamingLocations) > 0:
		link, mimeType = rc.Video.ProgressiveStreams[0].StreamingLocations[0].URL, "video/mp4"
		durationMs = rc.Video.Duration.Milliseconds()
	case rc.Audio != nil:
		link, mimeType, durationMs = rc.Audio.URL, "audio/mp4", rc.Audio.Duration.Milliseconds()
	case rc.File != nil && rc.File.ByteSize <= linkedInMaxDownload:
		link, mimeType, fileName = rc.File.URL, rc.File.MediaType, rc.File.Name
	}
	if link == "" {
		return
	}
	dir := filepath.Join(l.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return
	}
	path := filepath.Join(dir, strings.NewReplacer("/", "_", ":", "_", ",", "_", "(", "_", ")", "_").Replace(messageID))
	if _, err := os.Stat(path); err != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		data, err := client.DownloadBytes(ctx, link)
		cancel()
		if err != nil || len(data) > linkedInMaxDownload {
			l.log.Error().Err(err).Msg("Failed to download a LinkedIn attachment")
			return
		}
		if err := writeFileAtomic(path, data); err != nil {
			return
		}
	}
	event := map[string]any{
		"type": "attachment", "threadKey": threadKey, "messageId": messageID, "kind": kind, "url": "file://" + path,
		"previewUrl": "file://" + path, "mimeType": mimeType, "fileName": fileName, "width": width, "height": height,
	}
	if durationMs > 0 {
		event["durationMs"] = durationMs
	}
	l.emit(event)
}

func (l *LinkedIn) emitReaction(threadKey, messageID, sender, emoji string, added bool) {
	if sender == l.selfID {
		if added {
			l.ownReactions.Store(messageID, emoji)
		} else {
			l.ownReactions.Delete(messageID)
		}
	}
	if !added {
		emoji = ""
	}
	l.emit(map[string]any{"type": "reaction", "threadKey": threadKey, "targetId": messageID, "senderId": sender,
		"emoji": emoji})
}

// --- real-time events ---

func (l *LinkedIn) handleEvent(ctx context.Context, evt *linkedingo.DecoratedEvent) {
	data := evt.Payload.Data
	switch {
	case data.DecoratedMessage != nil:
		msg := &data.DecoratedMessage.Result
		conv := firstURN(msg.Conversation.EntityURN, msg.ConversationURN, msg.BackendConversationURN)
		if len(msg.Conversation.ConversationParticipants) > 0 {
			l.emitConversation(&msg.Conversation)
		}
		l.emitMessage(ctx, conv, msg)
		l.emit(map[string]any{"type": "thread", "key": conv.String(), "lastActivityMs": msg.DeliveredAt.UnixMilli()})
	case data.DecoratedConversation != nil:
		// Conversation updates (renames, new chats) come as the full conversation
		l.emitConversation(&data.DecoratedConversation.Result)
	case data.DecoratedReactionSummary != nil:
		r := &data.DecoratedReactionSummary.Result
		msgID := r.Message.EntityURN.String()
		threadKey := ""
		if info, ok := l.messages.Load(msgID); ok {
			threadKey = info.(linkedInMessageInfo).conversation.String()
		} else {
			threadKey = firstURN(r.Message.ConversationURN, r.Message.Conversation.EntityURN).String()
		}
		l.emitReaction(threadKey, msgID, r.Actor.EntityURN.ID(), r.ReactionSummary.Emoji, r.ReactionAdded)
	case data.DecoratedTypingIndicator != nil:
		t := &data.DecoratedTypingIndicator.Result
		l.emitTyping(t.Conversation.EntityURN.String(), t.TypingParticipant.EntityURN.ID())
	case data.DecoratedSeenReceipt != nil:
		s := &data.DecoratedSeenReceipt.Result
		threadKey := firstURN(s.Message.ConversationURN, s.Message.Conversation.EntityURN).String()
		if info, ok := l.messages.Load(s.Message.EntityURN.String()); ok {
			threadKey = info.(linkedInMessageInfo).conversation.String()
		}
		l.emit(map[string]any{"type": "read", "threadKey": threadKey, "contactId": s.SeenByParticipant.EntityURN.ID(),
			"watermarkMs": s.SeenAt.UnixMilli()})
	}
}

func (l *LinkedIn) emitTyping(threadKey, sender string) {
	if sender == "" || sender == l.selfID || threadKey == ":" {
		return
	}
	l.emit(map[string]any{"type": "typing", "threadKey": threadKey, "senderId": sender, "isTyping": true})
	key := threadKey + "/" + sender
	timer := time.AfterFunc(linkedInTypingTimeout, func() {
		l.typingTimers.Delete(key)
		l.emit(map[string]any{"type": "typing", "threadKey": threadKey, "senderId": sender, "isTyping": false})
	})
	if previous, loaded := l.typingTimers.Swap(key, timer); loaded {
		previous.(*time.Timer).Stop()
	}
}

// --- sending ---

func linkedInContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), time.Minute)
}

func (l *LinkedIn) send(threadKey, text string, content []linkedingo.SendRenderContent) (string, error) {
	client, err := l.current()
	if err != nil {
		return "", err
	}
	ctx, cancel := linkedInContext()
	defer cancel()
	key, personID := l.resolveKey(threadKey)
	if personID != "" {
		return l.createChat(ctx, client, threadKey, personID, text, content)
	}
	resp, err := client.SendMessage(ctx, linkedingo.NewURN(key), linkedingo.SendMessageBody{Text: text}, content,
		uuid.NewString())
	if err != nil {
		return "", err
	}
	return resp.Data.EntityURN.String(), nil
}

// StartChat returns the conversation with a person: the one there is, or a new-chat key whose first message
// creates it on LinkedIn (which has no empty chats).
func (l *LinkedIn) StartChat(personID string) (string, error) {
	if key, ok := l.directChats.Load(personID); ok {
		return key.(string), nil
	}
	return linkedInNewChatPrefix + personID, nil
}

// resolveKey gives the conversation a thread key stands for, or, for a chat its first message hasn't created yet,
// the person it's with.
func (l *LinkedIn) resolveKey(threadKey string) (key, newChatWith string) {
	personID, isNew := strings.CutPrefix(threadKey, linkedInNewChatPrefix)
	if !isNew {
		return threadKey, ""
	}
	if created, ok := l.started.Load(threadKey); ok {
		return created.(string), ""
	}
	if existing, ok := l.directChats.Load(personID); ok {
		return existing.(string), ""
	}
	return threadKey, personID
}

// createChat creates the conversation with the first message and tells the app the new-chat key moved there.
func (l *LinkedIn) createChat(ctx context.Context, client *linkedingo.Client, threadKey, personID, text string,
	content []linkedingo.SendRenderContent) (string, error) {
	resp, err := client.NewChatWithMessage(ctx, "", []linkedingo.URN{linkedingo.NewURN(personID).AsFsdProfile()},
		linkedingo.SendMessageBody{Text: text}, content, uuid.NewString())
	if err != nil {
		return "", err
	}
	created := firstURN(resp.Data.ConversationURN, resp.Data.BackendConversationURN).String()
	if created != "" {
		l.started.Store(threadKey, created)
		l.directChats.Store(personID, created)
		l.emit(map[string]any{"type": "moved", "from": threadKey, "to": created})
	}
	return resp.Data.EntityURN.String(), nil
}

// SendText sends a message and returns its URN (the echo's otid).
func (l *LinkedIn) SendText(threadKey, text string) (string, error) {
	return l.send(threadKey, text, nil)
}

// SendReply sends a message quoting an earlier one.
func (l *LinkedIn) SendReply(threadKey, text, replyToID string) (string, error) {
	info, ok := l.messages.Load(replyToID)
	if !ok {
		return l.SendText(threadKey, text)
	}
	original := info.(linkedInMessageInfo)
	return l.send(threadKey, text, []linkedingo.SendRenderContent{{
		RepliedMessageContent: &linkedingo.SendRepliedMessage{
			OriginalSenderURN: linkedingo.NewURN(original.senderID).
				WithPrefix("urn:li:msg_messagingParticipant:urn:li:fsd_profile"),
			OriginalSendAt:     jsontime.UnixMilli{Time: original.sentAt},
			OriginalMessageURN: linkedingo.NewURN(replyToID),
			MessageBody:        linkedingo.AttributedText{Text: original.text},
		},
	}})
}

// EditMessage changes the text of one of our messages.
func (l *LinkedIn) EditMessage(threadKey, messageID, text string) error {
	client, err := l.current()
	if err != nil {
		return err
	}
	ctx, cancel := linkedInContext()
	defer cancel()
	return client.EditMessage(ctx, linkedingo.NewURN(messageID), linkedingo.SendMessageBody{Text: text})
}

// Unsend recalls one of our messages for everyone.
func (l *LinkedIn) Unsend(threadKey, messageID string) error {
	client, err := l.current()
	if err != nil {
		return err
	}
	ctx, cancel := linkedInContext()
	defer cancel()
	return client.RecallMessage(ctx, linkedingo.NewURN(messageID))
}

// SendReaction sets our reaction on a message (replacing the one we had); an empty emoji removes it.
func (l *LinkedIn) SendReaction(threadKey, messageID, emoji string) error {
	client, err := l.current()
	if err != nil {
		return err
	}
	ctx, cancel := linkedInContext()
	defer cancel()
	urn := linkedingo.NewURN(messageID)
	if previous, ok := l.ownReactions.Load(messageID); ok {
		if err := client.RemoveReaction(ctx, urn, previous.(string)); err != nil {
			return err
		}
		l.ownReactions.Delete(messageID)
	}
	if emoji == "" {
		return nil
	}
	if err := client.SendReaction(ctx, urn, emoji); err != nil {
		return err
	}
	l.ownReactions.Store(messageID, emoji)
	return nil
}

// MarkRead marks a conversation read.
func (l *LinkedIn) MarkRead(threadKey string) error {
	client, err := l.current()
	if err != nil {
		return err
	}
	key, newChatWith := l.resolveKey(threadKey)
	if newChatWith != "" {
		return nil
	}
	ctx, cancel := linkedInContext()
	defer cancel()
	_, err = client.MarkConversationRead(ctx, linkedingo.NewURN(key))
	return err
}

// SetTyping shows "typing…" (LinkedIn has no "stopped typing"; it times out).
func (l *LinkedIn) SetTyping(threadKey string, isTyping bool) error {
	if !isTyping {
		return nil
	}
	client, err := l.current()
	if err != nil {
		return err
	}
	key, newChatWith := l.resolveKey(threadKey)
	if newChatWith != "" {
		return nil
	}
	ctx, cancel := linkedInContext()
	defer cancel()
	return client.StartTyping(ctx, linkedingo.NewURN(key))
}

// SendMediaFile uploads a photo or file into a conversation and returns the message URN.
func (l *LinkedIn) SendMediaFile(threadKey, path, mimeType, fileName, caption string) (string, error) {
	defer os.Remove(path)
	client, err := l.current()
	if err != nil {
		return "", err
	}
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return "", err
	}
	if fileName == "" {
		fileName = filepath.Base(path)
	}
	// The app's recordings come as "Voice message.m4a" (AAC, which LinkedIn's voice messages are)
	isVoice := strings.HasPrefix(fileName, linkedInVoiceFileName) && mimeType == "audio/mp4"
	uploadType := linkedingo.MediaUploadTypeFileAttachment
	switch {
	case isVoice:
		uploadType = linkedingo.MediaUploadTypeVoiceMessage
	case strings.HasPrefix(mimeType, "image/"):
		uploadType = linkedingo.MediaUploadTypePhotoAttachment
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	urn, err := client.UploadMedia(ctx, uploadType, fileName, mimeType, int(info.Size()), file)
	if err != nil {
		return "", err
	}
	if isVoice {
		return l.send(threadKey, caption, []linkedingo.SendRenderContent{{
			Audio: &linkedingo.SendAudio{AssetURN: urn, ByteSize: int(info.Size())},
		}})
	}
	return l.send(threadKey, caption, []linkedingo.SendRenderContent{{
		File: &linkedingo.SendFile{AssetURN: urn, Name: fileName, MediaType: mimeType, ByteSize: int(info.Size())},
	}})
}

// RequestOlder loads messages before the oldest one loaded (the newest when none are); they arrive as message
// events, then a range event.
func (l *LinkedIn) RequestOlder(threadKey, beforeMessageID string) error {
	client, err := l.current()
	if err != nil {
		return err
	}
	before := time.Now()
	if info, ok := l.messages.Load(beforeMessageID); ok {
		before = info.(linkedInMessageInfo).sentAt
	} else if oldest, ok := l.oldest.Load(threadKey); ok && beforeMessageID != "" {
		before = oldest.(time.Time)
	}
	key, newChatWith := l.resolveKey(threadKey)
	if newChatWith != "" {
		// Nothing before the first message
		l.emit(map[string]any{"type": "range", "threadKey": threadKey, "hasMoreBefore": false})
		return nil
	}
	ctx, cancel := linkedInContext()
	defer cancel()
	conv := linkedingo.NewURN(key)
	resp, err := client.GetMessagesBefore(ctx, conv, before.Add(-time.Millisecond), linkedInPageSize)
	if err != nil {
		return err
	}
	count := 0
	if resp != nil {
		count = len(resp.Elements)
		for i := range resp.Elements {
			l.emitMessage(ctx, conv, &resp.Elements[i])
		}
	}
	l.emit(map[string]any{"type": "range", "threadKey": key, "hasMoreBefore": count >= linkedInPageSize})
	return nil
}

func (l *LinkedIn) emitState(state, err string) {
	l.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (l *LinkedIn) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && l.listener != nil {
		l.listener.OnEvent(string(data))
	}
}

// SearchPeople finds LinkedIn members by name (LinkedIn's people search).
func (l *LinkedIn) SearchPeople(query string) (string, error) {
	client, err := l.current()
	if err != nil {
		return "", err
	}
	ctx, cancel := linkedInContext()
	defer cancel()
	results, err := client.Search(ctx, query)
	if err != nil {
		return "", err
	}
	people := make([]foundPerson, 0, len(results))
	for _, r := range results {
		id := r.ProfileURN.ID()
		if id == "" || id == l.selfID {
			continue
		}
		avatar := ""
		if r.ProfilePicture != nil {
			avatar = linkedInImageURL(r.ProfilePicture)
		}
		// LinkedIn has no handles: the headline ("Senior Developer at …") tells people with the same name apart
		people = append(people, foundPerson{ID: id, Name: strings.TrimSpace(r.FirstName + " " + r.LastName),
			Handle: strings.TrimSpace(r.Headline), AvatarURL: avatar})
	}
	return peopleJSON(people)
}
