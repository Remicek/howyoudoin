package hydbridge

import (
	"context"
	"crypto/sha256"
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
	return &WhatsApp{listener: listener, log: log, dataDir: dataDir, container: container}, nil
}

// IsLinked tells whether this phone is already linked to a WhatsApp account.
func (w *WhatsApp) IsLinked() bool {
	device, err := w.container.GetFirstDevice(context.Background())
	return err == nil && device.ID != nil
}

// Connect connects a linked device, or starts linking: with an empty phone number it emits QR codes
// ({"type":"pairing","qr":...}), otherwise a pairing code to type on the phone ({"type":"pairing","code":...}).
func (w *WhatsApp) Connect(phone string) error {
	w.lock.Lock()
	defer w.lock.Unlock()
	if w.client != nil {
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(context.Background())
	device, err := w.container.GetFirstDevice(ctx)
	if err != nil {
		cancel()
		return err
	}
	client := whatsmeow.NewClient(device, waLog.Zerolog(w.log.With().Str("component", "whatsmeow").Logger()))
	client.EnableAutoReconnect = true
	client.AddEventHandler(w.handleEvent)
	w.client, w.cancel = client, cancel

	if device.ID != nil {
		return client.Connect()
	}
	if phone == "" {
		qr, err := client.GetQRChannel(ctx)
		if err != nil {
			return err
		}
		go func() {
			for item := range qr {
				switch item.Event {
				case whatsmeow.QRChannelEventCode:
					w.emit(map[string]any{"type": "pairing", "qr": item.Code, "timeoutMs": item.Timeout.Milliseconds()})
				case whatsmeow.QRChannelEventError, "timeout":
					w.emitState("pairing_failed", item.Event)
				}
			}
		}()
		return client.Connect()
	}
	if err = client.Connect(); err != nil {
		return err
	}
	code, err := client.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (HowYouDoin)")
	if err != nil {
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
	switch evt := rawEvt.(type) {
	case *events.PairSuccess:
		w.emitState("paired", "")
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
			w.emit(map[string]any{"type": "read", "threadKey": evt.Chat.String(), "contactId": evt.Sender.ToNonAD().String(),
				"watermarkMs": evt.Timestamp.UnixMilli()})
		}
	case *events.ChatPresence:
		w.emit(map[string]any{"type": "typing", "threadKey": evt.Chat.String(), "senderId": evt.Sender.ToNonAD().String(),
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
	for _, conv := range evt.Data.GetConversations() {
		chat, err := types.ParseJID(conv.GetID())
		if err != nil || chat.Server == types.BroadcastServer || chat.Server == types.NewsletterServer {
			continue
		}
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
	w.emit(map[string]any{"type": "contact", "id": jid.ToNonAD().String(), "name": name, "firstName": strings.SplitN(name, " ", 2)[0]})
}

// emitWAMessage turns a WhatsApp message into message / reaction events (and downloads its media when live).
func (w *WhatsApp) emitWAMessage(evt *events.Message, live bool) {
	msg := evt.Message
	if msg == nil {
		return
	}
	chat := evt.Info.Chat.String()
	sender := evt.Info.Sender.ToNonAD().String()
	if evt.Info.IsFromMe && w.client != nil && w.client.Store.ID != nil {
		sender = w.client.Store.ID.ToNonAD().String()
	}
	if evt.Info.PushName != "" && !evt.Info.IsFromMe {
		w.emitContact(evt.Info.Sender, evt.Info.PushName)
	}
	if reaction := msg.GetReactionMessage(); reaction != nil {
		w.emit(map[string]any{"type": "reaction", "threadKey": chat, "targetId": reaction.GetKey().GetID(),
			"senderId": sender, "emoji": reaction.GetText()})
		return
	}
	kind, text := describeWAMessage(msg)
	if kind == "" {
		return
	}
	w.emit(map[string]any{
		"type": "message", "threadKey": chat, "id": evt.Info.ID, "otid": evt.Info.ID, "senderId": sender, "text": text,
		"kind": kind, "timestampMs": evt.Info.Timestamp.UnixMilli(), "isEncrypted": true,
	})
	if live && kind != "text" && kind != "other" && kind != "location" {
		go w.downloadMedia(chat, evt.Info.ID, kind, msg)
	}
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

func (w *WhatsApp) downloadMedia(chat, messageID, kind string, msg *waE2E.Message) {
	client := w.client
	if client == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	data, err := client.DownloadAny(ctx, msg)
	if err != nil || len(data) > maxEncryptedMediaBytes {
		return
	}
	dir := filepath.Join(w.dataDir, "media")
	if os.MkdirAll(dir, 0o700) != nil {
		return
	}
	sum := sha256.Sum256([]byte(chat + "/" + messageID))
	file := filepath.Join(dir, hex.EncodeToString(sum[:16]))
	if os.WriteFile(file, data, 0o600) != nil {
		return
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

// SetTyping shows or clears "typing…" in the chat.
func (w *WhatsApp) SetTyping(chatJID string, isTyping bool) error {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return err
	}
	state := types.ChatPresencePaused
	if isTyping {
		state = types.ChatPresenceComposing
		_ = client.SendPresence(context.Background(), types.PresenceAvailable)
	}
	return client.SendChatPresence(context.Background(), chat, state, types.ChatPresenceMediaText)
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
