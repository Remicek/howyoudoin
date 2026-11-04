package hydbridge

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/rs/zerolog"
	"go.uber.org/zap"

	"go.mau.fi/mautrix-telegram/pkg/gotd/telegram"
	"go.mau.fi/mautrix-telegram/pkg/gotd/telegram/auth"
	"go.mau.fi/mautrix-telegram/pkg/gotd/telegram/auth/qrlogin"
	"go.mau.fi/mautrix-telegram/pkg/gotd/telegram/updates"
	"go.mau.fi/mautrix-telegram/pkg/gotd/tg"
	"go.mau.fi/mautrix-telegram/pkg/gotd/tgerr"
)

// Telegram is a Telegram account (mautrix-telegram's MTProto client) running in the app. It emits the same JSON
// events as the other networks. Thread keys are "u<user ID>", "g<basic group ID>" or "c<channel/supergroup ID>",
// contact IDs are user IDs, and message IDs are "<thread key>/<message ID>" (channel message IDs are per channel).
//
// Logging in emits {"type":"login","step":"code"|"password"} events; SubmitCode and SubmitPassword answer them.
type Telegram struct {
	apiID    int
	apiHash  string
	dataDir  string
	listener Listener
	log      zerolog.Logger

	client  *telegram.Client
	updates *updates.Manager
	store   *telegramStore

	lock    sync.Mutex
	cancel  context.CancelFunc
	started bool // the first client has been run; later connects need a new one
	running atomic.Bool
	// Closed once logged in (at connect, or after a login)
	authorized     chan struct{}
	authorizedOnce sync.Once
	selfID         atomic.Int64

	// The phone login in progress
	loginPhone    string
	loginCodeHash string
	// The QR login in progress: its cancel, and the signal that the QR code was accepted on another device
	qrCancel   context.CancelFunc
	qrAccepted chan struct{}

	peers      sync.Map // thread key / "u<id>" → tg.InputPeerClass
	msgThreads sync.Map // message ID outside channels → thread key (deletions come without the chat)
	msgDates   sync.Map // message ID ("<thread key>/<id>") → unix ms, for read receipts
	reactions  sync.Map // message ID → map[sender]emoji, to tell removals apart
	typing     sync.Map // thread key + sender → *time.Timer that ends the typing indicator
	// Message ID → random ID of a media message we sent, so its echo (from the update stream) carries it as otid
	sentRandomIDs sync.Map
	// Random ID of a reply we sent → the message it replies to, for its echo
	pendingReplies sync.Map
}

// NewTelegram creates the client; apiID and apiHash are the app's credentials from my.telegram.org. The login
// (session keys and update state) is kept in dataDir.
func NewTelegram(apiID int, apiHash, dataDir string, listener Listener) (*Telegram, error) {
	if apiID == 0 || apiHash == "" {
		return nil, errors.New("Telegram API credentials are missing")
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "telegram").Logger().Level(zerolog.InfoLevel)
	t := &Telegram{
		apiID: apiID, apiHash: apiHash, dataDir: dataDir, listener: listener, log: log,
		store:      loadTelegramStore(filepath.Join(dataDir, "telegram-state.json")),
		authorized: make(chan struct{}),
	}
	t.selfID.Store(t.store.selfID())

	dispatcher := tg.NewUpdateDispatcher()
	t.registerHandlers(dispatcher)
	t.updates = updates.New(updates.Config{
		Handler:      dispatcher,
		Storage:      t.store,
		AccessHasher: t.store,
		Logger:       zap.NewNop(),
	})
	t.client = t.newClient()
	return t, nil
}

// newClient makes the MTProto client; a client can't run again once its run was cancelled, so each Connect after a
// Disconnect makes a new one (the session file keeps the login).
func (t *Telegram) newClient() *telegram.Client {
	return telegram.NewClient(t.apiID, t.apiHash, telegram.Options{
		SessionStorage: telegramSessionStorage{path: filepath.Join(t.dataDir, "telegram-session.json")},
		UpdateHandler:  telegramUpdateHandler{t},
		Logger:         zap.NewNop(),
		Device: telegram.DeviceConfig{
			DeviceModel:    "HowYouDoin",
			SystemVersion:  "Android",
			AppVersion:     "1.0",
			SystemLangCode: "en",
			LangCode:       "en",
		},
		OnAuthError: func(err error) {
			// Before logging in, Telegram answers "unauthorised" as a matter of course; only a session that was
			// logged in can have been logged out (and reporting it makes the app delete the session files)
			if !t.IsLoggedIn() {
				return
			}
			t.log.Warn().Err(err).Msg("Telegram auth error")
			t.clearLogin()
			t.emitState("logged_out", err.Error())
		},
	})
}

// IsLoggedIn tells whether a login was completed on this phone (the session may still have been ended elsewhere).
func (t *Telegram) IsLoggedIn() bool {
	return t.selfID.Load() != 0
}

// Connect starts the connection. When logged in, the chat list and new messages follow; otherwise a "needs_login"
// state is emitted and StartLogin can be called.
func (t *Telegram) Connect() error {
	t.lock.Lock()
	defer t.lock.Unlock()
	if t.cancel != nil {
		return nil
	}
	ctx, cancel := context.WithCancel(t.log.WithContext(context.Background()))
	t.cancel = cancel
	if t.started {
		t.client = t.newClient()
	}
	t.started = true
	go t.run(ctx, t.client)
	return nil
}

func (t *Telegram) run(ctx context.Context, client *telegram.Client) {
	for ctx.Err() == nil {
		err := client.Run(ctx, func(ctx context.Context) error {
			t.running.Store(true)
			defer t.running.Store(false)
			status, err := client.Auth().Status(ctx)
			if err != nil {
				return err
			}
			if status.Authorized {
				t.onAuthorized(status.User)
			} else if t.IsLoggedIn() {
				// Logged out from another device
				t.clearLogin()
				t.emitState("logged_out", "")
			} else {
				t.emitState("needs_login", "")
			}
			select {
			case <-t.authorized:
			case <-ctx.Done():
				return ctx.Err()
			}
			t.emitState("connected", "")
			go t.syncDialogs(ctx)
			return t.updates.Run(ctx, client.API(), t.selfID.Load(), updates.AuthOptions{})
		})
		if ctx.Err() != nil {
			return
		}
		t.log.Warn().Err(err).Msg("Telegram connection ended, reconnecting")
		t.emitState("reconnecting", errString(err))
		select {
		case <-time.After(5 * time.Second):
		case <-ctx.Done():
		}
	}
}

func (t *Telegram) onAuthorized(self *tg.User) {
	if self == nil {
		return
	}
	t.selfID.Store(self.ID)
	t.store.setSelfID(self.ID)
	t.learnUser(self)
	t.emit(map[string]any{"type": "self", "id": strconv.FormatInt(self.ID, 10), "name": userName(self)})
	t.authorizedOnce.Do(func() { close(t.authorized) })
}

// Disconnect closes the connection; Connect can be called again.
func (t *Telegram) Disconnect() {
	t.lock.Lock()
	cancel := t.cancel
	t.cancel = nil
	t.lock.Unlock()
	if cancel != nil {
		cancel()
	}
	t.emitState("disconnected", "")
}

// Logout ends the session on Telegram's side and deletes it from the phone.
func (t *Telegram) Logout() error {
	if t.running.Load() && t.IsLoggedIn() {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		_, _ = t.client.API().AuthLogOut(ctx)
		cancel()
	}
	t.Disconnect()
	t.clearLogin()
	return nil
}

func (t *Telegram) clearLogin() {
	t.selfID.Store(0)
	t.store.clear()
	_ = os.Remove(filepath.Join(t.dataDir, "telegram-session.json"))
}

// --- login ---

func (t *Telegram) waitRunning(ctx context.Context) error {
	if err := t.Connect(); err != nil {
		return err
	}
	for !t.running.Load() {
		select {
		case <-ctx.Done():
			return errors.New("couldn't reach Telegram")
		case <-time.After(100 * time.Millisecond):
		}
	}
	return nil
}

// telegramUpdateHandler passes updates to the updates manager, except the one telling that a QR login was accepted,
// which arrives before the account is logged in.
type telegramUpdateHandler struct{ t *Telegram }

func (h telegramUpdateHandler) Handle(ctx context.Context, u tg.UpdatesClass) error {
	if short, ok := u.(*tg.UpdateShort); ok {
		if _, ok := short.Update.(*tg.UpdateLoginToken); ok {
			h.t.lock.Lock()
			accepted := h.t.qrAccepted
			h.t.lock.Unlock()
			if accepted != nil {
				select {
				case accepted <- struct{}{}:
				default:
				}
			}
			return nil
		}
	}
	return h.t.updates.Handle(ctx, u)
}

// StartQRLogin shows a QR code (as a "pairing" event with "qr", a tg://login link) to scan in Telegram on another
// device, or to open in the Telegram app on this phone. The code renews until it's accepted; "self" follows, or a
// "password" login step for two-step verification.
func (t *Telegram) StartQRLogin() error {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	err := t.waitRunning(ctx)
	cancel()
	if err != nil {
		return err
	}
	t.CancelQRLogin()
	qrCtx, qrCancel := context.WithTimeout(context.Background(), 10*time.Minute)
	accepted := make(chan struct{}, 1)
	t.lock.Lock()
	t.qrCancel, t.qrAccepted = qrCancel, accepted
	t.lock.Unlock()
	go func() {
		defer qrCancel()
		a, err := t.client.QR().Auth(qrCtx, accepted, func(ctx context.Context, token qrlogin.Token) error {
			t.emit(map[string]any{"type": "pairing", "qr": token.URL()})
			return nil
		})
		switch {
		case qrCtx.Err() != nil:
			return
		case tgerr.Is(err, "SESSION_PASSWORD_NEEDED"), errors.Is(err, auth.ErrPasswordAuthNeeded):
			hint := ""
			if pw, err := t.client.API().AccountGetPassword(context.Background()); err == nil {
				hint = pw.Hint
			}
			t.emit(map[string]any{"type": "login", "step": "password", "hint": hint})
		case err != nil:
			// Accepting the code can authorise this session even when collecting the login then fails (e.g.
			// AUTH_TOKEN_EXPIRED), so check before giving up
			if status, statusErr := t.client.Auth().Status(context.Background()); statusErr == nil && status.Authorized {
				t.onAuthorized(status.User)
				return
			}
			t.emitState("error", friendlyTelegramError(err).Error())
		default:
			t.finishLogin(a)
		}
	}()
	return nil
}

// CancelQRLogin stops renewing the QR code.
func (t *Telegram) CancelQRLogin() {
	t.lock.Lock()
	cancel := t.qrCancel
	t.qrCancel, t.qrAccepted = nil, nil
	t.lock.Unlock()
	if cancel != nil {
		cancel()
	}
}

// StartLogin sends a login code to the phone number (international format); a login event with step "code" follows,
// or "self" when no code was needed. Without a number it starts a QR login instead.
func (t *Telegram) StartLogin(phone string) error {
	if strings.TrimSpace(phone) == "" {
		return t.StartQRLogin()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := t.waitRunning(ctx); err != nil {
		return err
	}
	sent, err := t.client.Auth().SendCode(ctx, phone, auth.SendCodeOptions{})
	if err != nil {
		return friendlyTelegramError(err)
	}
	switch s := sent.(type) {
	case *tg.AuthSentCode:
		t.loginPhone, t.loginCodeHash = phone, s.PhoneCodeHash
		t.emit(map[string]any{"type": "debug", "codeType": fmt.Sprintf("%T", s.Type), "nextType": fmt.Sprintf("%T", s.NextType)})
		if _, ok := s.Type.(*tg.AuthSentCodeTypeSetUpEmailRequired); ok {
			return errors.New("Telegram wants this account to have a login email first; add one in the Telegram app (Settings → Privacy and Security), then try again")
		}
		if _, ok := s.Type.(*tg.AuthSentCodeTypeFirebaseSMS); ok {
			// That SMS is only sent after a Google Play check that only Telegram's own app can pass; ask for the
			// next way instead (normally a plain SMS)
			if resent, err := t.client.API().AuthResendCode(ctx, &tg.AuthResendCodeRequest{PhoneNumber: phone, PhoneCodeHash: s.PhoneCodeHash}); err == nil {
				if next, ok := resent.(*tg.AuthSentCode); ok {
					s = next
					t.loginCodeHash = next.PhoneCodeHash
					t.emit(map[string]any{"type": "debug", "resentAs": fmt.Sprintf("%T", next.Type)})
				}
			} else {
				t.emit(map[string]any{"type": "debug", "resendError": err.Error()})
			}
		}
		t.emit(map[string]any{"type": "login", "step": "code", "codeType": codeTypeName(s.Type)})
	case *tg.AuthSentCodeSuccess:
		if a, ok := s.Authorization.(*tg.AuthAuthorization); ok {
			t.finishLogin(a)
		}
	default:
		return fmt.Errorf("unexpected answer from Telegram (%T)", sent)
	}
	return nil
}

// SubmitCode answers the "code" step; a wrong code repeats it with an error, two-step verification asks for the
// password next.
func (t *Telegram) SubmitCode(code string) error {
	if t.loginCodeHash == "" {
		return errors.New("no login in progress")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	a, err := t.client.Auth().SignIn(ctx, t.loginPhone, strings.TrimSpace(code), t.loginCodeHash)
	switch {
	case errors.Is(err, auth.ErrPasswordAuthNeeded):
		hint := ""
		if pw, err := t.client.API().AccountGetPassword(ctx); err == nil {
			hint = pw.Hint
		}
		t.emit(map[string]any{"type": "login", "step": "password", "hint": hint})
		return nil
	case errors.Is(err, auth.ErrPhoneCodeInvalid):
		t.emit(map[string]any{"type": "login", "step": "code", "error": "invalid"})
		return nil
	case errors.Is(err, &auth.SignUpRequired{}):
		return errors.New("this phone number has no Telegram account yet; sign up in the Telegram app first")
	case err != nil:
		return friendlyTelegramError(err)
	}
	t.finishLogin(a)
	return nil
}

// SubmitPassword answers the "password" step (two-step verification).
func (t *Telegram) SubmitPassword(password string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	a, err := t.client.Auth().Password(ctx, password)
	if errors.Is(err, auth.ErrPasswordInvalid) {
		t.emit(map[string]any{"type": "login", "step": "password", "error": "invalid"})
		return nil
	} else if err != nil {
		return friendlyTelegramError(err)
	}
	t.finishLogin(a)
	return nil
}

func (t *Telegram) finishLogin(a *tg.AuthAuthorization) {
	t.loginPhone, t.loginCodeHash = "", ""
	t.CancelQRLogin()
	if user, ok := a.User.(*tg.User); ok {
		t.onAuthorized(user)
	}
}

func codeTypeName(codeType tg.AuthSentCodeTypeClass) string {
	switch codeType.(type) {
	case *tg.AuthSentCodeTypeApp:
		return "app"
	case *tg.AuthSentCodeTypeSMS, *tg.AuthSentCodeTypeSMSWord, *tg.AuthSentCodeTypeSMSPhrase, *tg.AuthSentCodeTypeFirebaseSMS:
		return "sms"
	case *tg.AuthSentCodeTypeCall, *tg.AuthSentCodeTypeFlashCall, *tg.AuthSentCodeTypeMissedCall:
		return "call"
	case *tg.AuthSentCodeTypeEmailCode:
		return "email"
	case *tg.AuthSentCodeTypeFragmentSMS:
		return "fragment"
	}
	return "other"
}

func friendlyTelegramError(err error) error {
	msg := err.Error()
	switch {
	case strings.Contains(msg, "PHONE_NUMBER_INVALID"):
		return errors.New("Telegram doesn't recognise this phone number")
	case strings.Contains(msg, "PHONE_CODE_EXPIRED"):
		return errors.New("the code expired; start again")
	case strings.Contains(msg, "FLOOD_WAIT"), strings.Contains(msg, "FLOOD"):
		return errors.New("too many attempts; wait a while and try again")
	case strings.Contains(msg, "API_ID_INVALID"):
		return errors.New("the app's Telegram API credentials are invalid")
	}
	return err
}

// --- chats ---

// syncDialogs emits the most recent chats with their newest message.
func (t *Telegram) syncDialogs(ctx context.Context) {
	offsetPeer := tg.InputPeerClass(&tg.InputPeerEmpty{})
	offsetID, offsetDate := 0, 0
	for page := 0; page < telegramDialogPages; page++ {
		resp, err := t.client.API().MessagesGetDialogs(ctx, &tg.MessagesGetDialogsRequest{
			OffsetPeer: offsetPeer, OffsetID: offsetID, OffsetDate: offsetDate, Limit: telegramDialogPageSize,
		})
		if err != nil {
			t.log.Err(err).Msg("Failed to load Telegram chats")
			return
		}
		var dialogs []tg.DialogClass
		var messages []tg.MessageClass
		var users []tg.UserClass
		var chats []tg.ChatClass
		more := false
		switch d := resp.(type) {
		case *tg.MessagesDialogs:
			dialogs, messages, users, chats = d.Dialogs, d.Messages, d.Users, d.Chats
		case *tg.MessagesDialogsSlice:
			dialogs, messages, users, chats = d.Dialogs, d.Messages, d.Users, d.Chats
			more = len(d.Dialogs) == telegramDialogPageSize
		default:
			return
		}
		t.learn(users, chats)
		newest := map[string]tg.MessageClass{}
		for _, raw := range messages {
			switch msg := raw.(type) {
			case *tg.Message:
				newest[peerKey(msg.PeerID)+"/"+strconv.Itoa(msg.ID)] = msg
			case *tg.MessageService:
				newest[peerKey(msg.PeerID)+"/"+strconv.Itoa(msg.ID)] = msg
			}
		}
		for _, raw := range dialogs {
			dialog, ok := raw.(*tg.Dialog)
			if !ok {
				continue
			}
			key := peerKey(dialog.Peer)
			top := newest[key+"/"+strconv.Itoa(dialog.TopMessage)]
			lastActivity := int64(0)
			switch msg := top.(type) {
			case *tg.Message:
				lastActivity = int64(msg.Date) * 1000
			case *tg.MessageService:
				lastActivity = int64(msg.Date) * 1000
			}
			t.emitThread(key, lastActivity, dialog.UnreadCount > 0 || dialog.UnreadMark)
			if top != nil {
				t.emitMessage(top)
			}
		}
		if !more || len(messages) == 0 {
			return
		}
		// The next page starts after the oldest dialog's top message
		last := dialogs[len(dialogs)-1].(*tg.Dialog)
		offsetPeer = t.inputPeer(peerKey(last.Peer))
		offsetID = last.TopMessage
		switch top := newest[peerKey(last.Peer)+"/"+strconv.Itoa(last.TopMessage)].(type) {
		case *tg.Message:
			offsetDate = top.Date
		case *tg.MessageService:
			offsetDate = top.Date
		}
	}
}

const (
	telegramDialogPages    = 3
	telegramDialogPageSize = 100
	telegramHistoryPage    = 30
)

func (t *Telegram) emitThread(key string, lastActivity int64, unread bool) {
	name, isGroup := t.store.peerName(key), !strings.HasPrefix(key, "u")
	lastRead := lastActivity
	if unread {
		lastRead = 0
	}
	t.emit(map[string]any{
		"type": "thread", "key": key, "name": name, "isGroup": isGroup, "isEncrypted": false,
		"lastActivityMs": lastActivity, "lastReadMs": lastRead,
	})
	if !isGroup {
		t.emit(map[string]any{"type": "participant", "threadKey": key, "contactId": key[1:]})
	}
}

// learn remembers how to address users and chats (access hashes) and emits users as contacts.
func (t *Telegram) learn(users []tg.UserClass, chats []tg.ChatClass) {
	for _, raw := range users {
		if user, ok := raw.(*tg.User); ok {
			t.learnUser(user)
		}
	}
	for _, raw := range chats {
		switch chat := raw.(type) {
		case *tg.Chat:
			t.store.setPeer("g"+strconv.FormatInt(chat.ID, 10), 0, chat.Title)
		case *tg.Channel:
			if chat.AccessHash != 0 {
				t.store.setPeer("c"+strconv.FormatInt(chat.ID, 10), chat.AccessHash, chat.Title)
			}
		}
	}
}

func (t *Telegram) learnEntities(e tg.Entities) {
	for _, user := range e.Users {
		t.learnUser(user)
	}
	for _, chat := range e.Chats {
		t.store.setPeer("g"+strconv.FormatInt(chat.ID, 10), 0, chat.Title)
	}
	for _, channel := range e.Channels {
		if channel.AccessHash != 0 {
			t.store.setPeer("c"+strconv.FormatInt(channel.ID, 10), channel.AccessHash, channel.Title)
		}
	}
}

func (t *Telegram) learnUser(user *tg.User) {
	if user == nil {
		return
	}
	id := strconv.FormatInt(user.ID, 10)
	if user.AccessHash != 0 && !user.Min {
		t.store.setPeer("u"+id, user.AccessHash, userName(user))
	}
	if name := userName(user); name != "" {
		t.emit(map[string]any{"type": "contact", "id": id, "name": name, "firstName": firstNonEmpty(user.FirstName, name)})
	}
}

func userName(user *tg.User) string {
	name := strings.TrimSpace(user.FirstName + " " + user.LastName)
	if name == "" {
		name = user.Username
	}
	if name == "" && user.Phone != "" {
		name = "+" + user.Phone
	}
	if user.Deleted {
		name = "Deleted account"
	}
	return name
}

func peerKey(peer tg.PeerClass) string {
	switch p := peer.(type) {
	case *tg.PeerUser:
		return "u" + strconv.FormatInt(p.UserID, 10)
	case *tg.PeerChat:
		return "g" + strconv.FormatInt(p.ChatID, 10)
	case *tg.PeerChannel:
		return "c" + strconv.FormatInt(p.ChannelID, 10)
	}
	return ""
}

func peerSenderID(peer tg.PeerClass) string {
	switch p := peer.(type) {
	case *tg.PeerUser:
		return strconv.FormatInt(p.UserID, 10)
	case *tg.PeerChat:
		return "g" + strconv.FormatInt(p.ChatID, 10)
	case *tg.PeerChannel:
		return "c" + strconv.FormatInt(p.ChannelID, 10)
	}
	return ""
}

// inputPeer addresses a chat by thread key; nil if its access hash isn't known.
func (t *Telegram) inputPeer(key string) tg.InputPeerClass {
	if len(key) < 2 {
		return nil
	}
	id, err := strconv.ParseInt(key[1:], 10, 64)
	if err != nil {
		return nil
	}
	if key == "u"+strconv.FormatInt(t.selfID.Load(), 10) {
		return &tg.InputPeerSelf{}
	}
	switch key[0] {
	case 'u':
		if hash, ok := t.store.accessHash(key); ok {
			return &tg.InputPeerUser{UserID: id, AccessHash: hash}
		}
	case 'g':
		return &tg.InputPeerChat{ChatID: id}
	case 'c':
		if hash, ok := t.store.accessHash(key); ok {
			return &tg.InputPeerChannel{ChannelID: id, AccessHash: hash}
		}
	}
	return nil
}

func (t *Telegram) peerFor(threadKey string) (tg.InputPeerClass, error) {
	if peer := t.inputPeer(threadKey); peer != nil {
		return peer, nil
	}
	return nil, errors.New("unknown chat")
}

// splitTelegramMessageID splits "<thread key>/<id>" into the thread key and the message's number.
func splitTelegramMessageID(messageID string) (string, int, error) {
	idx := strings.LastIndexByte(messageID, '/')
	if idx < 0 {
		return "", 0, errors.New("invalid message ID")
	}
	n, err := strconv.Atoi(messageID[idx+1:])
	return messageID[:idx], n, err
}

// --- messages ---

func (t *Telegram) emitMessage(raw tg.MessageClass) {
	if service, ok := raw.(*tg.MessageService); ok {
		t.emitServiceMessage(service)
		return
	}
	msg, ok := raw.(*tg.Message)
	if !ok {
		return
	}
	key := peerKey(msg.PeerID)
	if key == "" {
		return
	}
	id := key + "/" + strconv.Itoa(msg.ID)
	timestamp := int64(msg.Date) * 1000
	t.msgDates.Store(id, timestamp)
	if !strings.HasPrefix(key, "c") {
		t.msgThreads.Store(msg.ID, key)
	}
	sender := ""
	switch {
	case msg.Out:
		sender = strconv.FormatInt(t.selfID.Load(), 10)
	case msg.FromID != nil:
		sender = peerSenderID(msg.FromID)
	default:
		sender = peerSenderID(msg.PeerID)
	}
	event := map[string]any{
		"type": "message", "threadKey": key, "id": id, "senderId": sender, "text": msg.Message,
		"kind": "text", "timestampMs": timestamp,
	}
	if rid, ok := t.sentRandomIDs.LoadAndDelete(id); ok {
		event["otid"] = rid
	}
	if replyTo := telegramReplyTo(key, msg); replyTo != "" {
		event["replyToId"] = replyTo
	}
	if msg.Media != nil {
		t.describeMedia(key, id, msg, event)
	}
	t.emit(event)
	if reactions, ok := msg.GetReactions(); ok {
		t.emitReactions(key, id, reactions)
	}
}

// emitReactions turns a message's recent reactions into reaction events, including removals since the last ones.
func (t *Telegram) emitReactions(key, id string, reactions tg.MessageReactions) {
	current := map[string]string{}
	for _, r := range reactions.RecentReactions {
		if emoji, ok := r.Reaction.(*tg.ReactionEmoji); ok {
			current[peerSenderID(r.PeerID)] = emoji.Emoticon
		}
	}
	previous := map[string]string{}
	if raw, ok := t.reactions.Load(id); ok {
		previous = raw.(map[string]string)
	}
	t.reactions.Store(id, current)
	for sender, emoji := range current {
		if previous[sender] != emoji {
			t.emit(map[string]any{"type": "reaction", "threadKey": key, "targetId": id, "senderId": sender, "emoji": emoji})
		}
	}
	for sender := range previous {
		if _, ok := current[sender]; !ok {
			t.emit(map[string]any{"type": "reaction", "threadKey": key, "targetId": id, "senderId": sender, "emoji": ""})
		}
	}
}

// RequestOlder loads a page of messages older than beforeMessageID (the newest page when it's empty); they arrive
// as message events, then a range event.
func (t *Telegram) RequestOlder(threadKey, beforeMessageID string) error {
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return err
	}
	offsetID := 0
	if beforeMessageID != "" {
		if _, offsetID, err = splitTelegramMessageID(beforeMessageID); err != nil {
			return err
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	resp, err := t.client.API().MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{
		Peer: peer, OffsetID: offsetID, Limit: telegramHistoryPage,
	})
	if err != nil {
		return err
	}
	var messages []tg.MessageClass
	switch r := resp.(type) {
	case *tg.MessagesMessages:
		t.learn(r.Users, r.Chats)
		messages = r.Messages
	case *tg.MessagesMessagesSlice:
		t.learn(r.Users, r.Chats)
		messages = r.Messages
	case *tg.MessagesChannelMessages:
		t.learn(r.Users, r.Chats)
		messages = r.Messages
	}
	for _, msg := range messages {
		t.emitMessage(msg)
	}
	t.emit(map[string]any{"type": "range", "threadKey": threadKey, "hasMoreBefore": len(messages) == telegramHistoryPage})
	return nil
}

func randomID() int64 {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return int64(binary.LittleEndian.Uint64(b[:]) &^ (1 << 63))
}

// SendText sends a message and returns the ID its echo carries as otid.
func (t *Telegram) SendText(threadKey, text string) (string, error) {
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	rid := randomID()
	resp, err := t.client.API().MessagesSendMessage(ctx, &tg.MessagesSendMessageRequest{Peer: peer, Message: text, RandomID: rid})
	if err != nil {
		return "", friendlyTelegramError(err)
	}
	t.echoSent(ctx, threadKey, rid, text, resp)
	return strconv.FormatInt(rid, 10), nil
}

// echoSent emits our own just-sent message with the random ID as otid (so the app matches it to what it showed),
// then lets the update manager see the response so its sequence stays in step.
func (t *Telegram) echoSent(ctx context.Context, threadKey string, rid int64, text string, resp tg.UpdatesClass) {
	msgID, date := 0, int(time.Now().Unix())
	switch r := resp.(type) {
	case *tg.UpdateShortSentMessage:
		msgID, date = r.ID, r.Date
	case *tg.Updates:
		for _, u := range r.Updates {
			if idUpdate, ok := u.(*tg.UpdateMessageID); ok && idUpdate.RandomID == rid {
				msgID = idUpdate.ID
			}
		}
	}
	if msgID != 0 {
		id := threadKey + "/" + strconv.Itoa(msgID)
		t.msgDates.Store(id, int64(date)*1000)
		if !strings.HasPrefix(threadKey, "c") {
			t.msgThreads.Store(msgID, threadKey)
		}
		event := map[string]any{
			"type": "message", "threadKey": threadKey, "id": id, "otid": strconv.FormatInt(rid, 10),
			"senderId": strconv.FormatInt(t.selfID.Load(), 10), "text": text, "kind": "text", "timestampMs": int64(date) * 1000,
		}
		if replyTo, ok := t.pendingReplies.LoadAndDelete(rid); ok {
			event["replyToId"] = replyTo
		}
		t.emit(event)
	}
	if _, short := resp.(*tg.UpdateShortSentMessage); !short {
		_ = t.updates.Handle(ctx, resp)
	}
}

// handleSent passes a send response to the update manager, which emits the message; the random ID goes along as
// its otid so the app replaces what it showed while sending.
func (t *Telegram) handleSent(ctx context.Context, threadKey string, rid int64, resp tg.UpdatesClass) {
	if updates, ok := resp.(*tg.Updates); ok {
		for _, u := range updates.Updates {
			if idUpdate, ok := u.(*tg.UpdateMessageID); ok && idUpdate.RandomID == rid {
				t.sentRandomIDs.Store(threadKey+"/"+strconv.Itoa(idUpdate.ID), strconv.FormatInt(rid, 10))
			}
		}
	}
	_ = t.updates.Handle(ctx, resp)
}

// MarkRead marks a chat read up to a message.
func (t *Telegram) MarkRead(threadKey, messageID string) error {
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return err
	}
	_, maxID, err := splitTelegramMessageID(messageID)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if channel, ok := peer.(*tg.InputPeerChannel); ok {
		_, err = t.client.API().ChannelsReadHistory(ctx, &tg.ChannelsReadHistoryRequest{
			Channel: &tg.InputChannel{ChannelID: channel.ChannelID, AccessHash: channel.AccessHash}, MaxID: maxID,
		})
		return err
	}
	_, err = t.client.API().MessagesReadHistory(ctx, &tg.MessagesReadHistoryRequest{Peer: peer, MaxID: maxID})
	return err
}

// SetTyping shows or stops the typing indicator in a chat.
func (t *Telegram) SetTyping(threadKey string, isTyping bool) error {
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return err
	}
	var action tg.SendMessageActionClass = &tg.SendMessageCancelAction{}
	if isTyping {
		action = &tg.SendMessageTypingAction{}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_, err = t.client.API().MessagesSetTyping(ctx, &tg.MessagesSetTypingRequest{Peer: peer, Action: action})
	return err
}

// SendReaction reacts to a message; an empty emoji removes the reaction.
func (t *Telegram) SendReaction(threadKey, messageID, emoji string) error {
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return err
	}
	_, msgID, err := splitTelegramMessageID(messageID)
	if err != nil {
		return err
	}
	var reaction []tg.ReactionClass
	if emoji != "" {
		reaction = []tg.ReactionClass{&tg.ReactionEmoji{Emoticon: emoji}}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	resp, err := t.client.API().MessagesSendReaction(ctx, &tg.MessagesSendReactionRequest{Peer: peer, MsgID: msgID, Reaction: reaction})
	if err != nil {
		return friendlyTelegramError(err)
	}
	return t.updates.Handle(ctx, resp)
}

// --- updates ---

func (t *Telegram) registerHandlers(d tg.UpdateDispatcher) {
	d.OnNewMessage(func(ctx context.Context, e tg.Entities, u *tg.UpdateNewMessage) error {
		t.learnEntities(e)
		t.onNewMessage(u.Message)
		return nil
	})
	d.OnNewChannelMessage(func(ctx context.Context, e tg.Entities, u *tg.UpdateNewChannelMessage) error {
		t.learnEntities(e)
		t.onNewMessage(u.Message)
		return nil
	})
	d.OnEditMessage(func(ctx context.Context, e tg.Entities, u *tg.UpdateEditMessage) error {
		t.learnEntities(e)
		t.onEdit(u.Message)
		return nil
	})
	d.OnEditChannelMessage(func(ctx context.Context, e tg.Entities, u *tg.UpdateEditChannelMessage) error {
		t.learnEntities(e)
		t.onEdit(u.Message)
		return nil
	})
	d.OnDeleteMessages(func(ctx context.Context, e tg.Entities, u *tg.UpdateDeleteMessages) error {
		for _, msgID := range u.Messages {
			if key, ok := t.msgThreads.Load(msgID); ok {
				t.emit(map[string]any{"type": "unsend", "threadKey": key, "messageId": key.(string) + "/" + strconv.Itoa(msgID)})
			}
		}
		return nil
	})
	d.OnDeleteChannelMessages(func(ctx context.Context, e tg.Entities, u *tg.UpdateDeleteChannelMessages) error {
		key := "c" + strconv.FormatInt(u.ChannelID, 10)
		for _, msgID := range u.Messages {
			t.emit(map[string]any{"type": "unsend", "threadKey": key, "messageId": key + "/" + strconv.Itoa(msgID)})
		}
		return nil
	})
	d.OnUserTyping(func(ctx context.Context, e tg.Entities, u *tg.UpdateUserTyping) error {
		sender := strconv.FormatInt(u.UserID, 10)
		t.onTyping("u"+sender, sender, u.Action)
		return nil
	})
	d.OnChatUserTyping(func(ctx context.Context, e tg.Entities, u *tg.UpdateChatUserTyping) error {
		t.onTyping("g"+strconv.FormatInt(u.ChatID, 10), peerSenderID(u.FromID), u.Action)
		return nil
	})
	d.OnChannelUserTyping(func(ctx context.Context, e tg.Entities, u *tg.UpdateChannelUserTyping) error {
		t.onTyping("c"+strconv.FormatInt(u.ChannelID, 10), peerSenderID(u.FromID), u.Action)
		return nil
	})
	d.OnReadHistoryOutbox(func(ctx context.Context, e tg.Entities, u *tg.UpdateReadHistoryOutbox) error {
		// Only 1:1 chats say who read; groups only that someone did
		user, ok := u.Peer.(*tg.PeerUser)
		if !ok {
			return nil
		}
		key := peerKey(u.Peer)
		watermark := time.Now().UnixMilli()
		if date, ok := t.msgDates.Load(key + "/" + strconv.Itoa(u.MaxID)); ok {
			watermark = date.(int64)
		}
		t.emit(map[string]any{"type": "read", "threadKey": key, "contactId": strconv.FormatInt(user.UserID, 10), "watermarkMs": watermark})
		return nil
	})
	d.OnPhoneCall(func(ctx context.Context, e tg.Entities, u *tg.UpdatePhoneCall) error {
		t.learnEntities(e)
		t.onPhoneCall(u.PhoneCall)
		return nil
	})
	d.OnMessageReactions(func(ctx context.Context, e tg.Entities, u *tg.UpdateMessageReactions) error {
		key := peerKey(u.Peer)
		t.emitReactions(key, key+"/"+strconv.Itoa(u.MsgID), u.Reactions)
		return nil
	})
}

func (t *Telegram) onNewMessage(raw tg.MessageClass) {
	if service, ok := raw.(*tg.MessageService); ok {
		if key := peerKey(service.PeerID); key != "" && t.emitServiceMessage(service) {
			t.emitThread(key, int64(service.Date)*1000, !service.Out)
		}
		return
	}
	msg, ok := raw.(*tg.Message)
	if !ok {
		return
	}
	key := peerKey(msg.PeerID)
	// A message from someone typing stops their indicator
	if timer, ok := t.typing.LoadAndDelete(key + "|" + peerSenderID(msg.FromID)); ok {
		timer.(*time.Timer).Stop()
	}
	t.emitThread(key, int64(msg.Date)*1000, !msg.Out)
	t.emitMessage(msg)
}

func (t *Telegram) onEdit(raw tg.MessageClass) {
	msg, ok := raw.(*tg.Message)
	if !ok {
		return
	}
	key := peerKey(msg.PeerID)
	id := key + "/" + strconv.Itoa(msg.ID)
	if reactions, ok := msg.GetReactions(); ok {
		t.emitReactions(key, id, reactions)
	}
	// Telegram also sends edits for reaction and link preview changes; only text changes are edits
	if msg.EditDate == 0 || msg.EditHide {
		return
	}
	t.emit(map[string]any{"type": "edit", "threadKey": key, "messageId": id, "text": msg.Message})
}

// onTyping emits typing for a chat; Telegram repeats the action every few seconds while typing and sends no stop,
// so the indicator ends after a quiet period.
func (t *Telegram) onTyping(key, sender string, action tg.SendMessageActionClass) {
	if key == "" || sender == "" {
		return
	}
	_, cancelled := action.(*tg.SendMessageCancelAction)
	timerKey := key + "|" + sender
	if old, ok := t.typing.LoadAndDelete(timerKey); ok {
		old.(*time.Timer).Stop()
	}
	t.emit(map[string]any{"type": "typing", "threadKey": key, "senderId": sender, "isTyping": !cancelled})
	if !cancelled {
		t.typing.Store(timerKey, time.AfterFunc(6*time.Second, func() {
			t.typing.Delete(timerKey)
			t.emit(map[string]any{"type": "typing", "threadKey": key, "senderId": sender, "isTyping": false})
		}))
	}
}

func (t *Telegram) emitState(state, err string) {
	t.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (t *Telegram) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && t.listener != nil {
		t.listener.OnEvent(string(data))
	}
}

// SearchPeople finds Telegram users by name or @username (Telegram's global search).
func (t *Telegram) SearchPeople(query string) (string, error) {
	client := t.client
	if client == nil {
		return "", errors.New("Telegram isn't connected")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	found, err := client.API().ContactsSearch(ctx, &tg.ContactsSearchRequest{
		Q: strings.TrimPrefix(strings.TrimSpace(query), "@"), Limit: 20,
	})
	if err != nil {
		return "", friendlyTelegramError(err)
	}
	var people []foundPerson
	for _, u := range found.Users {
		user, ok := u.(*tg.User)
		if !ok || user.Self || user.Deleted || user.AccessHash == 0 {
			continue
		}
		// Remembers the user's access hash, which sending to them needs
		t.learnUser(user)
		handle := ""
		if user.Username != "" {
			handle = "@" + user.Username
		}
		people = append(people, foundPerson{ID: strconv.FormatInt(user.ID, 10),
			Name: firstNonEmpty(userName(user), handle), Handle: handle})
	}
	return peopleJSON(people)
}
