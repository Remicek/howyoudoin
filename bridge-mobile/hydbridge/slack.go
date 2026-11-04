package hydbridge

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	stdlog "log"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"github.com/slack-go/slack"

	"go.mau.fi/mautrix-slack/pkg/emoji"
)

// Slack is a Slack workspace logged in as the user (the web client's xoxc token and "d" cookie), running in the
// app over Slack's real-time websocket. Thread keys are channel IDs (DMs, group DMs and channels the user is in),
// contact IDs are user IDs, message IDs are message timestamps. Thread replies show as replies to the thread's
// first message, and replying sends into that thread.
type Slack struct {
	dataDir  string
	listener Listener
	log      zerolog.Logger
	client   *slack.Client

	lock   sync.Mutex
	rtm    *slack.RTM
	cancel context.CancelFunc

	selfID string
	teamID string
	// Channel ID → *slack.Channel
	channels sync.Map
	// User ID → *slack.User, once looked up
	users sync.Map
	// "channel/ts" → our own reaction there (Slack allows several; the app shows one per person)
	ownReactions sync.Map
	// "channel/user" → timer clearing "typing…" (Slack only says when typing starts)
	typingTimers sync.Map
	// Channel ID → *slackHuddle, its latest huddle (see slack_calls.go)
	huddles sync.Map
}

const (
	slackPageSize = 30
	// Channels whose recent messages load on connect
	slackPrefetchChannels = 20
	slackMaxDownloadBytes = 25 << 20
	slackTypingTimeout    = 6 * time.Second
)

// NewSlack takes the login as a JSON object: "token" (xoxc-…, from the web client) and "d" (the d cookie).
func NewSlack(credentialsJSON string, dataDir string, listener Listener) (*Slack, error) {
	var creds struct {
		Token string `json:"token"`
		D     string `json:"d"`
	}
	if err := json.Unmarshal([]byte(credentialsJSON), &creds); err != nil {
		return nil, fmt.Errorf("invalid login: %w", err)
	}
	if !strings.HasPrefix(creds.Token, "xoxc-") || creds.D == "" {
		return nil, errors.New("missing the Slack token or cookie")
	}
	// A browser keeps the d cookie URL-encoded, and OptionCookie encodes it again: decode it first (like
	// mautrix-slack's login does), or Slack sees a different cookie and answers invalid_auth
	if decoded, err := url.QueryUnescape(creds.D); err == nil {
		creds.D = decoded
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "slack").Logger().Level(zerolog.InfoLevel)
	client := slack.New(
		creds.Token,
		slack.OptionCookie("d", creds.D),
		slack.OptionRetry(3),
		// slack-go logs to stderr (logcat on Android) unconditionally, including the websocket reconnect URL,
		// which carries a session token; errors reach us as return values and events anyway
		slack.OptionLog(stdlog.New(io.Discard, "", 0)),
	)
	return &Slack{dataDir: dataDir, listener: listener, log: log, client: client}, nil
}

// Connect loads the workspace's conversations and opens the real-time connection.
func (s *Slack) Connect() error {
	s.lock.Lock()
	if s.cancel != nil {
		s.lock.Unlock()
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(s.log.WithContext(context.Background()))
	s.cancel = cancel
	s.lock.Unlock()

	if err := s.client.FetchVersionData(ctx); err != nil {
		s.log.Warn().Err(err).Msg("Failed to fetch Slack version data")
	}
	boot, err := s.client.ClientUserBootContext(ctx, time.Time{})
	if err != nil {
		s.resetCancel()
		if isSlackAuthError(err) {
			return errors.New("Slack session expired, log in again")
		}
		return fmt.Errorf("failed to load Slack: %w", err)
	}
	s.selfID = boot.Self.ID
	s.teamID = boot.Team.ID
	s.users.Store(boot.Self.ID, &boot.Self)
	s.emit(map[string]any{"type": "self", "id": boot.Self.ID, "name": boot.Self.Name, "workspace": boot.Team.Name})
	s.emitUser(&boot.Self)

	rtm := s.client.NewRTM()
	s.lock.Lock()
	s.rtm = rtm
	s.lock.Unlock()
	go s.consume(ctx, rtm)
	go rtm.ManageConnection()
	go s.syncChannels(ctx, boot)
	return nil
}

func isSlackAuthError(err error) bool {
	msg := err.Error()
	return strings.Contains(msg, "invalid_auth") || strings.Contains(msg, "not_authed") ||
		strings.Contains(msg, "token_revoked") || strings.Contains(msg, "account_inactive")
}

func (s *Slack) resetCancel() {
	s.lock.Lock()
	defer s.lock.Unlock()
	if s.cancel != nil {
		s.cancel()
		s.cancel = nil
	}
}

// Disconnect closes the real-time connection; Connect can be called again.
func (s *Slack) Disconnect() {
	s.lock.Lock()
	rtm := s.rtm
	s.rtm = nil
	s.lock.Unlock()
	if rtm != nil {
		_ = rtm.Disconnect()
	}
	s.resetCancel()
	s.emitState("disconnected", "")
}

func (s *Slack) consume(ctx context.Context, rtm *slack.RTM) {
	for {
		select {
		case <-ctx.Done():
			return
		case evt, ok := <-rtm.IncomingEvents:
			if !ok {
				return
			}
			s.handleEvent(ctx, evt.Data)
		}
	}
}

func (s *Slack) handleEvent(ctx context.Context, raw any) {
	switch evt := raw.(type) {
	case *slack.HelloEvent:
		s.emitState("connected", "")
	case *slack.DisconnectedEvent:
		if !evt.Intentional {
			s.emitState("reconnecting", errString(evt.Cause))
		}
	case *slack.InvalidAuthEvent:
		s.emitState("error", "Slack session expired, log in again")
	case *slack.MessageEvent:
		s.handleMessage(ctx, evt)
	case *slack.SHRoomJoinEvent:
		s.handleHuddleRoom(&evt.Room, evt.Huddle)
	case *slack.SHRoomUpdateEvent:
		s.handleHuddleRoom(&evt.Room, evt.Huddle)
	case *slack.SHRoomLeaveEvent:
		s.handleHuddleRoom(&evt.Room, evt.Huddle)
	case *slack.ReactionAddedEvent:
		s.emitReaction(evt.Item.Channel, evt.Item.Timestamp, evt.User, evt.Reaction, true)
	case *slack.ReactionRemovedEvent:
		s.emitReaction(evt.Item.Channel, evt.Item.Timestamp, evt.User, evt.Reaction, false)
	case *slack.UserTypingEvent:
		s.emitTyping(evt.Channel, evt.User)
	case *slack.ChannelMarkedEvent:
		s.emitOwnRead(evt.Channel, evt.Timestamp)
	case *slack.IMMarkedEvent:
		s.emitOwnRead(evt.Channel, evt.Timestamp)
	case *slack.GroupMarkedEvent:
		s.emitOwnRead(evt.Channel, evt.Timestamp)
	case *slack.IMCreatedEvent:
		go s.loadChannel(ctx, evt.Channel.ID)
	case *slack.ChannelJoinedEvent:
		ch := evt.Channel
		s.emitChannel(ctx, &ch, ch.LastRead)
	case *slack.GroupJoinedEvent:
		ch := evt.Channel
		s.emitChannel(ctx, &ch, ch.LastRead)
	}
}

func (s *Slack) handleMessage(ctx context.Context, evt *slack.MessageEvent) {
	switch evt.SubType {
	case slack.MsgSubTypeMessageChanged:
		if evt.SubMessage != nil && evt.SubMessage.SubType != "huddle_thread" {
			s.emit(map[string]any{"type": "edit", "threadKey": evt.Channel, "messageId": evt.SubMessage.Timestamp,
				"text": s.formatText(ctx, evt.SubMessage.Text)})
		}
		return
	case slack.MsgSubTypeMessageDeleted:
		s.emit(map[string]any{"type": "unsend", "threadKey": evt.Channel, "messageId": evt.DeletedTimestamp})
		return
	case slack.MsgSubTypeMessageReplied, slack.MsgSubTypeChannelJoin, slack.MsgSubTypeChannelLeave,
		slack.MsgSubTypeGroupJoin, slack.MsgSubTypeGroupLeave:
		return
	}
	if _, known := s.channels.Load(evt.Channel); !known {
		go s.loadChannel(ctx, evt.Channel)
	}
	msg := slack.Message(*evt)
	s.emitMessage(ctx, evt.Channel, &msg)
}

// --- conversations ---

func (s *Slack) syncChannels(ctx context.Context, boot *slack.ClientUserBootResponse) {
	lastRead := map[string]string{}
	latest := map[string]string{}
	counts, err := s.client.ClientCountsContext(ctx, &slack.ClientCountsParams{})
	if err != nil {
		s.log.Warn().Err(err).Msg("Failed to load Slack read state")
	} else {
		for _, list := range [][]slack.ClientCountsChannel{counts.Channels, counts.IMs, counts.MpIMs} {
			for _, c := range list {
				lastRead[c.ID], latest[c.ID] = c.LastRead, c.Latest
			}
		}
	}
	var channels []*slack.Channel
	for _, list := range [][]slack.BootChannel{boot.Channels, boot.IMs} {
		for i := range list {
			ch := &list[i].Channel
			if ch.IsArchived || ch.IsUserDeleted {
				continue
			}
			if ch.Latest == nil && list[i].Latest != "" {
				ch.Latest = &slack.Message{Msg: slack.Msg{Timestamp: list[i].Latest}}
			}
			if ts := latest[ch.ID]; ts != "" && (ch.Latest == nil || ch.Latest.Timestamp < ts) {
				ch.Latest = &slack.Message{Msg: slack.Msg{Timestamp: ts}}
			}
			channels = append(channels, ch)
		}
	}
	// Most recent first, so the prefetch below covers the chats likely to be opened
	slices.SortFunc(channels, func(a, b *slack.Channel) int {
		return cmp.Compare(latestTS(b), latestTS(a))
	})
	s.lookUpUsers(ctx, imUsers(channels))
	for _, ch := range channels {
		s.emitChannel(ctx, ch, lastRead[ch.ID])
	}
	for i, ch := range channels {
		if i >= slackPrefetchChannels || ctx.Err() != nil {
			break
		}
		if latestTS(ch) == "" {
			continue
		}
		if err := s.fetchHistory(ctx, ch.ID, ""); err != nil {
			s.log.Warn().Err(err).Msg("Failed to load recent Slack messages")
		}
	}
}

func latestTS(ch *slack.Channel) string {
	if ch.Latest == nil {
		return ""
	}
	return ch.Latest.Timestamp
}

func imUsers(channels []*slack.Channel) []string {
	var ids []string
	for _, ch := range channels {
		if ch.IsIM && ch.User != "" {
			ids = append(ids, ch.User)
		}
	}
	return ids
}

func (s *Slack) loadChannel(ctx context.Context, channelID string) {
	ch, err := s.client.GetConversationInfoContext(ctx, &slack.GetConversationInfoInput{ChannelID: channelID})
	if err != nil {
		s.log.Warn().Err(err).Msg("Failed to load a Slack conversation")
		return
	}
	if ch.IsIM {
		s.lookUpUsers(ctx, []string{ch.User})
	}
	s.emitChannel(ctx, ch, ch.LastRead)
}

func (s *Slack) emitChannel(ctx context.Context, ch *slack.Channel, lastRead string) {
	s.channels.Store(ch.ID, ch)
	name := "#" + ch.Name
	isGroup := !ch.IsIM
	switch {
	case ch.IsIM:
		name = ""
		if user := s.user(ch.User); user != nil {
			name = slackUserName(user)
			if avatar := user.Profile.Image192; avatar != "" {
				s.emit(map[string]any{"type": "avatar", "threadKey": ch.ID, "url": avatar})
			}
		}
		if ch.User != "" {
			s.emit(map[string]any{"type": "participant", "threadKey": ch.ID, "contactId": ch.User})
		}
	case ch.IsMpIM:
		name = s.groupDMName(ctx, ch)
	}
	lastActivity := slackTSMillis(latestTS(ch))
	lastReadMs := lastActivity
	if lastRead != "" {
		lastReadMs = slackTSMillis(lastRead)
	}
	s.emit(map[string]any{
		"type": "thread", "key": ch.ID, "name": name, "isGroup": isGroup, "isEncrypted": false,
		"lastActivityMs": lastActivity, "lastReadMs": lastReadMs,
	})
}

// groupDMName names a group DM after its people (Slack's own name is "mpdm-a--b--c-1").
func (s *Slack) groupDMName(ctx context.Context, ch *slack.Channel) string {
	members := ch.Members
	if len(members) == 0 {
		if resp, _, err := s.client.GetUsersInConversationContext(ctx, &slack.GetUsersInConversationParameters{ChannelID: ch.ID}); err == nil {
			members = resp
		}
	}
	s.lookUpUsers(ctx, members)
	var names []string
	for _, id := range members {
		if id == s.selfID {
			continue
		}
		s.emit(map[string]any{"type": "participant", "threadKey": ch.ID, "contactId": id})
		if user := s.user(id); user != nil {
			names = append(names, firstNonEmpty(user.Profile.DisplayName, user.RealName, user.Name))
		}
	}
	if len(names) == 0 {
		return ch.Purpose.Value
	}
	return strings.Join(names, ", ")
}

// --- people ---

func (s *Slack) user(id string) *slack.User {
	if u, ok := s.users.Load(id); ok {
		return u.(*slack.User)
	}
	return nil
}

func slackUserName(u *slack.User) string {
	return firstNonEmpty(u.RealName, u.Profile.RealName, u.Profile.DisplayName, u.Name)
}

// lookUpUsers loads people not seen yet and sends them to the app.
func (s *Slack) lookUpUsers(ctx context.Context, ids []string) {
	var missing []string
	for _, id := range ids {
		if _, ok := s.users.Load(id); !ok && id != "" && !slices.Contains(missing, id) {
			missing = append(missing, id)
		}
	}
	for start := 0; start < len(missing); start += 100 {
		batch := missing[start:min(start+100, len(missing))]
		users, err := s.client.GetUsersInfoContext(ctx, batch...)
		if err != nil {
			s.log.Warn().Err(err).Msg("Failed to look up Slack users")
			continue
		}
		for i := range *users {
			u := &(*users)[i]
			s.users.Store(u.ID, u)
			s.emitUser(u)
		}
	}
}

func (s *Slack) emitUser(u *slack.User) {
	name := slackUserName(u)
	s.emit(map[string]any{"type": "contact", "id": u.ID, "name": name,
		"firstName": firstNonEmpty(u.Profile.FirstName, u.Profile.DisplayName, strings.SplitN(name, " ", 2)[0]),
		"avatarUrl": u.Profile.Image192})
}

// --- messages ---

func slackTSMillis(ts string) int64 {
	f, err := strconv.ParseFloat(ts, 64)
	if err != nil {
		return 0
	}
	return int64(f * 1000)
}

func (s *Slack) emitMessage(ctx context.Context, channelID string, msg *slack.Message) {
	if msg.Timestamp == "" {
		return
	}
	if msg.SubType == "huddle_thread" {
		s.emitHuddle(channelID, msg)
		return
	}
	sender := firstNonEmpty(msg.User, msg.BotID)
	if sender != "" && msg.User != "" {
		if _, known := s.users.Load(sender); !known {
			s.lookUpUsers(ctx, []string{sender})
		}
	} else if msg.BotID != "" {
		botName := msg.Username
		if msg.BotProfile != nil {
			botName = firstNonEmpty(botName, msg.BotProfile.Name)
		}
		s.emit(map[string]any{"type": "contact", "id": msg.BotID, "name": firstNonEmpty(botName, "Bot")})
	}
	text := s.formatText(ctx, msg.Text)
	kind := "text"
	var file *slack.File
	if len(msg.Files) > 0 {
		file = &msg.Files[0]
		kind, _ = mediaKind(file.Mimetype)
		if kind == "file" && text == "" {
			text = file.Name
		}
	}
	if text == "" && file == nil {
		if len(msg.Attachments) > 0 {
			text = firstNonEmpty(msg.Attachments[0].Fallback, msg.Attachments[0].Text, msg.Attachments[0].Title)
		}
		if text == "" {
			kind = "other"
		}
	}
	otid := msg.Timestamp
	if file != nil && sender == s.selfID {
		// Uploads return the file, not the message it ends up in
		otid = file.ID
	}
	event := map[string]any{
		"type": "message", "threadKey": channelID, "id": msg.Timestamp, "otid": otid, "senderId": sender,
		"text": text, "kind": kind, "timestampMs": slackTSMillis(msg.Timestamp),
	}
	if msg.ThreadTimestamp != "" && msg.ThreadTimestamp != msg.Timestamp {
		event["replyToId"] = msg.ThreadTimestamp
	}
	if msg.Edited != nil {
		event["isEdited"] = true
	}
	s.emit(event)
	for _, reaction := range msg.Reactions {
		for _, user := range reaction.Users {
			s.emitReaction(channelID, msg.Timestamp, user, reaction.Name, true)
		}
	}
	if file != nil && file.Size <= slackMaxDownloadBytes && (file.URLPrivateDownload != "" || file.URLPrivate != "") {
		go s.downloadFile(channelID, msg.Timestamp, kind, file)
	}
}

var slackEntity = regexp.MustCompile(`<([^>]+)>`)

// formatText turns Slack's markup into plain text: <@U1> → @Name, <#C1|general> → #general, <url|label> → label,
// :emoji: → the emoji.
func (s *Slack) formatText(ctx context.Context, text string) string {
	if text == "" {
		return ""
	}
	text = slackEntity.ReplaceAllStringFunc(text, func(match string) string {
		inner := match[1 : len(match)-1]
		target, label, _ := strings.Cut(inner, "|")
		switch {
		case strings.HasPrefix(target, "@"):
			id := target[1:]
			if user := s.user(id); user != nil {
				return "@" + firstNonEmpty(user.Profile.DisplayName, slackUserName(user))
			}
			if label != "" {
				return "@" + label
			}
			return "@" + id
		case strings.HasPrefix(target, "#"):
			if label == "" {
				if ch, ok := s.channels.Load(target[1:]); ok {
					label = ch.(*slack.Channel).Name
				}
			}
			return "#" + firstNonEmpty(label, target[1:])
		case strings.HasPrefix(target, "!"):
			name, _, _ := strings.Cut(target[1:], "^")
			return "@" + firstNonEmpty(label, name)
		case label != "" && label != strings.TrimPrefix(strings.TrimPrefix(target, "mailto:"), "tel:"):
			return label + " (" + target + ")"
		}
		return strings.TrimPrefix(target, "mailto:")
	})
	return emoji.ReplaceShortcodesWithUnicode(html.UnescapeString(text))
}

func (s *Slack) emitReaction(channelID, ts, user, name string, added bool) {
	if channelID == "" || ts == "" || user == "" {
		return
	}
	value := slackEmoji(name)
	key := channelID + "/" + ts
	if user == s.selfID {
		if added {
			s.ownReactions.Store(key, name)
		} else {
			s.ownReactions.Delete(key)
		}
	}
	if !added {
		value = ""
	}
	s.emit(map[string]any{"type": "reaction", "threadKey": channelID, "targetId": ts, "senderId": user, "emoji": value})
}

// slackEmoji is the emoji for a reaction name ("thumbsup", "+1::skin-tone-2"); custom workspace emoji keep
// their name.
func slackEmoji(name string) string {
	if unicode := emoji.GetUnicode(name); unicode != "" {
		return unicode
	}
	base, _, _ := strings.Cut(name, "::")
	if unicode := emoji.GetUnicode(base); unicode != "" {
		return unicode
	}
	return ":" + name + ":"
}

func (s *Slack) emitTyping(channelID, user string) {
	if user == "" || user == s.selfID {
		return
	}
	s.emit(map[string]any{"type": "typing", "threadKey": channelID, "senderId": user, "isTyping": true})
	key := channelID + "/" + user
	timer := time.AfterFunc(slackTypingTimeout, func() {
		s.typingTimers.Delete(key)
		s.emit(map[string]any{"type": "typing", "threadKey": channelID, "senderId": user, "isTyping": false})
	})
	if previous, loaded := s.typingTimers.Swap(key, timer); loaded {
		previous.(*time.Timer).Stop()
	}
}

func (s *Slack) emitOwnRead(channelID, ts string) {
	if s.selfID != "" && ts != "" {
		s.emit(map[string]any{"type": "read", "threadKey": channelID, "contactId": s.selfID, "watermarkMs": slackTSMillis(ts)})
	}
}

func (s *Slack) mediaPath(name string) (string, error) {
	dir := filepath.Join(s.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return filepath.Join(dir, safeMediaName(strings.NewReplacer("/", "_", ":", "_").Replace(name))), nil
}

// downloadFile fetches an attachment (Slack's file links need the login) and points the app to the local copy.
func (s *Slack) downloadFile(channelID, ts, kind string, file *slack.File) {
	path, err := s.mediaPath(file.ID)
	if err != nil {
		return
	}
	if _, err = os.Stat(path); err != nil {
		tmp := partPath(path)
		out, err := os.Create(tmp)
		if err != nil {
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		// Cap the stream: file.Size is server-reported, so don't trust it to bound the actual download.
		err = s.client.GetFileContext(ctx, firstNonEmpty(file.URLPrivateDownload, file.URLPrivate), &limitedWriter{w: out, limit: slackMaxDownloadBytes})
		cancel()
		if closeErr := out.Close(); err == nil {
			err = closeErr
		}
		if err == nil {
			err = os.Rename(tmp, path)
		}
		if err != nil {
			_ = os.Remove(tmp)
			s.log.Warn().Err(err).Msg("Failed to download a Slack file")
			return
		}
	}
	event := map[string]any{
		"type": "attachment", "threadKey": channelID, "messageId": ts, "kind": kind, "url": "file://" + path,
		"previewUrl": "file://" + path, "mimeType": file.Mimetype, "fileName": file.Name,
		"width": file.OriginalW, "height": file.OriginalH,
	}
	if kind == "audio" || kind == "video" {
		if ms := file.DurationMS; ms > 0 {
			event["durationMs"] = ms
		}
	}
	s.emit(event)
}

func (s *Slack) emitState(state, err string) {
	s.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (s *Slack) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && s.listener != nil {
		s.listener.OnEvent(string(data))
	}
}

// SearchPeople finds members of the workspace by name.
func (s *Slack) SearchPeople(query string) (string, error) {
	client := s.client
	if client == nil {
		return "", errors.New("Slack isn't connected")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	resp, err := client.SearchUsersCacheContext(ctx, s.teamID, query)
	if err != nil {
		return "", err
	}
	var people []foundPerson
	for _, u := range resp.Results {
		if u == nil || u.Deleted || u.ID == s.selfID {
			continue
		}
		s.users.Store(u.ID, u)
		s.emitUser(u)
		people = append(people, foundPerson{ID: u.ID, Name: firstNonEmpty(u.Profile.DisplayName, u.RealName, u.Name),
			Handle: "@" + u.Name, AvatarURL: u.Profile.Image72})
	}
	return peopleJSON(people)
}

// StartChat opens the direct message channel with a member; its ID is the chat's thread key.
func (s *Slack) StartChat(personID string) (string, error) {
	client := s.client
	if client == nil {
		return "", errors.New("Slack isn't connected")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	channel, _, _, err := client.OpenConversationContext(ctx, &slack.OpenConversationParameters{
		Users: []string{personID}, ReturnIM: true,
	})
	if err != nil {
		return "", err
	}
	return channel.ID, nil
}
