package hydbridge

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"

	"go.mau.fi/mautrix-twitter/pkg/juiceboxgo"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow/cookies"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow/crypto"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow/data/payload"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow/data/response"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow/data/types"
)

// Twitter is the user's X direct messages ("X Chat", end-to-end encrypted), logged in with x.com's session
// cookies (auth_token, ct0), with mautrix-twitter's twittermeow client. X Chat's keys are kept by X behind the
// user's X Chat passcode (in Juicebox): the first connect recovers them with the passcode and keeps them in dataDir.
// Thread keys are conversation IDs, contact IDs user IDs, message IDs sequence IDs.
//
// Older history beyond each chat's latest page isn't loaded yet.
type Twitter struct {
	dataDir  string
	listener Listener
	log      zerolog.Logger
	cookies  map[string]string
	passcode string
	keys     *twitterKeyStore

	lock   sync.Mutex
	client *twittermeow.Client
	cancel context.CancelFunc
	selfID string

	contacts sync.Map
	// "conversation/sequence" → our reaction there
	ownReactions sync.Map
	typingTimers sync.Map
}

const (
	twitterInboxPages     = 3
	twitterTypingTimeout  = 6 * time.Second
	twitterAuthFailedText = "X session expired, log in again"
)

// NewTwitter takes the login as a JSON object: x.com's cookies ("auth_token", "ct0", and "twid" when present) and
// "passcode", the user's X Chat passcode, needed only until the keys are recovered.
func NewTwitter(credentialsJSON string, dataDir string, listener Listener) (*Twitter, error) {
	var creds map[string]string
	if err := json.Unmarshal([]byte(credentialsJSON), &creds); err != nil {
		return nil, fmt.Errorf("invalid login: %w", err)
	}
	if creds["auth_token"] == "" || creds["ct0"] == "" {
		return nil, errors.New("missing the X cookies")
	}
	passcode := strings.TrimSpace(creds["passcode"])
	delete(creds, "passcode")
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "twitter").Logger().Level(zerolog.InfoLevel)
	keys, err := loadTwitterKeyStore(filepath.Join(dataDir, "keys.json"))
	if err != nil {
		return nil, err
	}
	return &Twitter{dataDir: dataDir, listener: listener, log: log, cookies: creds, passcode: passcode, keys: keys}, nil
}

// Connect checks the login, recovers the X Chat keys if needed, loads the inbox and opens X Chat's websocket.
func (t *Twitter) Connect() error {
	t.lock.Lock()
	if t.cancel != nil {
		t.lock.Unlock()
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(t.log.WithContext(context.Background()))
	t.cancel = cancel
	t.lock.Unlock()

	client := twittermeow.NewClient(cookies.NewCookies(t.cookies), t.keys, t.log.With().Str("component", "twittermeow").Logger())
	client.SetEventHandler(func(types.TwitterEvent, *response.TwitterInboxData) bool { return true }, nil, nil)
	client.SetXChatEventHandler(t.handleEvent)
	profile, err := client.LoadMessagesPage(ctx)
	if err != nil {
		t.resetCancel()
		if isTwitterAuthError(err) {
			return errors.New(twitterAuthFailedText)
		}
		return fmt.Errorf("failed to load X: %w", err)
	}
	t.selfID = firstNonEmpty(profile.ID, client.GetCurrentUserID())
	if !t.keys.hasOwnKeys() {
		if err := t.recoverKeys(ctx, client); err != nil {
			t.resetCancel()
			return err
		}
	}
	t.emit(map[string]any{"type": "self", "id": t.selfID, "name": "@" + profile.ScreenName})
	t.lock.Lock()
	t.client = client
	t.lock.Unlock()
	go t.syncInbox(ctx, client)
	return nil
}

// Disconnect closes the connection; Connect can be called again.
func (t *Twitter) Disconnect() {
	t.lock.Lock()
	client := t.client
	t.client = nil
	t.lock.Unlock()
	if client != nil {
		client.Disconnect()
	}
	t.resetCancel()
	t.emitState("disconnected", "")
}

func (t *Twitter) resetCancel() {
	t.lock.Lock()
	defer t.lock.Unlock()
	if t.cancel != nil {
		t.cancel()
		t.cancel = nil
	}
}

func (t *Twitter) current() (*twittermeow.Client, error) {
	t.lock.Lock()
	defer t.lock.Unlock()
	if t.client == nil {
		return nil, errors.New("not connected")
	}
	return t.client, nil
}

// isTwitterAuthError: X turned the session down (it answers some calls with 401 "Could not authenticate you").
func isTwitterAuthError(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return twittermeow.IsAuthError(err) || strings.Contains(msg, "HTTP 401") ||
		strings.Contains(msg, "Could not authenticate")
}

// --- X Chat keys ---

// recoverKeys gets the user's X Chat keys from Juicebox with their passcode (as mautrix-twitter's login does).
func (t *Twitter) recoverKeys(ctx context.Context, client *twittermeow.Client) error {
	if t.passcode == "" {
		return errors.New("Enter your X Chat passcode")
	}
	resp, err := client.GetPublicKeys(ctx, []string{t.selfID})
	if isTwitterAuthError(err) {
		return errors.New(twitterAuthFailedText)
	} else if err != nil {
		return fmt.Errorf("failed to look up X Chat keys: %w", err)
	}
	keyData, ok := latestTwitterKeyBackup(resp)
	if !ok {
		return errors.New("X Chat isn't set up on this account yet: open Chat on x.com and create a passcode first")
	}
	configJSON := strings.TrimSpace(keyData.TokenMap.KeyStoreTokenMapJSON)
	tokens := map[string]juiceboxgo.AuthToken{}
	for _, entry := range keyData.TokenMap.TokenMap {
		if realm, token := strings.ToLower(strings.TrimSpace(entry.Key)), strings.TrimSpace(entry.Value.Token); realm != "" && token != "" {
			tokens[realm] = juiceboxgo.AuthToken(token)
		}
	}
	config, err := juiceboxgo.ConfigurationFromJSON(configJSON)
	if err != nil {
		return fmt.Errorf("failed to read X Chat's key backup settings: %w", err)
	}
	jb, err := juiceboxgo.NewClient(config, tokens, nil, t.log)
	if err != nil {
		return err
	}
	defer jb.Close()
	secret, err := jb.Recover(ctx, []byte(t.passcode), nil)
	if err != nil {
		var recoverErr *juiceboxgo.RecoverError
		switch {
		case errors.As(err, &recoverErr) && recoverErr.GuessesRemaining != nil && *recoverErr.GuessesRemaining == 0:
			return errors.New("Too many wrong passcodes: X Chat is locked, reset the passcode on x.com")
		case errors.As(err, &recoverErr) && recoverErr.GuessesRemaining != nil:
			return fmt.Errorf("Wrong X Chat passcode (%d tries left before X locks it)", *recoverErr.GuessesRemaining)
		case errors.As(err, &recoverErr):
			return errors.New("Wrong X Chat passcode")
		case errors.Is(err, juiceboxgo.ErrRateLimitExceeded):
			return errors.New("Too many tries: wait a while before entering the passcode again")
		}
		return fmt.Errorf("failed to recover the X Chat keys: %w", err)
	}
	decryptKey, signingKey := twitterSplitSecret(secret)
	key, err := crypto.LoadSigningKeyPair(t.selfID, keyData.PublicKeyWithMetadata.Version, signingKey, decryptKey)
	if err != nil {
		return fmt.Errorf("the recovered X Chat keys don't work: %w", err)
	}
	return t.keys.PutOwnSigningKey(ctx, key)
}

func latestTwitterKeyBackup(resp *response.GetPublicKeysResponse) (response.PublicKeyWithTokenMap, bool) {
	if resp == nil || len(resp.Data.UserResultsByRestIDs) == 0 {
		return response.PublicKeyWithTokenMap{}, false
	}
	var latest response.PublicKeyWithTokenMap
	found := false
	for _, k := range resp.Data.UserResultsByRestIDs[0].Result.GetPublicKeys.PublicKeysWithTokenMap {
		if len(k.TokenMap.TokenMap) == 0 || k.TokenMap.KeyStoreTokenMapJSON == "" {
			continue
		}
		if !found || compareNumeric(k.PublicKeyWithMetadata.Version, latest.PublicKeyWithMetadata.Version) > 0 {
			latest, found = k, true
		}
	}
	return latest, found
}

// twitterSplitSecret splits Juicebox's secret into the decryption and signing key scalars (base64), as
// mautrix-twitter's keybackup.go does: JSON, or 64 raw bytes (32 + 32), or a single 32-byte key.
func twitterSplitSecret(secret []byte) (decryptKey, signingKey string) {
	if len(secret) > 0 && secret[0] == '{' {
		var data struct {
			SecretKey  string `json:"secret_key"`
			SigningKey string `json:"signing_key"`
		}
		if json.Unmarshal(secret, &data) == nil {
			return data.SecretKey, data.SigningKey
		}
	}
	priv, pub := secret, []byte(nil)
	switch {
	case len(secret) >= 64 && len(secret)%2 == 0:
		priv, pub = secret[:len(secret)/2], secret[len(secret)/2:]
	case len(secret) > 32:
		priv, pub = secret[:32], secret[32:]
	}
	decryptKey = base64.StdEncoding.EncodeToString(priv)
	if len(pub) > 0 {
		signingKey = base64.StdEncoding.EncodeToString(pub)
	}
	return
}

// compareNumeric compares decimal ID strings (snowflakes) by value.
func compareNumeric(a, b string) int {
	if len(a) != len(b) {
		return len(a) - len(b)
	}
	return strings.Compare(a, b)
}

// --- inbox ---

func (t *Twitter) syncInbox(ctx context.Context, client *twittermeow.Client) {
	resp, err := client.GetInitialXChatPage(ctx, payload.NewInitialXChatPageQueryVariables(""))
	if err != nil {
		t.log.Error().Err(err).Msg("Failed to load the X Chat inbox")
		if isTwitterAuthError(err) {
			t.emitState("error", twitterAuthFailedText)
			return
		}
	} else {
		page := resp.Data.GetInboxPage
		t.processPage(ctx, client, page)
		for i := 1; i < twitterInboxPages && page.InboxCursor.CursorID != "" && ctx.Err() == nil; i++ {
			next, err := client.GetInboxPageRequest(ctx, payload.NewInboxPageRequestQueryVariables(&payload.XChatCursor{
				CursorId: page.InboxCursor.CursorID, GraphSnapshotId: page.InboxCursor.GraphSnapshotID,
			}))
			if err != nil {
				break
			}
			page = next.Data.GetInboxPage
			t.processPage(ctx, client, page)
		}
	}
	if ctx.Err() != nil {
		return
	}
	// Live events after the inbox, so they apply on top of it
	client.Connect(ctx)
	t.emitState("connected", "")
}

func (t *Twitter) processPage(ctx context.Context, client *twittermeow.Client, page response.XChatInboxPage) {
	processor := client.GetXChatProcessor()
	for i := range page.Items {
		item := &page.Items[i]
		if item.ConversationUnavailable || item.ConversationDetail.ConversationID == "" {
			continue
		}
		t.emitConversation(&item.ConversationDetail)
		if err := processor.ProcessKeyChangeEvents(ctx, item); err != nil {
			t.log.Error().Err(err).Msg("Failed to read an X Chat conversation's keys")
		}
		if err := processor.ProcessMessageAndReadEvents(ctx, item); err != nil {
			t.log.Error().Err(err).Msg("Failed to read X Chat messages")
		}
		t.emit(map[string]any{"type": "range", "threadKey": item.ConversationDetail.ConversationID, "hasMoreBefore": false})
	}
}

func (t *Twitter) emitConversation(d *response.XChatConversationDetail) {
	people := d.ParticipantsResults
	if len(d.GroupMembersResults) > 0 {
		people = d.GroupMembersResults
	}
	var names []string
	var others []*response.XChatUser
	for _, p := range people {
		user := p.Result
		if user == nil || user.RestID == "" || user.RestID == t.selfID {
			continue
		}
		others = append(others, user)
		t.emitUser(user)
		names = append(names, twitterName(user))
		t.emit(map[string]any{"type": "participant", "threadKey": d.ConversationID, "contactId": user.RestID})
	}
	isGroup := d.GroupMetadata != nil || len(others) > 1
	name := strings.Join(names, ", ")
	if d.GroupMetadata != nil && d.GroupMetadata.GroupName != "" {
		name = d.GroupMetadata.GroupName
	}
	switch {
	case d.GroupMetadata != nil && d.GroupMetadata.GroupAvatarURL != "":
		t.emit(map[string]any{"type": "avatar", "threadKey": d.ConversationID, "url": d.GroupMetadata.GroupAvatarURL})
	case !isGroup && len(others) == 1 && others[0].Avatar != nil && others[0].Avatar.ImageURL != "":
		t.emit(map[string]any{"type": "avatar", "threadKey": d.ConversationID, "url": others[0].Avatar.ImageURL})
	}
	t.emit(map[string]any{"type": "thread", "key": d.ConversationID, "name": name, "isGroup": isGroup,
		"isEncrypted": false})
}

func twitterName(u *response.XChatUser) string {
	if u.Core == nil {
		return u.RestID
	}
	return firstNonEmpty(u.Core.Name, "@"+u.Core.ScreenName, u.RestID)
}

func (t *Twitter) emitUser(u *response.XChatUser) {
	if _, sent := t.contacts.LoadOrStore(u.RestID, true); sent {
		return
	}
	avatar := ""
	if u.Avatar != nil {
		avatar = u.Avatar.ImageURL
	}
	name := twitterName(u)
	t.emit(map[string]any{"type": "contact", "id": u.RestID, "name": name,
		"firstName": strings.SplitN(name, " ", 2)[0], "avatarUrl": avatar})
}

// --- events ---

func twitterMillis(ms string) int64 {
	n, _ := strconv.ParseInt(ms, 10, 64)
	return n
}

func (t *Twitter) handleEvent(ctx context.Context, raw types.TwitterEvent) bool {
	switch evt := raw.(type) {
	case *types.Message:
		t.emitMessage(evt)
		t.emit(map[string]any{"type": "thread", "key": evt.ConversationID, "lastActivityMs": twitterMillis(evt.Time)})
	case *types.MessageEdit:
		t.emit(map[string]any{"type": "edit", "threadKey": evt.ConversationID, "messageId": evt.MessageData.ID,
			"text": evt.MessageData.Text})
	case *types.MessageDelete:
		for _, m := range evt.Messages {
			t.emit(map[string]any{"type": "unsend", "threadKey": evt.ConversationID, "messageId": m.MessageID})
		}
	case *types.MessageReactionCreate:
		t.emitReaction(evt.ConversationID, evt.MessageID, evt.SenderID, evt.EmojiReaction, true)
	case *types.MessageReactionDelete:
		t.emitReaction(evt.ConversationID, evt.MessageID, evt.SenderID, evt.EmojiReaction, false)
	case *types.ConversationRead:
		t.emit(map[string]any{"type": "read", "threadKey": evt.ConversationID, "contactId": evt.SenderID,
			"watermarkMs": twitterMillis(evt.Time)})
	case *types.XChatTyping:
		t.emitTyping(evt.ConversationID, evt.SenderID)
	}
	return true
}

func (t *Twitter) emitMessage(m *types.Message) {
	if m.ID == "" {
		return
	}
	text := m.MessageData.Text
	kind := "text"
	attachments := m.MessageData.Attachments
	if len(attachments) == 0 && m.MessageData.Attachment != nil {
		attachments = []*types.Attachment{m.MessageData.Attachment}
	}
	for _, a := range attachments {
		switch {
		case a.Photo != nil:
			kind = "image"
		case a.Video != nil, a.AnimatedGif != nil:
			kind = "video"
		case a.Tweet != nil && a.Tweet.ExpandedURL != "" && !strings.Contains(text, a.Tweet.ExpandedURL):
			text = strings.TrimSpace(text + "\n" + a.Tweet.ExpandedURL)
		}
	}
	if text == "" && kind == "text" {
		kind = "other"
	}
	event := map[string]any{
		"type": "message", "threadKey": m.ConversationID, "id": m.ID, "otid": firstNonEmpty(m.RequestID, m.ID),
		"senderId": m.MessageData.SenderID, "text": text, "kind": kind, "timestampMs": twitterMillis(m.Time),
	}
	if reply := m.MessageData.ReplyData.ID; reply != "" {
		event["replyToId"] = reply
	}
	if m.MessageData.EditCount > 0 {
		event["isEdited"] = true
	}
	t.emit(event)
	for _, r := range m.MessageReactions {
		t.emitReaction(m.ConversationID, m.ID, r.SenderID, r.EmojiReaction, true)
	}
}

func (t *Twitter) emitReaction(convID, messageID, sender, emoji string, added bool) {
	if sender == t.selfID {
		key := convID + "/" + messageID
		if added {
			t.ownReactions.Store(key, emoji)
		} else {
			t.ownReactions.Delete(key)
		}
	}
	if !added {
		emoji = ""
	}
	t.emit(map[string]any{"type": "reaction", "threadKey": convID, "targetId": messageID, "senderId": sender,
		"emoji": emoji})
}

func (t *Twitter) emitTyping(convID, sender string) {
	if sender == "" || sender == t.selfID {
		return
	}
	t.emit(map[string]any{"type": "typing", "threadKey": convID, "senderId": sender, "isTyping": true})
	key := convID + "/" + sender
	timer := time.AfterFunc(twitterTypingTimeout, func() {
		t.typingTimers.Delete(key)
		t.emit(map[string]any{"type": "typing", "threadKey": convID, "senderId": sender, "isTyping": false})
	})
	if previous, loaded := t.typingTimers.Swap(key, timer); loaded {
		previous.(*time.Timer).Stop()
	}
}

// --- sending ---

func twitterContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), time.Minute)
}

// SendText sends an encrypted message and returns its message ID (the echo's otid).
func (t *Twitter) SendText(convID, text string) (string, error) {
	client, err := t.current()
	if err != nil {
		return "", err
	}
	ctx, cancel := twitterContext()
	defer cancel()
	opts := twittermeow.SendEncryptedMessageOpts{ConversationID: convID, MessageID: newTwitterMessageID(), Text: text}
	if _, err := client.SendEncryptedMessage(ctx, opts); err != nil {
		return "", err
	}
	return opts.MessageID, nil
}

// SendReply sends a message; X Chat replies need the replied-to message's preview, so it goes as a plain message.
func (t *Twitter) SendReply(convID, text, _ string) (string, error) { return t.SendText(convID, text) }

// EditMessage changes the text of one of our messages.
func (t *Twitter) EditMessage(convID, messageID, text string) error {
	client, err := t.current()
	if err != nil {
		return err
	}
	ctx, cancel := twitterContext()
	defer cancel()
	_, err = client.SendEncryptedEdit(ctx, twittermeow.SendEncryptedEditOpts{
		ConversationID: convID, TargetMessageSequenceID: messageID, UpdatedText: text,
	})
	return err
}

// Unsend deletes one of our messages for everyone.
func (t *Twitter) Unsend(convID, messageID string) error {
	client, err := t.current()
	if err != nil {
		return err
	}
	ctx, cancel := twitterContext()
	defer cancel()
	return client.DeleteXChatMessage(ctx, twittermeow.DeleteXChatMessageOpts{
		ConversationID: convID, SequenceIDs: []string{messageID}, DeleteForAll: true,
	})
}

// SendReaction sets our reaction on a message (replacing the one we had); an empty emoji removes it.
func (t *Twitter) SendReaction(convID, messageID, emoji string) error {
	client, err := t.current()
	if err != nil {
		return err
	}
	ctx, cancel := twitterContext()
	defer cancel()
	key := convID + "/" + messageID
	if previous, ok := t.ownReactions.Load(key); ok {
		if _, err := client.SendEncryptedReaction(ctx, convID, messageID, previous.(string), true); err != nil {
			return err
		}
		t.ownReactions.Delete(key)
	}
	if emoji == "" {
		return nil
	}
	if _, err := client.SendEncryptedReaction(ctx, convID, messageID, emoji, false); err != nil {
		return err
	}
	t.ownReactions.Store(key, emoji)
	return nil
}

// MarkRead marks a conversation read up to a message.
func (t *Twitter) MarkRead(convID, messageID string) error {
	if messageID == "" {
		return nil
	}
	client, err := t.current()
	if err != nil {
		return err
	}
	ctx, cancel := twitterContext()
	defer cancel()
	return client.SendXChatReadReceipt(ctx, convID, messageID, time.Now())
}

// SetTyping shows "typing…" for a few seconds.
func (t *Twitter) SetTyping(convID string, isTyping bool) error {
	if !isTyping {
		return nil
	}
	client, err := t.current()
	if err != nil {
		return err
	}
	ctx, cancel := twitterContext()
	defer cancel()
	return client.SendXChatTypingNotification(ctx, convID)
}

// SendMediaFile isn't supported yet: X Chat media needs its own encrypted upload.
func (t *Twitter) SendMediaFile(_, path, _, _, _ string) (string, error) {
	_ = os.Remove(path)
	return "", ErrUnsupported
}

// RequestOlder: only each chat's latest page is loaded for now.
func (t *Twitter) RequestOlder(convID, _ string) error {
	t.emit(map[string]any{"type": "range", "threadKey": convID, "hasMoreBefore": false})
	return nil
}

func newTwitterMessageID() string {
	return strings.ToLower(strconv.FormatInt(time.Now().UnixNano(), 36)) + "-hyd"
}

func (t *Twitter) emitState(state, err string) {
	t.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (t *Twitter) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && t.listener != nil {
		t.listener.OnEvent(string(data))
	}
}

// --- key store ---

// twitterKeyStore keeps X Chat's keys in a JSON file: the user's own key pair, each conversation's keys and its
// send token. crypto.KeyStore for twittermeow.
type twitterKeyStore struct {
	path string
	lock sync.Mutex
	data twitterKeys
}

type twitterKeys struct {
	SigningKey        string                                        `json:"signing_key,omitempty"`
	DecryptKey        string                                        `json:"decrypt_key,omitempty"`
	SigningKeyVersion string                                        `json:"signing_key_version,omitempty"`
	UserID            string                                        `json:"user_id,omitempty"`
	Conversations     map[string]map[string]*crypto.ConversationKey `json:"conversations,omitempty"`
	Tokens            map[string]string                             `json:"tokens,omitempty"`
}

func loadTwitterKeyStore(path string) (*twitterKeyStore, error) {
	s := &twitterKeyStore{path: path}
	data, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
	case err != nil:
		return nil, err
	default:
		if err := json.Unmarshal(data, &s.data); err != nil {
			return nil, err
		}
	}
	if s.data.Conversations == nil {
		s.data.Conversations = map[string]map[string]*crypto.ConversationKey{}
	}
	if s.data.Tokens == nil {
		s.data.Tokens = map[string]string{}
	}
	return s, nil
}

// save writes the file; call with the lock held.
func (s *twitterKeyStore) save() error {
	var buf bytes.Buffer
	if err := json.NewEncoder(&buf).Encode(s.data); err != nil {
		return err
	}
	return writeFileAtomic(s.path, buf.Bytes())
}

func (s *twitterKeyStore) hasOwnKeys() bool {
	s.lock.Lock()
	defer s.lock.Unlock()
	return s.data.DecryptKey != ""
}

func (s *twitterKeyStore) GetConversationKey(_ context.Context, conversationID, keyVersion string) (*crypto.ConversationKey, error) {
	s.lock.Lock()
	defer s.lock.Unlock()
	if key := s.data.Conversations[conversationID][keyVersion]; key != nil {
		return key, nil
	}
	return nil, crypto.ErrKeyNotFound
}

func (s *twitterKeyStore) PutConversationKey(_ context.Context, key *crypto.ConversationKey) error {
	s.lock.Lock()
	defer s.lock.Unlock()
	if s.data.Conversations[key.ConversationID] == nil {
		s.data.Conversations[key.ConversationID] = map[string]*crypto.ConversationKey{}
	}
	s.data.Conversations[key.ConversationID][key.KeyVersion] = key
	return s.save()
}

func (s *twitterKeyStore) DeleteConversationKey(_ context.Context, conversationID, keyVersion string) error {
	s.lock.Lock()
	defer s.lock.Unlock()
	delete(s.data.Conversations[conversationID], keyVersion)
	return s.save()
}

func (s *twitterKeyStore) GetLatestConversationKey(_ context.Context, conversationID string) (*crypto.ConversationKey, error) {
	s.lock.Lock()
	defer s.lock.Unlock()
	versions := make([]string, 0, len(s.data.Conversations[conversationID]))
	for v := range s.data.Conversations[conversationID] {
		versions = append(versions, v)
	}
	if len(versions) == 0 {
		return nil, crypto.ErrKeyNotFound
	}
	slices.SortFunc(versions, compareNumeric)
	return s.data.Conversations[conversationID][versions[len(versions)-1]], nil
}

func (s *twitterKeyStore) GetOwnSigningKey(_ context.Context) (*crypto.SigningKeyPair, error) {
	s.lock.Lock()
	defer s.lock.Unlock()
	if s.data.DecryptKey == "" && s.data.SigningKey == "" {
		return nil, crypto.ErrKeyNotFound
	}
	return crypto.LoadSigningKeyPair(s.data.UserID, s.data.SigningKeyVersion, s.data.SigningKey, s.data.DecryptKey)
}

func (s *twitterKeyStore) PutOwnSigningKey(_ context.Context, key *crypto.SigningKeyPair) error {
	s.lock.Lock()
	defer s.lock.Unlock()
	s.data.SigningKey, s.data.DecryptKey = key.SigningKeyB64, key.DecryptKeyB64
	s.data.SigningKeyVersion, s.data.UserID = key.KeyVersion, key.UserID
	return s.save()
}

func (s *twitterKeyStore) GetPublicKey(_ context.Context, _, _ string) (*crypto.PublicKeyInfo, error) {
	return nil, crypto.ErrKeyNotFound
}

func (s *twitterKeyStore) PutPublicKey(_ context.Context, _ *crypto.PublicKeyInfo) error { return nil }

func (s *twitterKeyStore) GetConversationToken(_ context.Context, conversationID string) (string, error) {
	s.lock.Lock()
	defer s.lock.Unlock()
	if token := s.data.Tokens[conversationID]; token != "" {
		return token, nil
	}
	return "", crypto.ErrKeyNotFound
}

func (s *twitterKeyStore) PutConversationToken(_ context.Context, conversationID, token string) error {
	s.lock.Lock()
	defer s.lock.Unlock()
	s.data.Tokens[conversationID] = token
	return s.save()
}

var (
	_ crypto.KeyStore = (*twitterKeyStore)(nil)
	_ ChatClient      = (*Twitter)(nil)
)
