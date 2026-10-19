package hydbridge

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCompanionReg"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

// WhatsApp is one WhatsApp account linked as a companion device of the user's phone, running in the app.
// Events use the same JSON shapes as Messenger's (thread, contact, message, attachment, reaction, typing, read,
// state, self) plus "pairing" with a QR code or pairing code; thread keys are chat JIDs.
type WhatsApp struct {
	listener Listener
	log      zerolog.Logger
	dataDir  string

	container *sqlstore.Container
	client    *whatsmeow.Client

	lock   sync.Mutex
	cancel context.CancelFunc

	seenEvents sync.Map
}

// NewWhatsApp opens (or creates) the device store in dataDir.
func NewWhatsApp(dataDir string, listener Listener) (*WhatsApp, error) {
	if dataDir == "" {
		return nil, errors.New("data directory is required")
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "whatsapp").Logger().Level(zerolog.InfoLevel)
	address := "file:" + filepath.Join(dataDir, "whatsapp.db") + "?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=5000"
	container, err := sqlstore.New(context.Background(), "sqlite3", address, waLog.Zerolog(log.With().Str("component", "whatsmeow-store").Logger()))
	if err != nil {
		return nil, fmt.Errorf("failed to open WhatsApp store: %w", err)
	}
	// Shown on the phone under "Linked devices"
	store.SetOSInfo("HowYouDoin", [3]uint32{1, 0, 0})
	// Ask for a full history sync when linking (like mautrix-whatsapp's request_full_sync); only applies to new links
	store.DeviceProps.RequireFullSync = proto.Bool(true)
	if store.DeviceProps.HistorySyncConfig == nil {
		store.DeviceProps.HistorySyncConfig = &waCompanionReg.DeviceProps_HistorySyncConfig{}
	}
	store.DeviceProps.HistorySyncConfig.FullSyncDaysLimit = proto.Uint32(365)
	store.DeviceProps.HistorySyncConfig.FullSyncSizeMbLimit = proto.Uint32(500)
	return &WhatsApp{listener: listener, log: log, dataDir: dataDir, container: container}, nil
}

// IsLinked tells whether this phone is already linked to a WhatsApp account.
func (w *WhatsApp) IsLinked() bool {
	device, err := w.container.GetFirstDevice(context.Background())
	return err == nil && device.ID != nil
}

// Connect connects a linked device, or starts linking: with an empty phone number it emits QR codes
// ({"type":"pairing","qr":...}), otherwise a pairing code to type on the phone ({"type":"pairing","code":...}).
// Linking follows mautrix-whatsapp: a login client without auto-reconnect, then after the pairing succeeded and
// WhatsApp asked for a reconnect (stream error 515), a regular client for the saved device.
func (w *WhatsApp) Connect(phone string) error {
	w.lock.Lock()
	defer w.lock.Unlock()
	if w.client != nil {
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(context.Background())
	w.cancel = cancel
	device, err := w.container.GetFirstDevice(ctx)
	if err != nil {
		return err
	}
	if device.ID != nil {
		return w.connectLinked(device)
	}
	return w.startLinking(ctx, device, phone)
}

// connectLinked runs the normal client for an already linked device. Call with w.lock held.
func (w *WhatsApp) connectLinked(device *store.Device) error {
	client := whatsmeow.NewClient(device, waLog.Zerolog(w.log.With().Str("component", "whatsmeow").Logger()))
	client.EnableAutoReconnect = true
	client.AddEventHandler(w.handleEvent)
	w.client = client
	return client.Connect()
}

func (w *WhatsApp) startLinking(ctx context.Context, device *store.Device, phone string) error {
	// One client for linking and afterwards: WhatsApp sends the first history batch right after pairing, on this
	// connection, so its events must go through the regular handler too. whatsmeow reconnects by itself after
	// the post-pairing restart (stream error 515).
	client := whatsmeow.NewClient(device, waLog.Zerolog(w.log.With().Str("component", "whatsmeow").Logger()))
	client.EnableAutoReconnect = false
	firstQR := make(chan struct{})
	var once sync.Once
	var paired *events.PairSuccess
	var qrTimer *time.Timer
	fail := func(reason string) {
		client.Disconnect()
		w.emitState("pairing_failed", reason)
	}
	client.AddEventHandler(w.handleEvent)
	client.AddEventHandler(func(rawEvt any) {
		switch evt := rawEvt.(type) {
		case *events.QR:
			once.Do(func() { close(firstQR) })
			if phone == "" {
				// Like the official clients: the first code is valid for a minute, the rest for 20 seconds each
				codes := evt.Codes
				var show func(i int)
				show = func(i int) {
					if i >= len(codes) || paired != nil {
						if paired == nil {
							fail("timeout")
						}
						return
					}
					timeout := 20 * time.Second
					if i == 0 {
						timeout = time.Minute
					}
					w.emit(map[string]any{"type": "pairing", "qr": codes[i], "timeoutMs": timeout.Milliseconds()})
					qrTimer = time.AfterFunc(timeout, func() { show(i + 1) })
				}
				show(0)
			}
		case *events.PairPasskeyConfirmation:
			if evt.SkipHandoffUX {
				if err := client.SendPasskeyConfirmation(ctx); err != nil {
					fail("passkey confirmation failed: " + err.Error())
				}
			}
		case *events.PairPasskeyRequest:
			fail("WhatsApp asked for a passkey, which isn't supported yet")
		case *events.PairPasskeyError:
			fail(evt.Error.Error())
		case *events.PairSuccess:
			paired = evt
			if qrTimer != nil {
				qrTimer.Stop()
			}
			client.EnableAutoReconnect = true
			w.emitState("paired", "")
		case *events.PairError:
			fail(evt.Error.Error())
		case *events.Disconnected:
			if paired == nil {
				w.emitState("pairing_failed", "disconnected before linking finished")
			}
		case *events.ClientOutdated:
			fail("WhatsApp says this client version is outdated")
		}
	})
	w.client = client
	if err := client.Connect(); err != nil {
		return err
	}
	select {
	case <-firstQR:
	case <-time.After(30 * time.Second):
		client.Disconnect()
		return errors.New("WhatsApp didn't start linking in time")
	}
	if phone == "" {
		return nil
	}
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, phone)
	pairCtx, cancelPair := context.WithTimeout(ctx, 30*time.Second)
	defer cancelPair()
	code, err := client.PairPhone(pairCtx, digits, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		client.Disconnect()
		return err
	}
	w.emit(map[string]any{"type": "pairing", "code": code})
	return nil
}

// Disconnect closes the connection; the link stays (Connect again later).
func (w *WhatsApp) Disconnect() {
	w.lock.Lock()
	defer w.lock.Unlock()
	if w.client != nil {
		w.client.Disconnect()
		w.client = nil
	}
	if w.cancel != nil {
		w.cancel()
		w.cancel = nil
	}
}

// Logout unlinks this device from the WhatsApp account.
func (w *WhatsApp) Logout() error {
	w.lock.Lock()
	client := w.client
	w.lock.Unlock()
	if client != nil && client.IsLoggedIn() {
		if err := client.Logout(context.Background()); err != nil {
			return err
		}
	}
	w.Disconnect()
	return nil
}

func (w *WhatsApp) handleEvent(rawEvt any) {
	// Diagnostics: which kinds of events arrive (types only, no content)
	if _, seen := w.seenEvents.LoadOrStore(fmt.Sprintf("%T", rawEvt), true); !seen {
		w.emit(map[string]any{"type": "debug", "event": fmt.Sprintf("%T", rawEvt)})
	}
	switch evt := rawEvt.(type) {
	case *events.Connected:
		w.emitSelf()
		w.emitState("connected", "")
		go w.syncNames()
	case *events.Disconnected:
		w.emitState("reconnecting", "")
	case *events.LoggedOut:
		w.emitState("logged_out", evt.Reason.String())
	case *events.HistorySync:
		w.handleHistorySync(evt)
	case *events.Message:
		w.emitWAMessage(evt, true)
	case *events.Receipt:
		if evt.Type == types.ReceiptTypeRead || evt.Type == types.ReceiptTypeReadSelf {
			w.emit(map[string]any{"type": "read", "threadKey": w.pnJID(evt.Chat).String(), "contactId": w.pnJID(evt.Sender).String(),
				"watermarkMs": evt.Timestamp.UnixMilli()})
		}
	case *events.ChatPresence:
		w.emit(map[string]any{"type": "typing", "threadKey": w.pnJID(evt.Chat).String(), "senderId": w.pnJID(evt.Sender).String(),
			"isTyping": evt.State == types.ChatPresenceComposing})
	case *events.PushName:
		w.emitContact(evt.JID, evt.NewPushName)
	}
}

func (w *WhatsApp) emitSelf() {
	if w.client == nil || w.client.Store.ID == nil {
		return
	}
	w.emit(map[string]any{"type": "self", "id": w.client.Store.ID.ToNonAD().String(), "name": w.client.Store.PushName})
}

// syncNames sends contact names from the device store and group names from the server.
func (w *WhatsApp) syncNames() {
	ctx := context.Background()
	client := w.client
	if client == nil {
		return
	}
	if contacts, err := client.Store.Contacts.GetAllContacts(ctx); err == nil {
		for jid, info := range contacts {
			w.emitContact(jid, firstNonEmpty(info.FullName, info.FirstName, info.PushName, info.BusinessName))
		}
	}
	if groups, err := client.GetJoinedGroups(ctx); err == nil {
		for _, group := range groups {
			w.emit(map[string]any{"type": "thread", "key": group.JID.String(), "name": group.Name, "isGroup": true,
				"isEncrypted": true, "lastActivityMs": 0, "nameOnly": true})
		}
	} else {
		w.log.Err(err).Msg("Failed to get WhatsApp groups")
	}
}

func (w *WhatsApp) handleHistorySync(evt *events.HistorySync) {
	w.emit(map[string]any{"type": "debug", "event": "history_sync", "syncType": evt.Data.GetSyncType().String(),
		"conversations": len(evt.Data.GetConversations()), "progress": evt.Data.GetProgress()})
	for _, conv := range evt.Data.GetConversations() {
		chat, err := types.ParseJID(conv.GetID())
		if err != nil || chat.User == "0" || chat.Server == types.BroadcastServer || chat.Server == types.NewsletterServer {
			// WhatsApp's system account, status updates and channels aren't chats
			continue
		}
		if !chat.IsEmpty() && chat.Server == types.HiddenUserServer && conv.GetPnJID() != "" {
			if pn, err := types.ParseJID(conv.GetPnJID()); err == nil {
				chat = pn
			}
		}
		chat = w.pnJID(chat)
		lastActivity := int64(conv.GetConversationTimestamp()) * 1000
		w.emit(map[string]any{
			"type": "thread", "key": chat.String(), "name": firstNonEmpty(conv.GetName(), conv.GetDisplayName()),
			"isGroup": chat.Server == types.GroupServer, "isEncrypted": true,
			"lastActivityMs": lastActivity, "lastReadMs": lastReadFromUnread(conv.GetUnreadCount(), lastActivity),
		})
		for _, item := range conv.GetMessages() {
			msg, err := w.client.ParseWebMessage(chat, item.GetMessage())
			if err == nil {
				w.emitWAMessage(msg, false)
			}
		}
	}
	for _, pushName := range evt.Data.GetPushnames() {
		if jid, err := types.ParseJID(pushName.GetID()); err == nil {
			w.emitContact(jid, pushName.GetPushname())
		}
	}
}

func lastReadFromUnread(unread uint32, lastActivity int64) int64 {
	if unread > 0 {
		return 0
	}
	return lastActivity
}

func (w *WhatsApp) emitContact(jid types.JID, name string) {
	if name == "" || jid.IsEmpty() {
		return
	}
	w.emit(map[string]any{"type": "contact", "id": w.pnJID(jid).String(), "name": name, "firstName": strings.SplitN(name, " ", 2)[0]})
}

// emitWAMessage turns a WhatsApp message into message / reaction events (and downloads its media when live).
func (w *WhatsApp) emitWAMessage(evt *events.Message, live bool) {
	msg := evt.Message
	if msg == nil {
		return
	}
	chatJID := evt.Info.Chat
	if chatJID.Server == types.HiddenUserServer && !evt.Info.IsFromMe && !evt.Info.SenderAlt.IsEmpty() && !evt.Info.IsGroup {
		chatJID = evt.Info.SenderAlt
	} else if chatJID.Server == types.HiddenUserServer && evt.Info.IsFromMe && !evt.Info.RecipientAlt.IsEmpty() {
		chatJID = evt.Info.RecipientAlt
	}
	if chatJID.User == "0" || chatJID.Server == types.BroadcastServer || chatJID.Server == types.NewsletterServer {
		// WhatsApp's system account, status updates and channels aren't chats
		return
	}
	chat := w.pnJID(chatJID).String()
	senderJID := evt.Info.Sender
	if senderJID.Server == types.HiddenUserServer && !evt.Info.SenderAlt.IsEmpty() {
		senderJID = evt.Info.SenderAlt
	}
	sender := w.pnJID(senderJID).String()
	if evt.Info.IsFromMe && w.client != nil && w.client.Store.ID != nil {
		sender = w.client.Store.ID.ToNonAD().String()
	}
	if evt.Info.PushName != "" && !evt.Info.IsFromMe {
		w.emitContact(w.pnJID(senderJID), evt.Info.PushName)
	}
	if reaction := msg.GetReactionMessage(); reaction != nil {
		w.emit(map[string]any{"type": "reaction", "threadKey": chat, "targetId": reaction.GetKey().GetID(),
			"senderId": sender, "emoji": reaction.GetText()})
		return
	}
	if secret := msg.GetSecretEncryptedMessage(); secret != nil {
		// Edits nowadays come encrypted with the original message's secret
		if secret.GetSecretEncType() == waE2E.SecretEncryptedMessage_MESSAGE_EDIT && w.client != nil {
			if edited, err := w.client.DecryptSecretEncryptedMessage(context.Background(), evt); err == nil {
				if inner := edited.GetProtocolMessage().GetEditedMessage(); inner != nil {
					edited = inner
				}
				_, text := describeWAMessage(edited)
				w.emit(map[string]any{"type": "edit", "threadKey": chat, "messageId": secret.GetTargetMessageKey().GetID(), "text": text})
			} else {
				w.emit(map[string]any{"type": "debug", "event": "edit_decrypt_failed", "error": err.Error()})
			}
		}
		return
	}
	if protocol := msg.GetProtocolMessage(); protocol != nil {
		switch protocol.GetType() {
		case waE2E.ProtocolMessage_REVOKE:
			w.emit(map[string]any{"type": "unsend", "threadKey": chat, "messageId": protocol.GetKey().GetID()})
		case waE2E.ProtocolMessage_MESSAGE_EDIT:
			_, text := describeWAMessage(protocol.GetEditedMessage())
			w.emit(map[string]any{"type": "edit", "threadKey": chat, "messageId": protocol.GetKey().GetID(), "text": text})
		}
		return
	}
	kind, text := describeWAMessage(msg)
	if kind == "" {
		return
	}
	event := map[string]any{
		"type": "message", "threadKey": chat, "id": evt.Info.ID, "otid": evt.Info.ID, "senderId": sender, "text": text,
		"kind": kind, "timestampMs": evt.Info.Timestamp.UnixMilli(), "isEncrypted": true,
	}
	isMedia := kind != "text" && kind != "other" && kind != "location"
	// History can be long, so from it only photos and stickers are downloaded right away; the rest carries a
	// reference the app hands back to DownloadMedia when the user opens it
	downloadNow := isMedia && (live || kind == "image" || kind == "sticker")
	if isMedia && !downloadNow {
		if ref, err := proto.Marshal(msg); err == nil {
			event["mediaRef"] = base64.StdEncoding.EncodeToString(ref)
		}
	}
	w.emit(event)
	if downloadNow {
		go w.downloadMedia(chat, evt.Info.ID, kind, msg)
	}
}

// DownloadMedia downloads a message's media from the reference its message event carried, emits the attachment
// and returns the file's path.
func (w *WhatsApp) DownloadMedia(chatJID, messageID, mediaRef string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(mediaRef)
	if err != nil {
		return "", fmt.Errorf("invalid media reference: %w", err)
	}
	msg := &waE2E.Message{}
	if err = proto.Unmarshal(raw, msg); err != nil {
		return "", fmt.Errorf("invalid media reference: %w", err)
	}
	kind, _ := describeWAMessage(msg)
	return w.downloadMedia(chatJID, messageID, kind, msg)
}

func describeWAMessage(msg *waE2E.Message) (kind, text string) {
	switch {
	case msg.GetConversation() != "":
		return "text", msg.GetConversation()
	case msg.GetExtendedTextMessage() != nil:
		return "text", msg.GetExtendedTextMessage().GetText()
	case msg.GetImageMessage() != nil:
		return "image", msg.GetImageMessage().GetCaption()
	case msg.GetVideoMessage() != nil:
		return "video", msg.GetVideoMessage().GetCaption()
	case msg.GetAudioMessage() != nil:
		return "audio", ""
	case msg.GetDocumentMessage() != nil:
		return "file", msg.GetDocumentMessage().GetFileName()
	case msg.GetStickerMessage() != nil:
		return "sticker", ""
	case msg.GetLocationMessage() != nil, msg.GetLiveLocationMessage() != nil:
		return "location", ""
	case msg.GetProtocolMessage() != nil, msg.GetSenderKeyDistributionMessage() != nil && msg.GetConversation() == "",
		msg.GetPollUpdateMessage() != nil, msg.GetEncReactionMessage() != nil:
		return "", ""
	case msg.GetEphemeralMessage() != nil:
		return describeWAMessage(msg.GetEphemeralMessage().GetMessage())
	case msg.GetViewOnceMessage() != nil:
		return describeWAMessage(msg.GetViewOnceMessage().GetMessage())
	default:
		return "other", ""
	}
}

func (w *WhatsApp) downloadMedia(chat, messageID, kind string, msg *waE2E.Message) (string, error) {
	client := w.client
	if client == nil {
		return "", errors.New("WhatsApp isn't connected")
	}
	for {
		// Media of disappearing and view-once messages sits one level down
		if inner := msg.GetEphemeralMessage().GetMessage(); inner != nil {
			msg = inner
		} else if inner := msg.GetViewOnceMessage().GetMessage(); inner != nil {
			msg = inner
		} else {
			break
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	data, err := client.DownloadAny(ctx, msg)
	if err != nil {
		return "", fmt.Errorf("download failed: %w", err)
	} else if len(data) > maxEncryptedMediaBytes {
		return "", errors.New("the file is too large")
	}
	dir := filepath.Join(w.dataDir, "media")
	if err = os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	sum := sha256.Sum256([]byte(chat + "/" + messageID))
	file := filepath.Join(dir, hex.EncodeToString(sum[:16]))
	if err = os.WriteFile(file, data, 0o600); err != nil {
		return "", err
	}
	var mimeType, fileName string
	var width, height uint32
	switch {
	case msg.GetImageMessage() != nil:
		mimeType, width, height = msg.GetImageMessage().GetMimetype(), msg.GetImageMessage().GetWidth(), msg.GetImageMessage().GetHeight()
	case msg.GetVideoMessage() != nil:
		mimeType, width, height = msg.GetVideoMessage().GetMimetype(), msg.GetVideoMessage().GetWidth(), msg.GetVideoMessage().GetHeight()
	case msg.GetAudioMessage() != nil:
		mimeType = msg.GetAudioMessage().GetMimetype()
	case msg.GetDocumentMessage() != nil:
		mimeType, fileName = msg.GetDocumentMessage().GetMimetype(), msg.GetDocumentMessage().GetFileName()
	case msg.GetStickerMessage() != nil:
		mimeType = msg.GetStickerMessage().GetMimetype()
	}
	w.emit(map[string]any{
		"type": "attachment", "threadKey": chat, "messageId": messageID, "kind": kind, "url": "file://" + file,
		"previewUrl": "file://" + file, "mimeType": mimeType, "fileName": fileName, "width": width, "height": height,
	})
	return file, nil
}

// SendText sends a text message and emits it (WhatsApp doesn't echo our own messages to this device).
func (w *WhatsApp) SendText(chatJID string, text string) (string, error) {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return "", err
	}
	resp, err := client.SendMessage(context.Background(), chat, &waE2E.Message{Conversation: proto.String(text)})
	if err != nil {
		return "", err
	}
	w.emit(map[string]any{
		"type": "message", "threadKey": chat.String(), "id": resp.ID, "otid": resp.ID, "senderId": client.Store.ID.ToNonAD().String(),
		"text": text, "kind": "text", "timestampMs": resp.Timestamp.UnixMilli(), "isEncrypted": true,
	})
	return resp.ID, nil
}

// SendReaction reacts to a message; an empty emoji removes the reaction.
func (w *WhatsApp) SendReaction(chatJID, messageID, senderJID, emoji string) error {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return err
	}
	sender, err := types.ParseJID(senderJID)
	if err != nil {
		return err
	}
	if _, err = client.SendMessage(context.Background(), chat, client.BuildReaction(chat, sender, messageID, emoji)); err != nil {
		return err
	}
	w.emit(map[string]any{"type": "reaction", "threadKey": chat.String(), "targetId": messageID,
		"senderId": client.Store.ID.ToNonAD().String(), "emoji": emoji})
	return nil
}

// MarkRead marks the chat read up to the given message.
func (w *WhatsApp) MarkRead(chatJID, messageID, senderJID string) error {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil || messageID == "" {
		return err
	}
	sender, err := types.ParseJID(senderJID)
	if err != nil {
		return err
	}
	return client.MarkRead(context.Background(), []types.MessageID{messageID}, time.Now(), chat, sender)
}

// sendAvailable marks the account online. Presence needs the account's push name, which arrives with the app state
// sync; until it has, the name of the account's own contact (or its phone number) stands in.
func (w *WhatsApp) sendAvailable(client *whatsmeow.Client) error {
	if client.Store.PushName == "" && client.Store.ID != nil {
		self := client.Store.ID.ToNonAD()
		name := "+" + self.User
		if contact, err := client.Store.Contacts.GetContact(context.Background(), self); err == nil {
			name = cmpOr(contact.PushName, contact.FullName, name)
		}
		client.Store.PushName = name
	}
	return client.SendPresence(context.Background(), types.PresenceAvailable)
}

func cmpOr(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// SetTyping shows or clears "typing…" in the chat.
func (w *WhatsApp) SetTyping(chatJID string, isTyping bool) error {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return err
	}
	state := types.ChatPresencePaused
	if isTyping {
		state = types.ChatPresenceComposing
		// Typing from someone who isn't "available" isn't shown
		if err := w.sendAvailable(client); err != nil {
			w.emit(map[string]any{"type": "debug", "event": "presence_failed", "error": err.Error()})
		}
	}
	// 1:1 chats are addressed by LID nowadays; typing sent to the phone-number JID isn't shown (as in mautrix-whatsapp)
	if chat.Server == types.DefaultUserServer {
		if lid, err := client.Store.LIDs.GetLIDForPN(context.Background(), chat); err == nil && !lid.IsEmpty() {
			chat = lid
		}
	}
	err = client.SendChatPresence(context.Background(), chat, state, types.ChatPresenceMediaText)
	w.emit(map[string]any{"type": "debug", "event": "typing_sent", "server": chat.Server, "state": string(state), "ok": err == nil})
	if !isTyping {
		// While a linked device is online the phone holds back its own notifications
		_ = client.SendPresence(context.Background(), types.PresenceUnavailable)
	}
	return err
}

func (w *WhatsApp) clientAndChat(chatJID string) (*whatsmeow.Client, types.JID, error) {
	w.lock.Lock()
	client := w.client
	w.lock.Unlock()
	if client == nil || !client.IsLoggedIn() {
		return nil, types.JID{}, errors.New("WhatsApp isn't connected")
	}
	chat, err := types.ParseJID(chatJID)
	return client, chat, err
}

func (w *WhatsApp) emitState(state, err string) {
	w.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (w *WhatsApp) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && w.listener != nil {
		w.listener.OnEvent(string(data))
	}
}

// pnJID maps WhatsApp's anonymous LID identifiers (user@lid) to the phone-number JID when the device store knows
// it, so chats and senders match contacts (which are keyed by phone number). Other JIDs are returned unchanged.
func (w *WhatsApp) pnJID(jid types.JID) types.JID {
	jid = jid.ToNonAD()
	if jid.Server != types.HiddenUserServer || w.client == nil {
		return jid
	}
	if pn, err := w.client.Store.LIDs.GetPNForLID(context.Background(), jid); err == nil && !pn.IsEmpty() {
		return pn.ToNonAD()
	}
	return jid
}

// RequestHistory asks the user's own phone (not any contact) for up to count messages of a chat older than the
// given one; they arrive as an on-demand history sync (message events).
func (w *WhatsApp) RequestHistory(chatJID, oldestMessageID string, oldestFromMe bool, oldestTimestampMs int64, count int) error {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return err
	}
	request := client.BuildHistorySyncRequest(&types.MessageInfo{
		MessageSource: types.MessageSource{Chat: chat, IsFromMe: oldestFromMe},
		ID:            oldestMessageID,
		Timestamp:     time.UnixMilli(oldestTimestampMs),
	}, count)
	_, err = client.SendPeerMessage(context.Background(), request)
	return err
}

// SendMediaFile sends the file at path (written by the app into its private storage) as a photo, video, voice
// message or document, depending on mimeType, and returns the message ID. The file is moved into the media
// cache so the app's echo can show it.
func (w *WhatsApp) SendMediaFile(chatJID, path, mimeType, fileName, caption string, width, height int) (string, error) {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	kind, mediaType := "file", whatsmeow.MediaDocument
	switch {
	case strings.HasPrefix(mimeType, "image/"):
		kind, mediaType = "image", whatsmeow.MediaImage
	case strings.HasPrefix(mimeType, "video/"):
		kind, mediaType = "video", whatsmeow.MediaVideo
	case strings.HasPrefix(mimeType, "audio/"):
		kind, mediaType = "audio", whatsmeow.MediaAudio
	}
	up, err := client.Upload(context.Background(), data, mediaType)
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	msg := &waE2E.Message{}
	switch kind {
	case "image":
		msg.ImageMessage = &waE2E.ImageMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, Mimetype: &mimeType,
			FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: &up.FileLength,
			Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height)), Caption: optionalString(caption),
		}
	case "video":
		msg.VideoMessage = &waE2E.VideoMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, Mimetype: &mimeType,
			FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: &up.FileLength,
			Caption: optionalString(caption),
		}
	case "audio":
		msg.AudioMessage = &waE2E.AudioMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, Mimetype: &mimeType,
			FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: &up.FileLength,
		}
	default:
		msg.DocumentMessage = &waE2E.DocumentMessage{
			URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, Mimetype: &mimeType,
			FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: &up.FileLength,
			FileName: &fileName, Title: &fileName, Caption: optionalString(caption),
		}
	}
	resp, err := client.SendMessage(context.Background(), chat, msg)
	if err != nil {
		return "", err
	}
	self := client.Store.ID.ToNonAD().String()
	w.emit(map[string]any{
		"type": "message", "threadKey": chat.String(), "id": resp.ID, "otid": resp.ID, "senderId": self,
		"text": caption, "kind": kind, "timestampMs": resp.Timestamp.UnixMilli(), "isEncrypted": true,
	})
	// Keep our copy as the echo's media
	dir := filepath.Join(w.dataDir, "media")
	if os.MkdirAll(dir, 0o700) == nil {
		sum := sha256.Sum256([]byte(chat.String() + "/" + resp.ID))
		cached := filepath.Join(dir, hex.EncodeToString(sum[:16]))
		if os.Rename(path, cached) == nil {
			w.emit(map[string]any{
				"type": "attachment", "threadKey": chat.String(), "messageId": resp.ID, "kind": kind,
				"url": "file://" + cached, "previewUrl": "file://" + cached, "mimeType": mimeType, "fileName": fileName,
				"width": width, "height": height,
			})
		}
	}
	return resp.ID, nil
}

func optionalString(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
