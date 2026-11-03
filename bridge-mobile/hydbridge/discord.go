package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/bwmarrin/discordgo"
	"github.com/imroc/req/v3"
	"github.com/rs/zerolog"
)

// Discord is the user's Discord direct messages and group DMs (not server channels), logged in with the web
// client's token and kept up to date over Discord's gateway (Beeper's discordgo fork, as mautrix-discord uses).
// Thread keys are channel IDs, contact IDs user IDs, message IDs message IDs.
//
// Discord doesn't allow third-party clients on user accounts and has banned accounts for it; the app warns
// before connecting.
type Discord struct {
	dataDir  string
	listener Listener
	log      zerolog.Logger
	token    string

	lock    sync.Mutex
	session *discordgo.Session
	selfID  string
	// The CAPTCHA Discord last asked for, and the user's answer to it for the request that's tried again
	captcha       *discordCaptcha
	captchaAnswer string
	// Discord refuses to send for an account without a verified email or phone; typing isn't tried meanwhile
	needsVerification atomic.Bool

	// Channel ID → *discordgo.Channel (DMs and group DMs only)
	channels sync.Map
	// User ID → true once its contact event was sent
	contacts sync.Map
	// User ID → *discordgo.User: friends and the people in DMs, searched for new chats
	users sync.Map
	// "channel/message" → our reaction (its API name) there
	ownReactions sync.Map
	typingTimers sync.Map
}

const (
	discordPageSize       = 50
	discordPrefetch       = 20
	discordTypingTimeout  = 10 * time.Second
	discordMaxDownload    = 25 << 20
	discordEpochMillis    = 1420070400000
	discordAuthFailedText = "Discord session expired, log in again"
)

// NewDiscord takes the login as a JSON object with the web client's "token".
func NewDiscord(credentialsJSON string, dataDir string, listener Listener) (*Discord, error) {
	var creds struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal([]byte(credentialsJSON), &creds); err != nil {
		return nil, fmt.Errorf("invalid login: %w", err)
	}
	token := strings.Trim(strings.TrimSpace(creds.Token), `"`)
	if token == "" || strings.HasPrefix(token, "Bot ") {
		return nil, errors.New("missing the Discord token")
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "discord").Logger().Level(zerolog.InfoLevel)
	return &Discord{dataDir: dataDir, listener: listener, log: log, token: token}, nil
}

// Connect checks the token, then opens the gateway; the inbox arrives with its Ready event.
func (d *Discord) Connect() error {
	d.lock.Lock()
	if d.session != nil {
		d.lock.Unlock()
		return errors.New("already connected")
	}
	d.lock.Unlock()

	session, err := discordgo.New(d.token)
	if err != nil {
		return err
	}
	// Requests look like Discord's own web client (a Chrome TLS fingerprint), as mautrix-discord's do
	session.Client.Transport = req.C().ImpersonateChrome().SetProxy(nil).Transport
	session.LogLevel = discordgo.LogWarning
	session.Logger = func(level, caller int, format string, a ...any) {
		d.log.Debug().Msgf(strings.TrimSpace(format), a...) // zerolog-allow-msgf
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	if err := session.LoadMainPage(ctx); err != nil {
		d.log.Warn().Err(err).Msg("Failed to load Discord's main page")
	}
	self, err := session.User("@me")
	if err != nil {
		var restErr *discordgo.RESTError
		if errors.As(err, &restErr) && restErr.Response != nil && restErr.Response.StatusCode == http.StatusUnauthorized {
			return errors.New(discordAuthFailedText)
		}
		return fmt.Errorf("failed to load Discord: %w", err)
	}
	d.selfID = self.ID
	d.emit(map[string]any{"type": "self", "id": self.ID, "name": self.DisplayName()})
	d.emitUser(self)

	session.EventHandler = func(evt any) { go d.handleEvent(evt) }
	d.lock.Lock()
	d.session = session
	d.lock.Unlock()
	if err := session.Open(); err != nil {
		d.lock.Lock()
		d.session = nil
		d.lock.Unlock()
		return fmt.Errorf("failed to connect to Discord: %w", err)
	}
	return nil
}

// Disconnect closes the gateway; Connect can be called again.
func (d *Discord) Disconnect() {
	d.lock.Lock()
	session := d.session
	d.session = nil
	d.lock.Unlock()
	if session != nil {
		_ = session.Close()
	}
	d.emitState("disconnected", "")
}

func (d *Discord) current() (*discordgo.Session, error) {
	d.lock.Lock()
	defer d.lock.Unlock()
	if d.session == nil {
		return nil, errors.New("not connected")
	}
	return d.session, nil
}

// discordMillis is when a snowflake ID (message, channel) was made.
func discordMillis(id string) int64 {
	n, err := strconv.ParseUint(id, 10, 64)
	if err != nil || n == 0 {
		return 0
	}
	return int64(n>>22) + discordEpochMillis
}

func (d *Discord) handleEvent(raw any) {
	defer func() {
		if err := recover(); err != nil {
			d.log.Error().Any("panic", err).Msg("Panic in a Discord event handler")
		}
	}()
	switch evt := raw.(type) {
	case *discordgo.Ready:
		d.handleReady(evt)
	case *discordgo.Resumed:
		d.emitState("connected", "")
	case *discordgo.Disconnect:
		d.emitState("reconnecting", "")
	case *discordgo.InvalidAuth:
		d.emitState("error", discordAuthFailedText)
	case *discordgo.ChannelCreate:
		d.emitChannel(evt.Channel, "")
	case *discordgo.ChannelUpdate:
		d.emitChannel(evt.Channel, "")
	case *discordgo.MessageCreate:
		if d.isPrivate(evt.ChannelID) {
			d.emitMessage(evt.Message)
			d.emit(map[string]any{"type": "thread", "key": evt.ChannelID, "lastActivityMs": discordMillis(evt.ID)})
		} else if evt.GuildID == "" {
			go d.loadChannel(evt.ChannelID)
		}
	case *discordgo.MessageUpdate:
		if d.isPrivate(evt.ChannelID) && evt.Content != "" {
			d.emit(map[string]any{"type": "edit", "threadKey": evt.ChannelID, "messageId": evt.ID, "text": d.formatText(evt.Message)})
		}
	case *discordgo.MessageDelete:
		if d.isPrivate(evt.ChannelID) {
			d.emit(map[string]any{"type": "unsend", "threadKey": evt.ChannelID, "messageId": evt.ID})
		}
	case *discordgo.MessageReactionAdd:
		if d.isPrivate(evt.ChannelID) {
			d.emitReaction(evt.ChannelID, evt.MessageID, evt.UserID, &evt.Emoji, true)
		}
	case *discordgo.MessageReactionRemove:
		if d.isPrivate(evt.ChannelID) {
			d.emitReaction(evt.ChannelID, evt.MessageID, evt.UserID, &evt.Emoji, false)
		}
	case *discordgo.TypingStart:
		if d.isPrivate(evt.ChannelID) {
			d.emitTyping(evt.ChannelID, evt.UserID)
		}
	case *discordgo.MessageAck:
		if d.isPrivate(evt.ChannelID) {
			d.emit(map[string]any{"type": "read", "threadKey": evt.ChannelID, "contactId": d.selfID,
				"watermarkMs": discordMillis(evt.MessageID)})
		}
	}
}

func (d *Discord) isPrivate(channelID string) bool {
	_, ok := d.channels.Load(channelID)
	return ok
}

// --- channels ---

func (d *Discord) handleReady(r *discordgo.Ready) {
	d.emitState("connected", "")
	for _, u := range r.Users {
		d.emitUser(u)
	}
	lastRead := map[string]string{}
	if r.ReadState != nil {
		for _, entry := range r.ReadState.Entries {
			lastRead[entry.ID] = string(entry.LastMessageID)
		}
	}
	usersByID := map[string]*discordgo.User{}
	for _, u := range r.Users {
		usersByID[u.ID] = u
	}
	channels := slices.Clone(r.PrivateChannels)
	slices.SortFunc(channels, func(a, b *discordgo.Channel) int {
		return int(discordMillis(b.LastMessageID) - discordMillis(a.LastMessageID))
	})
	for _, ch := range channels {
		if len(ch.Recipients) == 0 {
			for _, id := range ch.RecipientIDs {
				if u := usersByID[id]; u != nil {
					ch.Recipients = append(ch.Recipients, u)
				}
			}
		}
		d.emitChannel(ch, lastRead[ch.ID])
	}
	go d.prefetch(channels)
}

func (d *Discord) prefetch(channels []*discordgo.Channel) {
	for i, ch := range channels {
		if i >= discordPrefetch {
			return
		}
		if ch.LastMessageID == "" {
			continue
		}
		if err := d.RequestOlder(ch.ID, ""); err != nil {
			d.log.Warn().Err(err).Msg("Failed to load recent Discord messages")
		}
	}
}

func (d *Discord) loadChannel(channelID string) {
	session, err := d.current()
	if err != nil {
		return
	}
	ch, err := session.Channel(channelID)
	if err != nil {
		d.log.Warn().Err(err).Msg("Failed to load a Discord channel")
		return
	}
	d.emitChannel(ch, "")
	if err := d.RequestOlder(ch.ID, ""); err != nil {
		d.log.Warn().Err(err).Msg("Failed to load Discord messages")
	}
}

func (d *Discord) emitChannel(ch *discordgo.Channel, lastReadID string) {
	if ch == nil || (ch.Type != discordgo.ChannelTypeDM && ch.Type != discordgo.ChannelTypeGroupDM) {
		return
	}
	d.channels.Store(ch.ID, ch)
	var names []string
	for _, u := range ch.Recipients {
		d.emitUser(u)
		if u.ID == d.selfID {
			continue
		}
		names = append(names, u.DisplayName())
		d.emit(map[string]any{"type": "participant", "threadKey": ch.ID, "contactId": u.ID})
	}
	isGroup := ch.Type == discordgo.ChannelTypeGroupDM
	name := ch.Name
	if name == "" {
		name = strings.Join(names, ", ")
	}
	switch {
	case isGroup && ch.Icon != "":
		d.emit(map[string]any{"type": "avatar", "threadKey": ch.ID,
			"url": discordgo.EndpointGroupIcon(ch.ID, ch.Icon)})
	case !isGroup && len(ch.Recipients) > 0:
		if avatar := ch.Recipients[0].AvatarURL("256"); avatar != "" {
			d.emit(map[string]any{"type": "avatar", "threadKey": ch.ID, "url": avatar})
		}
	}
	lastActivity := discordMillis(ch.LastMessageID)
	if lastActivity == 0 {
		lastActivity = discordMillis(ch.ID)
	}
	event := map[string]any{
		"type": "thread", "key": ch.ID, "name": name, "isGroup": isGroup, "isEncrypted": false,
		"lastActivityMs": lastActivity,
	}
	if lastReadID != "" {
		event["lastReadMs"] = discordMillis(lastReadID)
	}
	d.emit(event)
}

func (d *Discord) emitUser(u *discordgo.User) {
	if u == nil || u.ID == "" {
		return
	}
	d.users.Store(u.ID, u)
	if _, sent := d.contacts.LoadOrStore(u.ID, true); sent {
		return
	}
	d.emit(map[string]any{"type": "contact", "id": u.ID, "name": u.DisplayName(),
		"firstName": strings.SplitN(u.DisplayName(), " ", 2)[0], "avatarUrl": u.AvatarURL("256")})
}

// --- messages ---

var discordCustomEmoji = regexp.MustCompile(`<a?:(\w+):\d+>`)

// formatText turns Discord's mentions (<@123>, <#123>, <:name:id>) into readable text.
func (d *Discord) formatText(m *discordgo.Message) string {
	text := m.Content
	for _, u := range m.Mentions {
		mention := "@" + u.DisplayName()
		text = strings.ReplaceAll(text, "<@"+u.ID+">", mention)
		text = strings.ReplaceAll(text, "<@!"+u.ID+">", mention)
	}
	return discordCustomEmoji.ReplaceAllString(text, ":$1:")
}

func (d *Discord) emitMessage(m *discordgo.Message) {
	if m == nil || m.ID == "" {
		return
	}
	switch m.Type {
	case discordgo.MessageTypeDefault, discordgo.MessageTypeReply:
	default:
		// Calls, pins, people joining a group: not chat messages
		return
	}
	if m.Author != nil {
		d.emitUser(m.Author)
	}
	sender := ""
	if m.Author != nil {
		sender = m.Author.ID
	}
	text := d.formatText(m)
	kind := "text"
	var attachment *discordgo.MessageAttachment
	if len(m.Attachments) > 0 {
		attachment = m.Attachments[0]
		kind, _ = mediaKind(attachment.ContentType)
		if kind == "file" && text == "" {
			text = attachment.Filename
		}
	} else if len(m.StickerItems) > 0 && text == "" {
		text = "[" + m.StickerItems[0].Name + "]"
	} else if len(m.Embeds) > 0 && text == "" {
		text = firstNonEmpty(m.Embeds[0].URL, m.Embeds[0].Title, m.Embeds[0].Description)
	}
	if text == "" && attachment == nil {
		kind = "other"
	}
	event := map[string]any{
		"type": "message", "threadKey": m.ChannelID, "id": m.ID, "otid": firstNonEmpty(string(m.Nonce), m.ID),
		"senderId": sender, "text": text, "kind": kind, "timestampMs": m.Timestamp.UnixMilli(),
	}
	if m.MessageReference != nil && m.MessageReference.MessageID != "" {
		event["replyToId"] = m.MessageReference.MessageID
	}
	if m.EditedTimestamp != nil {
		event["isEdited"] = true
	}
	d.emit(event)
	for _, r := range m.Reactions {
		if r.Me {
			d.emitReaction(m.ChannelID, m.ID, d.selfID, r.Emoji, true)
		}
	}
	if attachment != nil && attachment.Size <= discordMaxDownload && attachment.URL != "" {
		go d.downloadAttachment(m.ChannelID, m.ID, kind, attachment)
	}
}

func discordEmojiText(e *discordgo.Emoji) string {
	if e == nil {
		return ""
	}
	if e.ID != "" {
		return ":" + e.Name + ":"
	}
	return e.Name
}

func (d *Discord) emitReaction(channelID, messageID, userID string, e *discordgo.Emoji, added bool) {
	if userID == d.selfID {
		key := channelID + "/" + messageID
		if added {
			d.ownReactions.Store(key, e.APIName())
		} else {
			d.ownReactions.Delete(key)
		}
	}
	value := discordEmojiText(e)
	if !added {
		value = ""
	}
	d.emit(map[string]any{"type": "reaction", "threadKey": channelID, "targetId": messageID, "senderId": userID,
		"emoji": value})
}

func (d *Discord) emitTyping(channelID, userID string) {
	if userID == "" || userID == d.selfID {
		return
	}
	d.emit(map[string]any{"type": "typing", "threadKey": channelID, "senderId": userID, "isTyping": true})
	key := channelID + "/" + userID
	timer := time.AfterFunc(discordTypingTimeout, func() {
		d.typingTimers.Delete(key)
		d.emit(map[string]any{"type": "typing", "threadKey": channelID, "senderId": userID, "isTyping": false})
	})
	if previous, loaded := d.typingTimers.Swap(key, timer); loaded {
		previous.(*time.Timer).Stop()
	}
}

// downloadAttachment saves an attachment and points the app to the local copy (Discord's links expire).
func (d *Discord) downloadAttachment(channelID, messageID, kind string, a *discordgo.MessageAttachment) {
	session, err := d.current()
	if err != nil {
		return
	}
	dir := filepath.Join(d.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return
	}
	// Sanitize the server-supplied attachment ID so it can't escape the media dir (path traversal).
	path := filepath.Join(dir, safeMediaName(strings.NewReplacer(":", "_", "/", "_", "|", "_").Replace(a.ID)))
	if _, err := os.Stat(path); err != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, a.URL, nil)
		if err != nil {
			return
		}
		resp, err := session.Client.Do(request)
		if err != nil {
			return
		}
		data, err := io.ReadAll(io.LimitReader(resp.Body, discordMaxDownload+1))
		_ = resp.Body.Close()
		if err != nil || resp.StatusCode != http.StatusOK || len(data) > discordMaxDownload {
			d.log.Warn().Err(err).Int("status", resp.StatusCode).Msg("Failed to download a Discord attachment")
			return
		}
		if err := writeFileAtomic(path, data); err != nil {
			return
		}
	}
	d.emit(map[string]any{
		"type": "attachment", "threadKey": channelID, "messageId": messageID, "kind": kind, "url": "file://" + path,
		"previewUrl": "file://" + path, "mimeType": a.ContentType, "fileName": a.Filename,
		"width": a.Width, "height": a.Height,
	})
}

// --- sending ---

// SendText sends a message and returns its ID (the echo's otid).
func (d *Discord) SendText(channelID, text string) (string, error) {
	return d.send(channelID, &discordgo.MessageSend{Content: text})
}

// SendReply sends a message replying to another.
func (d *Discord) SendReply(channelID, text, replyToID string) (string, error) {
	return d.send(channelID, &discordgo.MessageSend{
		Content:   text,
		Reference: &discordgo.MessageReference{ChannelID: channelID, MessageID: replyToID},
	})
}

func (d *Discord) send(channelID string, data *discordgo.MessageSend) (string, error) {
	session, err := d.current()
	if err != nil {
		return "", err
	}
	// The nonce comes back on the echo, so the app matches it to what it showed while sending
	data.Nonce = strconv.FormatInt((time.Now().UnixMilli()-discordEpochMillis)<<22, 10)
	msg, err := session.ChannelMessageSendComplex(channelID, data, d.captchaOptions()...)
	d.noteVerification(err == nil, err)
	if err != nil {
		return "", d.checkCaptcha(err)
	}
	return firstNonEmpty(data.Nonce, msg.ID), nil
}

// EditMessage changes the text of one of our messages.
func (d *Discord) EditMessage(channelID, messageID, text string) error {
	session, err := d.current()
	if err != nil {
		return err
	}
	_, err = session.ChannelMessageEdit(channelID, messageID, text)
	return err
}

// Unsend deletes one of our messages.
func (d *Discord) Unsend(channelID, messageID string) error {
	session, err := d.current()
	if err != nil {
		return err
	}
	return session.ChannelMessageDelete(channelID, messageID)
}

// SendReaction sets our reaction on a message (replacing the one we had); an empty emoji removes it.
func (d *Discord) SendReaction(channelID, messageID, emoji string) error {
	session, err := d.current()
	if err != nil {
		return err
	}
	key := channelID + "/" + messageID
	if previous, ok := d.ownReactions.Load(key); ok {
		if err := session.MessageReactionRemove(channelID, messageID, previous.(string), "@me"); err != nil {
			return err
		}
		d.ownReactions.Delete(key)
	}
	if emoji == "" {
		return nil
	}
	if err := session.MessageReactionAdd(channelID, messageID, emoji); err != nil {
		return err
	}
	d.ownReactions.Store(key, emoji)
	return nil
}

// MarkRead marks a channel read up to a message.
func (d *Discord) MarkRead(channelID, messageID string) error {
	if messageID == "" {
		return nil
	}
	session, err := d.current()
	if err != nil {
		return err
	}
	_, err = session.ChannelMessageAckNoToken(channelID, messageID)
	return err
}

// SetTyping shows "typing…" for a few seconds.
func (d *Discord) SetTyping(channelID string, isTyping bool) error {
	if !isTyping || d.needsVerification.Load() {
		return nil
	}
	session, err := d.current()
	if err != nil {
		return err
	}
	err = session.ChannelTyping(channelID)
	d.noteVerification(false, err)
	return err
}

// SendMediaFile uploads a file into a channel and returns the nonce of its message.
func (d *Discord) SendMediaFile(channelID, path, mimeType, fileName, caption string) (string, error) {
	defer os.Remove(path)
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	if fileName == "" {
		fileName = filepath.Base(path)
	}
	return d.send(channelID, &discordgo.MessageSend{
		Content: caption,
		Files:   []*discordgo.File{{Name: fileName, ContentType: mimeType, Reader: file}},
	})
}

// RequestOlder loads messages before one (the newest when beforeMessageID is empty); they arrive as message
// events, then a range event.
func (d *Discord) RequestOlder(channelID, beforeMessageID string) error {
	session, err := d.current()
	if err != nil {
		return err
	}
	messages, err := session.ChannelMessages(channelID, discordPageSize, beforeMessageID, "", "")
	if err != nil {
		return err
	}
	for _, m := range messages {
		if m.ChannelID == "" {
			m.ChannelID = channelID
		}
		d.emitMessage(m)
	}
	d.emit(map[string]any{"type": "range", "threadKey": channelID, "hasMoreBefore": len(messages) == discordPageSize})
	return nil
}

func (d *Discord) emitState(state, err string) {
	d.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (d *Discord) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && d.listener != nil {
		d.listener.OnEvent(string(data))
	}
}

// --- new chats ---

// discordUsernamePrefix marks a SearchPeople result that is only a username nobody known has: starting a chat
// with it sends a friend request first.
const discordUsernamePrefix = "username:"

var discordUsername = regexp.MustCompile(`^[a-z0-9_.]{2,32}$`)

// SearchPeople finds friends and people from DMs by username or display name. Discord has no people search, so
// an exact username nobody known has is offered as is (StartChat adds them as a friend).
func (d *Discord) SearchPeople(query string) (string, error) {
	if _, err := d.current(); err != nil {
		return "", err
	}
	q := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(query), "@"))
	var people []foundPerson
	exact := false
	d.users.Range(func(_, value any) bool {
		u := value.(*discordgo.User)
		if u.ID == d.selfID || u.Bot || u.System {
			return true
		}
		if !strings.Contains(strings.ToLower(u.Username), q) && !strings.Contains(strings.ToLower(u.GlobalName), q) {
			return true
		}
		exact = exact || strings.EqualFold(u.Username, q)
		people = append(people, foundPerson{ID: u.ID, Name: u.DisplayName(), Handle: "@" + u.Username,
			AvatarURL: u.AvatarURL("256")})
		return true
	})
	slices.SortFunc(people, func(a, b foundPerson) int { return strings.Compare(a.Name, b.Name) })
	if !exact && discordUsername.MatchString(q) {
		people = append(people, foundPerson{ID: discordUsernamePrefix + q, Name: q, Handle: "@" + q})
	}
	return peopleJSON(people)
}

// StartChat opens the DM channel with a person (its ID is the thread key). A bare username is sent a friend
// request first: that's how Discord finds someone by username, and most people only take DMs from friends.
func (d *Discord) StartChat(personID string) (string, error) {
	session, err := d.current()
	if err != nil {
		return "", err
	}
	userID := personID
	if username, ok := strings.CutPrefix(personID, discordUsernamePrefix); ok {
		if userID, err = d.addFriend(session, username); err != nil {
			return "", err
		}
	}
	ch, err := session.UserChannelCreate(userID, d.captchaOptions()...)
	if err != nil {
		return "", d.checkCaptcha(err)
	}
	d.emitChannel(ch, "")
	return ch.ID, nil
}

// addFriend sends a friend request by username and returns the user's ID from the outgoing request it made.
func (d *Discord) addFriend(session *discordgo.Session, username string) (string, error) {
	body := map[string]any{"username": username, "discriminator": nil}
	if _, err := session.RequestWithBucketID("POST", discordgo.EndpointRelationships(), body,
		discordgo.EndpointRelationships(), d.captchaOptions()...); err != nil {
		if err := d.checkCaptcha(err); errors.Is(err, errCaptchaRequired) {
			return "", err
		}
		var restErr *discordgo.RESTError
		if errors.As(err, &restErr) && restErr.Message != nil && restErr.Message.Message != "" {
			return "", fmt.Errorf("Discord didn't send the friend request: %s", restErr.Message.Message)
		}
		return "", err
	}
	data, err := session.RequestWithBucketID("GET", discordgo.EndpointRelationships(), nil,
		discordgo.EndpointRelationships())
	if err != nil {
		return "", err
	}
	var relationships []struct {
		ID   string          `json:"id"`
		User *discordgo.User `json:"user"`
	}
	if err := json.Unmarshal(data, &relationships); err != nil {
		return "", err
	}
	for _, r := range relationships {
		if r.User != nil && strings.EqualFold(r.User.Username, username) {
			d.emitUser(r.User)
			return r.User.ID, nil
		}
	}
	return "", fmt.Errorf("no Discord user %s", username)
}

// --- account ---

// discordUnverifiedCode is Discord's "You need to verify your account in order to perform this action".
const discordUnverifiedCode = 40002

// noteVerification tells the app when Discord starts or stops refusing for an unverified account: err is that
// refusal, or sent is true once a message went through.
func (d *Discord) noteVerification(sent bool, err error) {
	var restErr *discordgo.RESTError
	refused := errors.As(err, &restErr) && restErr.Message != nil && restErr.Message.Code == discordUnverifiedCode
	if !sent && !refused {
		return
	}
	if d.needsVerification.Swap(refused) != refused {
		d.emit(map[string]any{"type": "account", "needsVerification": refused})
	}
}

// --- CAPTCHA ---

// errCaptchaRequired starts the error of a request Discord wants a CAPTCHA for; the rest is the CAPTCHA as JSON
// ({"service", "siteKey", "rqData"}). The app shows it and calls AnswerCaptcha, then makes the request again.
var errCaptchaRequired = errors.New("captcha_required")

// discordCaptcha is what Discord sends with a captcha-required error (an hCaptcha to show on discord.com).
type discordCaptcha struct {
	Keys      []string `json:"captcha_key"`
	SiteKey   string   `json:"captcha_sitekey"`
	Service   string   `json:"captcha_service"`
	SessionID string   `json:"captcha_session_id"`
	RqData    string   `json:"captcha_rqdata"`
	RqToken   string   `json:"captcha_rqtoken"`
}

// checkCaptcha turns Discord's captcha-required error into errCaptchaRequired (remembering the CAPTCHA for the
// answer); other errors are returned as they are.
func (d *Discord) checkCaptcha(err error) error {
	var restErr *discordgo.RESTError
	if !errors.As(err, &restErr) {
		return err
	}
	var captcha discordCaptcha
	if json.Unmarshal(restErr.ResponseBody, &captcha) != nil || captcha.SiteKey == "" ||
		!slices.Contains(captcha.Keys, "captcha-required") {
		return err
	}
	d.lock.Lock()
	d.captcha = &captcha
	d.captchaAnswer = ""
	d.lock.Unlock()
	data, _ := json.Marshal(map[string]string{
		"service": captcha.Service, "siteKey": captcha.SiteKey, "rqData": captcha.RqData, "pageUrl": "https://discord.com/",
	})
	return fmt.Errorf("%w:%s", errCaptchaRequired, data)
}

// AnswerCaptcha takes the token of the solved CAPTCHA; the next request sends it along.
func (d *Discord) AnswerCaptcha(token string) error {
	d.lock.Lock()
	defer d.lock.Unlock()
	if d.captcha == nil {
		return errors.New("Discord didn't ask for a CAPTCHA")
	}
	d.captchaAnswer = token
	return nil
}

// captchaOptions are the headers carrying a CAPTCHA answer (used once), as Discord's web client sends them.
func (d *Discord) captchaOptions() []discordgo.RequestOption {
	d.lock.Lock()
	defer d.lock.Unlock()
	if d.captcha == nil || d.captchaAnswer == "" {
		return nil
	}
	options := []discordgo.RequestOption{discordgo.WithHeader("X-Captcha-Key", d.captchaAnswer)}
	if d.captcha.RqToken != "" {
		options = append(options, discordgo.WithHeader("X-Captcha-Rqtoken", d.captcha.RqToken))
	}
	if d.captcha.SessionID != "" {
		options = append(options, discordgo.WithHeader("X-Captcha-Session-Id", d.captcha.SessionID))
	}
	d.captcha = nil
	d.captchaAnswer = ""
	return options
}
