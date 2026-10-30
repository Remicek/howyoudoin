package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/util"
)

// GoogleMessages is the user's SMS and RCS chats, through the Google Messages app on their Android phone, paired
// like messages.google.com (mautrix-gmessages' libgm client): by scanning a QR code with the Messages app, or by
// signing in with the same Google account and tapping the emoji the Messages app shows. The phone must stay on and
// online, as with Messages for web. Thread keys are conversation IDs, contact IDs participant IDs.
type GoogleMessages struct {
	dataDir  string
	listener Listener
	log      zerolog.Logger

	lock   sync.Mutex
	client *libgm.Client
	cancel context.CancelFunc

	// Participant IDs that are the user (one per SIM)
	selfIDs sync.Map
	// Conversation ID → *gmproto.Conversation, for sending (outgoing participant and SIM)
	convos sync.Map
	// Phone number → participant ID, for typing events (which only carry the number)
	byNumber sync.Map
	contacts sync.Map
	// Conversation ID → cursor of its next older page
	cursors sync.Map
	// Message ID → our reaction on it
	ownReactions sync.Map
}

const (
	googleMessagesSelfID     = "me"
	googleMessagesConvoCount = 50
	googleMessagesPrefetch   = 20
	googleMessagesPageSize   = 30
	googleMessagesQRAttempts = 6
	googleMessagesQRLifetime = 30 * time.Second
	googleMessagesMaxMedia   = 25 << 20
)

// NewGoogleMessages loads the pairing saved in dataDir, if any.
func NewGoogleMessages(dataDir string, listener Listener) (*GoogleMessages, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "googlemessages").Logger().
		Level(zerolog.InfoLevel)
	return &GoogleMessages{dataDir: dataDir, listener: listener, log: log}, nil
}

func (g *GoogleMessages) sessionPath() string { return filepath.Join(g.dataDir, "session.json") }

// IsLinked says whether a pairing is saved.
func (g *GoogleMessages) IsLinked() bool {
	_, err := os.Stat(g.sessionPath())
	return err == nil
}

func (g *GoogleMessages) loadSession() (*libgm.AuthData, error) {
	data, err := readSecretFile(g.sessionPath())
	if err != nil {
		return nil, err
	}
	auth := libgm.NewAuthData()
	if err := json.Unmarshal(data, auth); err != nil {
		return nil, err
	}
	return auth, nil
}

func (g *GoogleMessages) saveSession(auth *libgm.AuthData) {
	if data, err := json.Marshal(auth); err == nil {
		_ = writeSecretFile(g.sessionPath(), data)
	}
}

func (g *GoogleMessages) newClient(auth *libgm.AuthData) *libgm.Client {
	return libgm.NewClient(auth, nil, g.log.With().Str("component", "libgm").Logger(), exhttp.ClientSettings{})
}

// Connect connects with the saved pairing and loads the conversations.
func (g *GoogleMessages) Connect() error {
	auth, err := g.loadSession()
	if err != nil {
		return errors.New("Google Messages isn't paired")
	}
	g.lock.Lock()
	if g.client != nil {
		g.lock.Unlock()
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(g.log.WithContext(context.Background()))
	g.cancel = cancel
	client := g.newClient(auth)
	client.SetEventHandler(g.handleEvent)
	g.client = client
	g.lock.Unlock()

	if err := client.FetchConfig(ctx); err != nil {
		g.log.Warn().Err(err).Msg("Failed to fetch the Google Messages config")
	}
	if err := client.Connect(ctx); err != nil {
		g.reset()
		switch {
		case errors.Is(err, events.ErrRequestedEntityNotFound), errors.Is(err, events.ErrInvalidCredentials):
			return errors.New("Google Messages was unpaired, pair it again")
		}
		return fmt.Errorf("failed to connect to Google Messages: %w", err)
	}
	g.emit(map[string]any{"type": "self", "id": googleMessagesSelfID, "name": "Google Messages"})
	g.emitState("connected", "")
	go g.syncConversations(ctx)
	return nil
}

func (g *GoogleMessages) reset() {
	g.lock.Lock()
	client, cancel := g.client, g.cancel
	g.client, g.cancel = nil, nil
	g.lock.Unlock()
	if client != nil {
		client.Disconnect()
	}
	if cancel != nil {
		cancel()
	}
}

// Disconnect closes the connection; Connect can be called again.
func (g *GoogleMessages) Disconnect() {
	g.reset()
	g.emitState("disconnected", "")
}

// Logout unpairs this app from the phone and forgets the pairing.
func (g *GoogleMessages) Logout() error {
	g.lock.Lock()
	client := g.client
	g.lock.Unlock()
	if client != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		_ = client.Unpair(ctx)
		cancel()
	}
	g.reset()
	return os.Remove(g.sessionPath())
}

func (g *GoogleMessages) current() (*libgm.Client, error) {
	g.lock.Lock()
	defer g.lock.Unlock()
	if g.client == nil {
		return nil, errors.New("not connected")
	}
	return g.client, nil
}

// --- pairing ---

// StartLinking shows a QR code to scan with the Messages app (Device pairing → QR code scanner); it's refreshed
// every 30 seconds for a few minutes. When the phone accepts, the pairing is saved and the client connects.
func (g *GoogleMessages) StartLinking() error {
	g.CancelLinking()
	ctx, cancel := context.WithCancel(g.log.WithContext(context.Background()))
	client := g.newClient(libgm.NewAuthData())
	client.SetEventHandler(func(any) {})
	paired := make(chan *gmproto.PairedData, 1)
	callback := func(data *gmproto.PairedData) { paired <- data }
	client.PairCallback.Store(&callback)
	g.lock.Lock()
	g.client, g.cancel = client, cancel
	g.lock.Unlock()
	if err := client.FetchConfig(ctx); err != nil {
		g.reset()
		return fmt.Errorf("failed to start pairing: %w", err)
	}
	qr, err := client.StartLogin(ctx)
	if err != nil {
		g.reset()
		return fmt.Errorf("failed to start pairing: %w", err)
	}
	g.emit(map[string]any{"type": "pairing", "qr": qr, "timeoutMs": googleMessagesQRLifetime.Milliseconds()})
	go func() {
		for attempt := 1; ; attempt++ {
			select {
			case <-ctx.Done():
				return
			case <-paired:
				g.finishPairing(client, true)
				return
			case <-time.After(googleMessagesQRLifetime):
				if attempt >= googleMessagesQRAttempts {
					g.reset()
					g.emitState("pairing_failed", "timeout")
					return
				}
				qr, err := client.RefreshPhoneRelay()
				if err != nil {
					g.reset()
					g.emitState("pairing_failed", err.Error())
					return
				}
				g.emit(map[string]any{"type": "pairing", "qr": qr,
					"timeoutMs": googleMessagesQRLifetime.Milliseconds()})
			}
		}
	}()
	return nil
}

// StartGoogleLinking pairs through the Google account signed in on messages.google.com (its cookies, as a JSON
// object): an emoji is shown here ("pairing" event with a "code"), and pairing finishes when the user taps the same
// emoji in the Messages app on their phone.
func (g *GoogleMessages) StartGoogleLinking(cookiesJSON string) error {
	var cookies map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &cookies); err != nil {
		return fmt.Errorf("invalid login: %w", err)
	}
	g.CancelLinking()
	auth := libgm.NewAuthData()
	auth.Cookies = cookies
	ctx, cancel := context.WithCancel(g.log.WithContext(context.Background()))
	client := g.newClient(auth)
	client.SetEventHandler(func(any) {})
	g.lock.Lock()
	g.client, g.cancel = client, cancel
	g.lock.Unlock()
	if err := client.FetchConfig(ctx); err != nil {
		g.reset()
		return fmt.Errorf("failed to start pairing: %w", err)
	}
	if client.Config.GetDeviceInfo().GetEmail() == "" {
		g.reset()
		return errors.New("Google didn't accept the sign-in, log in again")
	}
	emoji, session, err := client.StartGaiaPairing(ctx, ctx)
	if err != nil {
		g.reset()
		switch {
		case errors.Is(err, libgm.ErrNoDevicesFound):
			return errors.New("No phone with Google Messages found on this Google account")
		case errors.Is(err, libgm.ErrPairingInitTimeout):
			return errors.New("Your phone didn't respond: check that Google Messages is set up and online")
		}
		return fmt.Errorf("failed to start pairing: %w", err)
	}
	g.emit(map[string]any{"type": "pairing", "code": emoji})
	go func() {
		_, err := client.FinishGaiaPairing(ctx, session)
		switch {
		case err == nil:
			g.finishPairing(client, false)
		case ctx.Err() != nil:
		case errors.Is(err, libgm.ErrIncorrectEmoji):
			g.reset()
			g.emitState("pairing_failed", "a different emoji was tapped on the phone")
		case errors.Is(err, libgm.ErrPairingCancelled):
			g.reset()
			g.emitState("pairing_failed", "cancelled on the phone")
		case errors.Is(err, libgm.ErrPairingTimeout):
			g.reset()
			g.emitState("pairing_failed", "timeout")
		default:
			g.reset()
			g.emitState("pairing_failed", err.Error())
		}
	}()
	return nil
}

func (g *GoogleMessages) finishPairing(client *libgm.Client, fromQR bool) {
	g.saveSession(client.AuthData)
	g.reset()
	if fromQR {
		// The phone needs a moment to save the pairing, or it doesn't recognize the reconnect
		time.Sleep(2 * time.Second)
	}
	if err := g.Connect(); err != nil {
		g.emitState("error", err.Error())
	}
}

// CancelLinking stops a pairing in progress (a connected client is left alone).
func (g *GoogleMessages) CancelLinking() {
	if g.IsLinked() {
		return
	}
	g.reset()
}

// --- events ---

func (g *GoogleMessages) handleEvent(raw any) {
	switch evt := raw.(type) {
	case *events.AuthTokenRefreshed:
		g.lock.Lock()
		client := g.client
		g.lock.Unlock()
		if client != nil {
			g.saveSession(client.AuthData)
		}
	case *events.ListenFatalError:
		g.emitState("error", "Google Messages disconnected: "+errString(evt.Error))
	case *events.ListenTemporaryError:
		g.emitState("reconnecting", errString(evt.Error))
	case *events.ListenRecovered, *events.PhoneRespondingAgain:
		g.emitState("connected", "")
	case *events.PhoneNotResponding:
		g.emitState("reconnecting", "Your phone isn't responding: check that it's on and online")
	case *events.GaiaLoggedOut:
		g.emitState("error", "Signed out of Google, pair again")
	case *gmproto.RevokePairData:
		// Unpaired from the phone: the app forgets the account
		_ = os.Remove(g.sessionPath())
		g.emitState("logged_out", "")
	case *gmproto.Conversation:
		g.emitConversation(evt)
	case *libgm.WrappedMessage:
		g.emitMessage(evt.Message)
		if !evt.IsOld {
			g.emit(map[string]any{"type": "thread", "key": evt.ConversationID, "lastActivityMs": evt.Timestamp / 1000})
		}
	case *gmproto.TypingData:
		sender, ok := g.byNumber.Load(evt.GetUser().GetNumber())
		if ok && sender != googleMessagesSelfID {
			g.emit(map[string]any{"type": "typing", "threadKey": evt.ConversationID, "senderId": sender,
				"isTyping": evt.Type == gmproto.TypingTypes_STARTED_TYPING})
		}
	}
}

// --- conversations ---

func (g *GoogleMessages) syncConversations(ctx context.Context) {
	client, err := g.current()
	if err != nil {
		return
	}
	resp, err := client.ListConversations(ctx, &gmproto.ListConversationsRequest{
		Count: googleMessagesConvoCount, Folder: gmproto.ListConversationsRequest_INBOX,
	})
	if err != nil {
		g.log.Warn().Err(err).Msg("Failed to load Google Messages conversations")
		return
	}
	convos := resp.GetConversations()
	for _, c := range convos {
		g.emitConversation(c)
	}
	for i, c := range convos {
		if i >= googleMessagesPrefetch || ctx.Err() != nil {
			return
		}
		if err := g.RequestOlder(c.ConversationID, ""); err != nil {
			g.log.Warn().Err(err).Msg("Failed to load recent Google Messages messages")
		}
	}
}

func (g *GoogleMessages) participantID(p *gmproto.Participant) string {
	return p.GetID().GetParticipantID()
}

func (g *GoogleMessages) emitConversation(c *gmproto.Conversation) {
	if c.GetConversationID() == "" {
		return
	}
	g.convos.Store(c.ConversationID, c)
	var names []string
	for _, p := range c.Participants {
		id := g.participantID(p)
		if id == "" {
			continue
		}
		if p.IsMe {
			g.selfIDs.Store(id, true)
			if number := p.GetID().GetNumber(); number != "" {
				g.byNumber.Store(number, googleMessagesSelfID)
			}
			continue
		}
		if number := p.GetID().GetNumber(); number != "" {
			g.byNumber.Store(number, id)
		}
		g.emitParticipant(p)
		names = append(names, firstNonEmpty(p.FullName, p.FormattedNumber, p.GetID().GetNumber()))
		g.emit(map[string]any{"type": "participant", "threadKey": c.ConversationID, "contactId": id})
	}
	lastActivity := c.LastMessageTimestamp / 1000
	lastRead := lastActivity
	if c.Unread {
		lastRead = lastActivity - 1
	}
	g.emit(map[string]any{
		"type": "thread", "key": c.ConversationID, "name": firstNonEmpty(c.Name, strings.Join(names, ", ")),
		"isGroup": c.IsGroupChat, "isEncrypted": false, "lastActivityMs": lastActivity, "lastReadMs": lastRead,
	})
}

func (g *GoogleMessages) emitParticipant(p *gmproto.Participant) {
	id := g.participantID(p)
	name := firstNonEmpty(p.FullName, p.FormattedNumber, p.GetID().GetNumber())
	if previous, ok := g.contacts.Load(id); ok && previous == name {
		return
	}
	g.contacts.Store(id, name)
	g.emit(map[string]any{"type": "contact", "id": id, "name": name,
		"firstName": firstNonEmpty(p.FirstName, strings.SplitN(name, " ", 2)[0])})
}

// --- messages ---

func (g *GoogleMessages) senderOf(m *gmproto.Message) string {
	if _, isSelf := g.selfIDs.Load(m.ParticipantID); isSelf || m.GetSenderParticipant().GetIsMe() {
		return googleMessagesSelfID
	}
	if status := m.GetMessageStatus().GetStatus(); status > 0 && status < 100 {
		// Statuses below 100 are outgoing ones
		return googleMessagesSelfID
	}
	return m.ParticipantID
}

func (g *GoogleMessages) emitMessage(m *gmproto.Message) {
	if m == nil || m.MessageID == "" {
		return
	}
	switch m.GetMessageStatus().GetStatus() {
	case gmproto.MessageStatusType_MESSAGE_DELETED, gmproto.MessageStatusType_OUTGOING_DELETED,
		gmproto.MessageStatusType_INCOMING_DELETED:
		g.emit(map[string]any{"type": "unsend", "threadKey": m.ConversationID, "messageId": m.MessageID})
		return
	}
	if p := m.GetSenderParticipant(); p != nil && !p.IsMe && g.participantID(p) != "" {
		g.emitParticipant(p)
	}
	var texts []string
	var media *gmproto.MediaContent
	for _, info := range m.MessageInfo {
		if content := info.GetMessageContent(); content != nil && content.Content != "" {
			texts = append(texts, content.Content)
		}
		if mc := info.GetMediaContent(); mc != nil && media == nil {
			media = mc
		}
	}
	if subject := m.GetSubject(); subject != "" {
		texts = append([]string{subject}, texts...)
	}
	text := strings.Join(texts, "\n")
	kind := "text"
	if media != nil {
		kind, _ = mediaKind(media.MimeType)
		if kind == "file" && text == "" {
			text = media.MediaName
		}
	}
	if text == "" && media == nil {
		kind = "other"
	}
	event := map[string]any{
		"type": "message", "threadKey": m.ConversationID, "id": m.MessageID, "otid": firstNonEmpty(m.TmpID, m.MessageID),
		"senderId": g.senderOf(m), "text": text, "kind": kind, "timestampMs": m.Timestamp / 1000,
	}
	if reply := m.GetReplyMessage().GetMessageID(); reply != "" {
		event["replyToId"] = reply
	}
	g.emit(event)
	for _, r := range m.Reactions {
		for _, id := range r.ParticipantIDs {
			sender := id
			if _, isSelf := g.selfIDs.Load(id); isSelf {
				sender = googleMessagesSelfID
				g.ownReactions.Store(m.MessageID, r.GetData().GetUnicode())
			}
			g.emit(map[string]any{"type": "reaction", "threadKey": m.ConversationID, "targetId": m.MessageID,
				"senderId": sender, "emoji": r.GetData().GetUnicode()})
		}
	}
	if media != nil && media.Size <= googleMessagesMaxMedia && (media.MediaID != "" || len(media.MediaData) > 0) {
		go g.downloadMedia(m.ConversationID, m.MessageID, kind, media)
	}
}

func (g *GoogleMessages) downloadMedia(convID, messageID, kind string, media *gmproto.MediaContent) {
	client, err := g.current()
	if err != nil {
		return
	}
	dir := filepath.Join(g.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return
	}
	path := filepath.Join(dir, strings.NewReplacer("/", "_", ":", "_").Replace(messageID))
	if _, err := os.Stat(path); err != nil {
		data := media.MediaData
		if len(data) == 0 {
			reader, err := client.DownloadMedia(media.MediaID, media.DecryptionKey)
			if err != nil {
				g.log.Warn().Err(err).Msg("Failed to download Google Messages media")
				return
			}
			data, err = io.ReadAll(io.LimitReader(reader, googleMessagesMaxMedia+1))
			_ = reader.Close()
			if err != nil || len(data) > googleMessagesMaxMedia {
				return
			}
		}
		if err := writeFileAtomic(path, data); err != nil {
			return
		}
	}
	event := map[string]any{
		"type": "attachment", "threadKey": convID, "messageId": messageID, "kind": kind, "url": "file://" + path,
		"previewUrl": "file://" + path, "mimeType": media.MimeType, "fileName": media.MediaName,
	}
	if d := media.GetDimensions(); d != nil {
		event["width"], event["height"] = d.Width, d.Height
	}
	g.emit(event)
}

// --- sending ---

func googleMessagesContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), time.Minute)
}

// outgoing is the user's participant in a conversation and the SIM it sends from.
func (g *GoogleMessages) outgoing(convID string) (string, *gmproto.SIMPayload) {
	value, ok := g.convos.Load(convID)
	if !ok {
		return "", nil
	}
	c := value.(*gmproto.Conversation)
	for _, p := range c.Participants {
		if p.IsMe && (c.DefaultOutgoingID == "" || g.participantID(p) == c.DefaultOutgoingID) {
			return g.participantID(p), p.GetSimPayload()
		}
	}
	return c.DefaultOutgoingID, nil
}

func (g *GoogleMessages) send(convID, replyToID string, info *gmproto.MessageInfo) (string, error) {
	client, err := g.current()
	if err != nil {
		return "", err
	}
	tmpID := util.GenerateTmpID()
	participant, sim := g.outgoing(convID)
	req := &gmproto.SendMessageRequest{
		ConversationID: convID,
		MessagePayload: &gmproto.MessagePayload{
			TmpID: tmpID, TmpID2: tmpID, ConversationID: convID, ParticipantID: participant,
			MessageInfo: []*gmproto.MessageInfo{info},
		},
		SIMPayload: sim,
		TmpID:      tmpID,
	}
	if replyToID != "" {
		req.Reply = &gmproto.ReplyPayload{MessageID: replyToID}
	}
	ctx, cancel := googleMessagesContext()
	defer cancel()
	resp, err := client.SendMessage(ctx, req)
	if err != nil {
		if errors.Is(err, libgm.ErrPhoneNotResponding) {
			return "", errors.New("Your phone isn't responding: check that it's on and online")
		}
		return "", err
	}
	if resp.Status != gmproto.SendMessageResponse_SUCCESS {
		return "", fmt.Errorf("the phone didn't send it (%s)", resp.Status)
	}
	return tmpID, nil
}

func googleMessagesText(text string) *gmproto.MessageInfo {
	return &gmproto.MessageInfo{Data: &gmproto.MessageInfo_MessageContent{
		MessageContent: &gmproto.MessageContent{Content: text},
	}}
}

// SendText sends a text and returns its temporary ID (the echo's otid).
func (g *GoogleMessages) SendText(convID, text string) (string, error) {
	return g.send(convID, "", googleMessagesText(text))
}

// SendReply sends a text replying to a message (shown as a reply in RCS chats).
func (g *GoogleMessages) SendReply(convID, text, replyToID string) (string, error) {
	return g.send(convID, replyToID, googleMessagesText(text))
}

// SendMediaFile sends a photo, video, audio or file.
func (g *GoogleMessages) SendMediaFile(convID, path, mimeType, fileName string) (string, error) {
	defer os.Remove(path)
	client, err := g.current()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	if fileName == "" {
		fileName = filepath.Base(path)
	}
	media, err := client.UploadMedia(data, fileName, mimeType)
	if err != nil {
		return "", err
	}
	return g.send(convID, "", &gmproto.MessageInfo{Data: &gmproto.MessageInfo_MediaContent{MediaContent: media}})
}

// Unsend deletes a message (from the phone; SMS can't be taken back from the other person).
func (g *GoogleMessages) Unsend(convID, messageID string) error {
	client, err := g.current()
	if err != nil {
		return err
	}
	ctx, cancel := googleMessagesContext()
	defer cancel()
	_, err = client.DeleteMessage(ctx, messageID)
	return err
}

// SendReaction sets our reaction on a message (replacing the one we had); an empty emoji removes it.
func (g *GoogleMessages) SendReaction(convID, messageID, emoji string) error {
	client, err := g.current()
	if err != nil {
		return err
	}
	_, sim := g.outgoing(convID)
	action := gmproto.SendReactionRequest_ADD
	previous, had := g.ownReactions.Load(messageID)
	switch {
	case emoji == "" && !had:
		return nil
	case emoji == "":
		action, emoji = gmproto.SendReactionRequest_REMOVE, previous.(string)
	case had:
		action = gmproto.SendReactionRequest_SWITCH
	}
	ctx, cancel := googleMessagesContext()
	defer cancel()
	resp, err := client.SendReaction(ctx, &gmproto.SendReactionRequest{
		MessageID: messageID, ReactionData: gmproto.MakeReactionData(emoji), Action: action, SIMPayload: sim,
	})
	if err != nil {
		return err
	}
	if !resp.Success {
		return errors.New("the phone didn't take the reaction")
	}
	if action == gmproto.SendReactionRequest_REMOVE {
		g.ownReactions.Delete(messageID)
	} else {
		g.ownReactions.Store(messageID, emoji)
	}
	return nil
}

// MarkRead marks a conversation read up to a message.
func (g *GoogleMessages) MarkRead(convID, messageID string) error {
	if messageID == "" {
		return nil
	}
	client, err := g.current()
	if err != nil {
		return err
	}
	ctx, cancel := googleMessagesContext()
	defer cancel()
	return client.MarkRead(ctx, convID, messageID)
}

// SetTyping shows "typing…" in RCS chats.
func (g *GoogleMessages) SetTyping(convID string, isTyping bool) error {
	if !isTyping {
		return nil
	}
	client, err := g.current()
	if err != nil {
		return err
	}
	_, sim := g.outgoing(convID)
	ctx, cancel := googleMessagesContext()
	defer cancel()
	return client.SetTyping(ctx, convID, sim)
}

// RequestOlder loads the conversation's next older page (the newest when beforeMessageID is empty); the messages
// arrive as message events, then a range event.
func (g *GoogleMessages) RequestOlder(convID, beforeMessageID string) error {
	client, err := g.current()
	if err != nil {
		return err
	}
	var cursor *gmproto.Cursor
	if beforeMessageID != "" {
		value, ok := g.cursors.Load(convID)
		if !ok {
			g.emit(map[string]any{"type": "range", "threadKey": convID, "hasMoreBefore": false})
			return nil
		}
		cursor = value.(*gmproto.Cursor)
	}
	ctx, cancel := googleMessagesContext()
	defer cancel()
	resp, err := client.FetchMessages(ctx, convID, googleMessagesPageSize, cursor)
	if err != nil {
		return err
	}
	messages := slices.Clone(resp.GetMessages())
	slices.SortFunc(messages, func(a, b *gmproto.Message) int { return int(a.Timestamp - b.Timestamp) })
	for _, m := range messages {
		if m.ConversationID == "" {
			m.ConversationID = convID
		}
		g.emitMessage(m)
	}
	if next := resp.GetCursor(); next != nil {
		g.cursors.Store(convID, next)
	} else {
		g.cursors.Delete(convID)
	}
	g.emit(map[string]any{"type": "range", "threadKey": convID, "hasMoreBefore": resp.GetCursor() != nil})
	return nil
}

func (g *GoogleMessages) emitState(state, err string) {
	g.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (g *GoogleMessages) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && g.listener != nil {
		g.listener.OnEvent(string(data))
	}
}
