package hydbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
)

// Bluesky is the user's Bluesky direct messages, through Bluesky's own chat API (chat.bsky.*), logged in with the
// handle and an app password that is allowed to read messages. Thread keys are conversation IDs, contact IDs are
// DIDs, message IDs are message IDs. Bluesky chats have no typing indicator, attachments or edits.
type Bluesky struct {
	dataDir    string
	listener   Listener
	log        zerolog.Logger
	http       *http.Client
	identifier string
	password   string

	lock    sync.Mutex
	cancel  context.CancelFunc
	session blueskySession

	// Convo ID → member DIDs, to name chats and tell groups apart
	members sync.Map
	// DID → true once its contact event was sent
	contacts sync.Map
	// "convo/message" → our reaction on it
	ownReactions sync.Map
	// Convo ID → where its next older page starts (Bluesky pages by cursor, not by message); under lock
	cursors map[string]string
}

type blueskySession struct {
	AccessJwt  string `json:"accessJwt"`
	RefreshJwt string `json:"refreshJwt"`
	Did        string `json:"did"`
	Handle     string `json:"handle"`
	// The user's own server, where every call goes (chat calls are proxied from there to Bluesky's chat service)
	PDS string `json:"pds"`
}

// Where accounts sign in, and how often new events are asked for (variables so tests can use a local server)
var (
	blueskyEntryway     = "https://bsky.social"
	blueskyPollInterval = 3 * time.Second
)

const (
	blueskyChatProxy  = "did:web:api.bsky.chat#bsky_chat"
	blueskyPageSize   = 50
	blueskyConvoPages = 4
	// Chats whose recent messages load on connect
	blueskyPrefetchConvos = 20
)

// NewBluesky takes the login as a JSON object: "identifier" (handle, e-mail or DID) and "password" (an app
// password with access to direct messages).
func NewBluesky(credentialsJSON string, dataDir string, listener Listener) (*Bluesky, error) {
	var creds struct {
		Identifier string `json:"identifier"`
		Password   string `json:"password"`
	}
	if err := json.Unmarshal([]byte(credentialsJSON), &creds); err != nil {
		return nil, fmt.Errorf("invalid login: %w", err)
	}
	creds.Identifier = strings.TrimPrefix(strings.TrimSpace(creds.Identifier), "@")
	if creds.Identifier == "" || creds.Password == "" {
		return nil, errors.New("missing the Bluesky handle or app password")
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "bluesky").Logger().Level(zerolog.InfoLevel)
	return &Bluesky{
		dataDir: dataDir, listener: listener, log: log, identifier: creds.Identifier, password: creds.Password,
		http: &http.Client{Timeout: time.Minute}, cursors: map[string]string{},
	}, nil
}

// Connect signs in (reusing the saved session when it's still valid), loads the conversations and starts
// following new events.
func (b *Bluesky) Connect() error {
	b.lock.Lock()
	if b.cancel != nil {
		b.lock.Unlock()
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(context.Background())
	b.cancel = cancel
	b.lock.Unlock()

	if err := b.signIn(ctx); err != nil {
		b.resetCancel()
		return err
	}
	b.emit(map[string]any{"type": "self", "id": b.session.Did, "name": "@" + b.session.Handle})
	// The log cursor before loading the inbox, so nothing that happens while it loads is missed
	cursor, err := b.latestLogCursor(ctx)
	if err != nil {
		b.resetCancel()
		return b.describe(err)
	}
	b.emitState("connected", "")
	go b.syncConvos(ctx)
	go b.poll(ctx, cursor)
	return nil
}

// Disconnect stops following events; Connect can be called again.
func (b *Bluesky) Disconnect() {
	b.resetCancel()
	b.emitState("disconnected", "")
}

func (b *Bluesky) resetCancel() {
	b.lock.Lock()
	defer b.lock.Unlock()
	if b.cancel != nil {
		b.cancel()
		b.cancel = nil
	}
}

// --- session ---

func (b *Bluesky) sessionPath() string { return filepath.Join(b.dataDir, "session.json") }

func (b *Bluesky) signIn(ctx context.Context) error {
	if data, err := os.ReadFile(b.sessionPath()); err == nil {
		var saved blueskySession
		if json.Unmarshal(data, &saved) == nil && saved.RefreshJwt != "" && b.matchesLogin(saved) {
			b.session = saved
			if err := b.refresh(ctx); err == nil {
				return nil
			}
			b.log.Info().Msg("Saved Bluesky session expired, signing in again")
		}
	}
	var resp struct {
		blueskySession
		DidDoc json.RawMessage `json:"didDoc"`
	}
	err := b.call(ctx, blueskyEntryway, http.MethodPost, "com.atproto.server.createSession", nil,
		map[string]string{"identifier": b.identifier, "password": b.password}, "", &resp)
	if err != nil {
		return b.describe(err)
	}
	b.session = resp.blueskySession
	b.session.PDS = pdsFromDidDoc(resp.DidDoc, blueskyEntryway)
	b.saveSession()
	return nil
}

// matchesLogin: a saved session belongs to this login (the user may have signed in with another account since).
func (b *Bluesky) matchesLogin(s blueskySession) bool {
	id := strings.ToLower(b.identifier)
	return id == strings.ToLower(s.Handle) || id == strings.ToLower(s.Did) || strings.Contains(id, "@")
}

func (b *Bluesky) refresh(ctx context.Context) error {
	var resp struct {
		blueskySession
		DidDoc json.RawMessage `json:"didDoc"`
	}
	pds := firstNonEmpty(b.session.PDS, blueskyEntryway)
	if err := b.call(ctx, pds, http.MethodPost, "com.atproto.server.refreshSession", nil, nil, b.session.RefreshJwt,
		&resp); err != nil {
		return err
	}
	b.lock.Lock()
	b.session.AccessJwt, b.session.RefreshJwt = resp.AccessJwt, resp.RefreshJwt
	b.session.Handle = firstNonEmpty(resp.Handle, b.session.Handle)
	b.session.PDS = pdsFromDidDoc(resp.DidDoc, pds)
	b.lock.Unlock()
	b.saveSession()
	return nil
}

func (b *Bluesky) saveSession() {
	b.lock.Lock()
	data, err := json.Marshal(b.session)
	b.lock.Unlock()
	if err == nil {
		_ = writeFileAtomic(b.sessionPath(), data)
	}
}

// pdsFromDidDoc finds the account's own server in its DID document.
func pdsFromDidDoc(doc json.RawMessage, fallback string) string {
	var parsed struct {
		Service []struct {
			ID              string `json:"id"`
			Type            string `json:"type"`
			ServiceEndpoint string `json:"serviceEndpoint"`
		} `json:"service"`
	}
	if json.Unmarshal(doc, &parsed) == nil {
		for _, s := range parsed.Service {
			if strings.HasSuffix(s.ID, "#atproto_pds") && strings.HasPrefix(s.ServiceEndpoint, "https://") {
				return strings.TrimSuffix(s.ServiceEndpoint, "/")
			}
		}
	}
	return fallback
}

// --- XRPC ---

type blueskyError struct {
	Status  int
	Code    string `json:"error"`
	Message string `json:"message"`
}

func (e *blueskyError) Error() string {
	return fmt.Sprintf("Bluesky: %s (%d): %s", e.Code, e.Status, e.Message)
}

// describe turns Bluesky's errors into what the user should do about them.
func (b *Bluesky) describe(err error) error {
	var be *blueskyError
	if !errors.As(err, &be) {
		return err
	}
	switch {
	case be.Code == "AuthFactorTokenRequired":
		return errors.New("This account asks for a sign-in code: use an app password instead")
	case be.Code == "AuthenticationRequired" || be.Status == http.StatusUnauthorized:
		return errors.New("Wrong Bluesky handle or app password")
	case strings.Contains(be.Message, "Bad token scope") || strings.Contains(be.Message, "scope"):
		return errors.New("This app password can't read messages: create one with access to direct messages")
	case be.Code == "RateLimitExceeded":
		return errors.New("Too many sign-ins: try again in a few minutes")
	}
	return err
}

// call makes an XRPC request; token is the bearer token (the access token when empty).
func (b *Bluesky) call(ctx context.Context, host, method, nsid string, query url.Values, body any, token string,
	out any) error {
	endpoint := host + "/xrpc/" + nsid
	if len(query) > 0 {
		endpoint += "?" + query.Encode()
	}
	var reader io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint, reader)
	if err != nil {
		return err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if strings.HasPrefix(nsid, "chat.bsky.") {
		req.Header.Set("Atproto-Proxy", blueskyChatProxy)
	}
	if token == "" {
		b.lock.Lock()
		token = b.session.AccessJwt
		b.lock.Unlock()
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := b.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		be := &blueskyError{Status: resp.StatusCode}
		_ = json.Unmarshal(data, be)
		return be
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(data, out)
}

// chat calls the chat service through the user's server, refreshing the session once if it expired.
func (b *Bluesky) chat(ctx context.Context, method, nsid string, query url.Values, body, out any) error {
	b.lock.Lock()
	pds := b.session.PDS
	b.lock.Unlock()
	err := b.call(ctx, pds, method, nsid, query, body, "", out)
	var be *blueskyError
	if errors.As(err, &be) && (be.Code == "ExpiredToken" || be.Code == "InvalidToken") {
		if refreshErr := b.refresh(ctx); refreshErr != nil {
			b.emitState("error", "Bluesky session expired, log in again")
			return refreshErr
		}
		return b.call(ctx, pds, method, nsid, query, body, "", out)
	}
	return err
}

// --- data ---

type blueskyMember struct {
	Did         string `json:"did"`
	Handle      string `json:"handle"`
	DisplayName string `json:"displayName"`
	Avatar      string `json:"avatar"`
}

type blueskyReaction struct {
	Value  string `json:"value"`
	Sender struct {
		Did string `json:"did"`
	} `json:"sender"`
}

type blueskyMessage struct {
	Type   string `json:"$type"`
	ID     string `json:"id"`
	Text   string `json:"text"`
	SentAt string `json:"sentAt"`
	Sender struct {
		Did string `json:"did"`
	} `json:"sender"`
	Embed     json.RawMessage   `json:"embed"`
	Reactions []blueskyReaction `json:"reactions"`
}

type blueskyConvo struct {
	ID          string          `json:"id"`
	Members     []blueskyMember `json:"members"`
	LastMessage *blueskyMessage `json:"lastMessage"`
	UnreadCount int             `json:"unreadCount"`
	Status      string          `json:"status"`
}

func (m *blueskyMessage) isDeleted() bool { return strings.HasSuffix(m.Type, "#deletedMessageView") }

func blueskyMillis(timestamp string) int64 {
	t, err := time.Parse(time.RFC3339Nano, timestamp)
	if err != nil {
		return 0
	}
	return t.UnixMilli()
}

// --- conversations ---

func (b *Bluesky) syncConvos(ctx context.Context) {
	var convos []blueskyConvo
	cursor := ""
	for page := 0; page < blueskyConvoPages; page++ {
		var resp struct {
			Convos []blueskyConvo `json:"convos"`
			Cursor string         `json:"cursor"`
		}
		query := url.Values{"limit": {"100"}}
		if cursor != "" {
			query.Set("cursor", cursor)
		}
		if err := b.chat(ctx, http.MethodGet, "chat.bsky.convo.listConvos", query, nil, &resp); err != nil {
			b.log.Warn().Err(err).Msg("Failed to load Bluesky chats")
			if page == 0 {
				b.emitState("error", b.describe(err).Error())
			}
			break
		}
		convos = append(convos, resp.Convos...)
		if resp.Cursor == "" {
			break
		}
		cursor = resp.Cursor
	}
	for i := range convos {
		b.emitConvo(&convos[i])
	}
	for i := range convos {
		if i >= blueskyPrefetchConvos || ctx.Err() != nil {
			break
		}
		if convos[i].LastMessage != nil {
			if err := b.fetchHistory(ctx, convos[i].ID, ""); err != nil {
				b.log.Warn().Err(err).Msg("Failed to load recent Bluesky messages")
			}
		}
	}
}

func (b *Bluesky) loadConvo(ctx context.Context, convoID string) {
	var resp struct {
		Convo blueskyConvo `json:"convo"`
	}
	if err := b.chat(ctx, http.MethodGet, "chat.bsky.convo.getConvo", url.Values{"convoId": {convoID}}, nil,
		&resp); err != nil {
		b.log.Warn().Err(err).Msg("Failed to load a Bluesky chat")
		return
	}
	b.emitConvo(&resp.Convo)
}

func (b *Bluesky) emitConvo(c *blueskyConvo) {
	var others []blueskyMember
	var dids []string
	for _, m := range c.Members {
		dids = append(dids, m.Did)
		b.emitMember(m)
		if m.Did != b.session.Did {
			others = append(others, m)
		}
	}
	b.members.Store(c.ID, dids)
	isGroup := len(others) > 1
	var names []string
	for _, m := range others {
		names = append(names, blueskyName(m))
		b.emit(map[string]any{"type": "participant", "threadKey": c.ID, "contactId": m.Did})
	}
	if !isGroup && len(others) == 1 && others[0].Avatar != "" {
		b.emit(map[string]any{"type": "avatar", "threadKey": c.ID, "url": others[0].Avatar})
	}
	var lastActivity int64
	if c.LastMessage != nil {
		lastActivity = blueskyMillis(c.LastMessage.SentAt)
	}
	lastRead := lastActivity
	if c.UnreadCount > 0 {
		// Bluesky only says how many are unread: everything from the last message on counts
		lastRead = lastActivity - 1
	}
	b.emit(map[string]any{
		"type": "thread", "key": c.ID, "name": strings.Join(names, ", "), "isGroup": isGroup, "isEncrypted": false,
		"lastActivityMs": lastActivity, "lastReadMs": lastRead,
	})
}

func blueskyName(m blueskyMember) string {
	return firstNonEmpty(m.DisplayName, "@"+m.Handle, m.Did)
}

func (b *Bluesky) emitMember(m blueskyMember) {
	if _, sent := b.contacts.LoadOrStore(m.Did, true); sent {
		return
	}
	name := blueskyName(m)
	b.emit(map[string]any{"type": "contact", "id": m.Did, "name": name,
		"firstName": strings.SplitN(name, " ", 2)[0], "avatarUrl": m.Avatar})
}

// --- messages ---

func (b *Bluesky) emitMessage(convoID string, m *blueskyMessage) {
	if m.ID == "" || m.isDeleted() {
		return
	}
	text, kind := m.Text, "text"
	if text == "" {
		text, kind = blueskyEmbedText(m.Embed), "text"
		if text == "" {
			kind = "other"
		}
	} else if extra := blueskyEmbedText(m.Embed); extra != "" && !strings.Contains(text, extra) {
		text += "\n" + extra
	}
	b.emit(map[string]any{
		"type": "message", "threadKey": convoID, "id": m.ID, "otid": m.ID, "senderId": m.Sender.Did,
		"text": text, "kind": kind, "timestampMs": blueskyMillis(m.SentAt),
	})
	for _, r := range m.Reactions {
		b.emitReaction(convoID, m.ID, r.Sender.Did, r.Value, true)
	}
}

// blueskyEmbedText describes a shared post as a link to it (DMs embed posts, not files).
func blueskyEmbedText(embed json.RawMessage) string {
	if len(embed) == 0 {
		return ""
	}
	var parsed struct {
		Record struct {
			URI    string `json:"uri"`
			Author struct {
				Handle string `json:"handle"`
			} `json:"author"`
		} `json:"record"`
	}
	if json.Unmarshal(embed, &parsed) != nil || parsed.Record.URI == "" {
		return ""
	}
	// at://did/app.bsky.feed.post/rkey → https://bsky.app/profile/handle/post/rkey
	parts := strings.Split(strings.TrimPrefix(parsed.Record.URI, "at://"), "/")
	if len(parts) != 3 || parts[1] != "app.bsky.feed.post" {
		return ""
	}
	return "https://bsky.app/profile/" + firstNonEmpty(parsed.Record.Author.Handle, parts[0]) + "/post/" + parts[2]
}

func (b *Bluesky) emitReaction(convoID, messageID, sender, value string, added bool) {
	if sender == b.session.Did {
		key := convoID + "/" + messageID
		if added {
			b.ownReactions.Store(key, value)
		} else {
			b.ownReactions.Delete(key)
		}
	}
	if !added {
		value = ""
	}
	b.emit(map[string]any{"type": "reaction", "threadKey": convoID, "targetId": messageID, "senderId": sender,
		"emoji": value})
}

func (b *Bluesky) fetchHistory(ctx context.Context, convoID, cursor string) error {
	var resp struct {
		Messages []blueskyMessage `json:"messages"`
		Cursor   string           `json:"cursor"`
	}
	query := url.Values{"convoId": {convoID}, "limit": {fmt.Sprint(blueskyPageSize)}}
	if cursor != "" {
		query.Set("cursor", cursor)
	}
	if err := b.chat(ctx, http.MethodGet, "chat.bsky.convo.getMessages", query, nil, &resp); err != nil {
		return err
	}
	for i := range resp.Messages {
		b.emitMessage(convoID, &resp.Messages[i])
	}
	b.lock.Lock()
	b.cursors[convoID] = resp.Cursor
	b.lock.Unlock()
	b.emit(map[string]any{"type": "range", "threadKey": convoID, "hasMoreBefore": resp.Cursor != ""})
	return nil
}

// --- following new events ---

func (b *Bluesky) latestLogCursor(ctx context.Context) (string, error) {
	var resp struct {
		Cursor string `json:"cursor"`
	}
	if err := b.chat(ctx, http.MethodGet, "chat.bsky.convo.getLog", nil, nil, &resp); err != nil {
		return "", err
	}
	return resp.Cursor, nil
}

type blueskyLogEntry struct {
	Type     string          `json:"$type"`
	ConvoID  string          `json:"convoId"`
	Message  *blueskyMessage `json:"message"`
	Reaction *struct {
		Value  string `json:"value"`
		Sender struct {
			Did string `json:"did"`
		} `json:"sender"`
	} `json:"reaction"`
}

// poll asks for what changed since the cursor every few seconds (Bluesky's chat service has no push for clients).
func (b *Bluesky) poll(ctx context.Context, cursor string) {
	ticker := time.NewTicker(blueskyPollInterval)
	defer ticker.Stop()
	failures := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		var resp struct {
			Logs   []blueskyLogEntry `json:"logs"`
			Cursor string            `json:"cursor"`
		}
		query := url.Values{}
		if cursor != "" {
			query.Set("cursor", cursor)
		}
		if err := b.chat(ctx, http.MethodGet, "chat.bsky.convo.getLog", query, nil, &resp); err != nil {
			if ctx.Err() != nil {
				return
			}
			failures++
			if failures == 3 {
				b.emitState("reconnecting", err.Error())
			}
			continue
		}
		if failures >= 3 {
			b.emitState("connected", "")
		}
		failures = 0
		for i := range resp.Logs {
			b.handleLog(ctx, &resp.Logs[i])
		}
		if resp.Cursor != "" {
			cursor = resp.Cursor
		}
	}
}

func (b *Bluesky) handleLog(ctx context.Context, entry *blueskyLogEntry) {
	kind := entry.Type[strings.LastIndex(entry.Type, "#")+1:]
	if _, known := b.members.Load(entry.ConvoID); !known && entry.ConvoID != "" {
		b.loadConvo(ctx, entry.ConvoID)
	}
	switch kind {
	case "logBeginConvo", "logAcceptConvo":
		b.loadConvo(ctx, entry.ConvoID)
	case "logCreateMessage":
		if entry.Message != nil {
			b.emitMessage(entry.ConvoID, entry.Message)
			b.emit(map[string]any{"type": "thread", "key": entry.ConvoID,
				"lastActivityMs": blueskyMillis(entry.Message.SentAt)})
		}
	case "logDeleteMessage":
		if entry.Message != nil {
			b.emit(map[string]any{"type": "unsend", "threadKey": entry.ConvoID, "messageId": entry.Message.ID})
		}
	case "logReadMessage":
		if entry.Message != nil {
			b.emit(map[string]any{"type": "read", "threadKey": entry.ConvoID, "contactId": b.session.Did,
				"watermarkMs": blueskyMillis(entry.Message.SentAt)})
		}
	case "logAddReaction", "logRemoveReaction":
		if entry.Message != nil && entry.Reaction != nil {
			b.emitReaction(entry.ConvoID, entry.Message.ID, entry.Reaction.Sender.Did, entry.Reaction.Value,
				kind == "logAddReaction")
		}
	}
}

// --- sending ---

func blueskyContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), time.Minute)
}

// SendText sends a message and returns its ID (the echo's otid).
func (b *Bluesky) SendText(convoID, text string) (string, error) {
	ctx, cancel := blueskyContext()
	defer cancel()
	var sent blueskyMessage
	err := b.chat(ctx, http.MethodPost, "chat.bsky.convo.sendMessage", nil,
		map[string]any{"convoId": convoID, "message": map[string]any{"text": text}}, &sent)
	if err != nil {
		return "", err
	}
	b.emitMessage(convoID, &sent)
	return sent.ID, nil
}

// Unsend deletes a message for the user (Bluesky can't delete it for the other person).
func (b *Bluesky) Unsend(convoID, messageID string) error {
	ctx, cancel := blueskyContext()
	defer cancel()
	err := b.chat(ctx, http.MethodPost, "chat.bsky.convo.deleteMessageForSelf", nil,
		map[string]string{"convoId": convoID, "messageId": messageID}, nil)
	if err == nil {
		b.emit(map[string]any{"type": "unsend", "threadKey": convoID, "messageId": messageID})
	}
	return err
}

// SendReaction sets our reaction on a message (replacing the one we had); an empty emoji removes it.
func (b *Bluesky) SendReaction(convoID, messageID, value string) error {
	ctx, cancel := blueskyContext()
	defer cancel()
	key := convoID + "/" + messageID
	if previous, ok := b.ownReactions.Load(key); ok {
		if err := b.chat(ctx, http.MethodPost, "chat.bsky.convo.removeReaction", nil,
			map[string]string{"convoId": convoID, "messageId": messageID, "value": previous.(string)}, nil); err != nil {
			return err
		}
		b.ownReactions.Delete(key)
	}
	if value == "" {
		return nil
	}
	err := b.chat(ctx, http.MethodPost, "chat.bsky.convo.addReaction", nil,
		map[string]string{"convoId": convoID, "messageId": messageID, "value": value}, nil)
	if err == nil {
		b.ownReactions.Store(key, value)
	}
	return err
}

// MarkRead marks a chat read up to a message.
func (b *Bluesky) MarkRead(convoID, messageID string) error {
	ctx, cancel := blueskyContext()
	defer cancel()
	body := map[string]string{"convoId": convoID}
	if messageID != "" {
		body["messageId"] = messageID
	}
	return b.chat(ctx, http.MethodPost, "chat.bsky.convo.updateRead", nil, body, nil)
}

// RequestOlder loads the next older page of a chat (the newest when nothing loaded yet); the messages arrive as
// message events, then a range event.
func (b *Bluesky) RequestOlder(convoID, beforeMessageID string) error {
	ctx, cancel := blueskyContext()
	defer cancel()
	cursor := ""
	if beforeMessageID != "" {
		b.lock.Lock()
		cursor = b.cursors[convoID]
		b.lock.Unlock()
		if cursor == "" {
			b.emit(map[string]any{"type": "range", "threadKey": convoID, "hasMoreBefore": false})
			return nil
		}
	}
	return b.fetchHistory(ctx, convoID, cursor)
}

func (b *Bluesky) emitState(state, err string) {
	b.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (b *Bluesky) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && b.listener != nil {
		b.listener.OnEvent(string(data))
	}
}

// SearchPeople finds Bluesky accounts by handle or name (the same search as Bluesky's "new chat" box).
func (b *Bluesky) SearchPeople(query string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	var resp struct {
		Actors []blueskyMember `json:"actors"`
	}
	if err := b.chat(ctx, http.MethodGet, "app.bsky.actor.searchActorsTypeahead",
		url.Values{"q": {query}, "limit": {"20"}}, nil, &resp); err != nil {
		return "", b.describe(err)
	}
	people := make([]foundPerson, 0, len(resp.Actors))
	for _, a := range resp.Actors {
		if a.Did == b.session.Did {
			continue
		}
		people = append(people, foundPerson{ID: a.Did, Name: blueskyName(a), Handle: "@" + a.Handle, AvatarURL: a.Avatar})
	}
	return peopleJSON(people)
}

// StartChat opens (or creates) the chat with an account; Bluesky refuses it when the person doesn't accept
// messages from the user.
func (b *Bluesky) StartChat(personID string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	var resp struct {
		Convo blueskyConvo `json:"convo"`
	}
	if err := b.chat(ctx, http.MethodGet, "chat.bsky.convo.getConvoForMembers", url.Values{"members": {personID}},
		nil, &resp); err != nil {
		return "", b.describe(err)
	}
	b.emitConvo(&resp.Convo)
	return resp.Convo.ID, nil
}
