package hydbridge

import (
	"context"
	"fmt"
	"path/filepath"

	"github.com/google/uuid"
	_ "github.com/mattn/go-sqlite3" // driver for whatsmeow's device store
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waConsumerApplication"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// connectE2EE starts the WhatsApp-protocol client Messenger uses for end-to-end encrypted chats. The phone
// registers its own encrypted device (like the server bridge does); keys live in dataDir/e2ee.db.
func (m *Messenger) connectE2EE(ctx context.Context) error {
	if m.dataDir == "" {
		return nil
	}
	if m.deviceStore == nil {
		address := "file:" + filepath.Join(m.dataDir, "e2ee.db") + "?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=5000"
		container, err := sqlstore.New(ctx, "sqlite3", address, waLog.Zerolog(m.log.With().Str("component", "whatsmeow-store").Logger()))
		if err != nil {
			return fmt.Errorf("failed to open encrypted chat store: %w", err)
		}
		m.deviceStore = container
	}
	device, err := m.deviceStore.GetFirstDevice(ctx)
	if err != nil {
		return fmt.Errorf("failed to load encrypted chat device: %w", err)
	}
	isNew := device.ID == nil
	if isNew {
		if suggested := m.client.MessengerLite.GetSuggestedDeviceID(); suggested != uuid.Nil {
			device.FacebookUUID = suggested
		}
	}
	m.client.SetDevice(device)
	if isNew {
		m.log.Info().Msg("Registering encrypted chat device")
		if err = m.client.RegisterE2EE(ctx, m.selfID); err != nil {
			return fmt.Errorf("failed to register encrypted chat device: %w", err)
		}
		if err = device.Save(ctx); err != nil {
			return fmt.Errorf("failed to save encrypted chat device: %w", err)
		}
	}
	e2ee, err := m.client.PrepareE2EEClient()
	if err != nil {
		return fmt.Errorf("failed to prepare encrypted chat client: %w", err)
	}
	e2ee.AddEventHandler(m.handleE2EEEvent)
	if err = e2ee.Connect(); err != nil {
		return fmt.Errorf("failed to connect encrypted chat client: %w", err)
	}
	m.e2ee = e2ee
	m.device = device
	return nil
}

func (m *Messenger) handleE2EEEvent(rawEvt any) {
	switch evt := rawEvt.(type) {
	case *events.FBMessage:
		m.emitFBMessage(evt.Info.Chat.User, evt.Info.ID, evt.Info.Sender.User, evt.Info.Timestamp.UnixMilli(), evt.GetConsumerApplication(), nil,
			evt.FBApplication.GetMetadata().GetQuotedMessage().GetStanzaID())
	case *events.ChatPresence:
		m.emit(map[string]any{"type": "typing", "threadKey": evt.Chat.User, "senderId": evt.Sender.User,
			"isTyping": evt.State == types.ChatPresenceComposing})
	case *events.Receipt:
		if evt.Type == types.ReceiptTypeRead || evt.Type == types.ReceiptTypeReadSelf {
			m.emit(map[string]any{"type": "read", "threadKey": evt.Chat.User, "contactId": evt.Sender.User,
				"watermarkMs": evt.Timestamp.UnixMilli()})
		}
	case *events.Connected:
		m.emitState("encrypted_connected", "")
	case *events.Disconnected:
		m.emitState("encrypted_disconnected", "")
	}
}

// emitFBMessage turns a decrypted Messenger (consumer application) message into a message or reaction event.
func (m *Messenger) emitFBMessage(threadKey, messageID, senderID string, timestamp int64, app *waConsumerApplication.ConsumerApplication, mediaPaths map[string]string, replyTo ...string) {
	if revoke := app.GetPayload().GetApplicationData().GetRevoke(); revoke != nil {
		m.emit(map[string]any{"type": "unsend", "threadKey": threadKey, "messageId": revoke.GetKey().GetID()})
		return
	}
	content := app.GetPayload().GetContent()
	if content == nil || messageID == "" {
		return
	}
	if edit := content.GetEditMessage(); edit != nil {
		m.emit(map[string]any{"type": "edit", "threadKey": threadKey, "messageId": edit.GetKey().GetID(),
			"text": edit.GetMessage().GetText()})
		return
	}
	if reaction := content.GetReactionMessage(); reaction != nil {
		m.emit(map[string]any{
			"type": "reaction", "threadKey": threadKey, "targetId": reaction.GetKey().GetID(),
			"senderId": senderID, "emoji": reaction.GetText(),
		})
		return
	}
	kind, text := describeContent(content)
	if kind == "" {
		return
	}
	m.ensureContact(senderID)
	m.ensureContact(threadKey)
	event := map[string]any{
		"type":        "message",
		"threadKey":   threadKey,
		"id":          messageID,
		"senderId":    senderID,
		"text":        text,
		"kind":        kind,
		"timestampMs": timestamp,
		"isEncrypted": true,
	}
	if len(replyTo) > 0 && replyTo[0] != "" {
		event["replyToId"] = replyTo[0]
	}
	m.emit(event)
	m.downloadEncryptedMedia(threadKey, messageID, content, mediaPaths)
}

type captioned interface {
	GetCaption() *waCommon.MessageText
}

// describeContent gives the message kind and its text (or caption); edits and other control messages return "".
func describeContent(content *waConsumerApplication.ConsumerApplication_Content) (kind, text string) {
	caption := func(c captioned) string { return c.GetCaption().GetText() }
	switch {
	case content.GetMessageText() != nil:
		return "text", content.GetMessageText().GetText()
	case content.GetExtendedTextMessage() != nil:
		return "text", content.GetExtendedTextMessage().GetText().GetText()
	case content.GetImageMessage() != nil:
		return "image", caption(content.GetImageMessage())
	case content.GetVideoMessage() != nil:
		return "video", caption(content.GetVideoMessage())
	case content.GetAudioMessage() != nil:
		return "audio", ""
	case content.GetDocumentMessage() != nil:
		return "file", content.GetDocumentMessage().GetFileName()
	case content.GetStickerMessage() != nil:
		return "sticker", ""
	case content.GetLocationMessage() != nil, content.GetLiveLocationMessage() != nil:
		return "location", ""
	case content.GetEditMessage() != nil, content.GetPollUpdateMessage() != nil:
		return "", ""
	default:
		return "other", ""
	}
}

// SendEncryptedText sends text to an end-to-end encrypted chat and returns the message ID the echo will carry.
func (m *Messenger) SendEncryptedText(threadKey string, isGroup bool, text string) (string, error) {
	if m.e2ee == nil {
		return "", errNotConnectedE2EE
	}
	server := chatServer(isGroup)
	msg := &waConsumerApplication.ConsumerApplication{
		Payload: &waConsumerApplication.ConsumerApplication_Payload{
			Payload: &waConsumerApplication.ConsumerApplication_Payload_Content{
				Content: &waConsumerApplication.ConsumerApplication_Content{
					Content: &waConsumerApplication.ConsumerApplication_Content_MessageText{
						MessageText: &waCommon.MessageText{Text: &text},
					},
				},
			},
		},
	}
	resp, err := m.e2ee.SendFBMessage(context.Background(), types.NewJID(threadKey, server), msg, nil)
	if err != nil {
		return "", err
	}
	// The encrypted protocol doesn't echo our own messages; emit it so the app replaces its local echo
	m.emit(map[string]any{
		"type": "message", "threadKey": threadKey, "id": resp.ID, "otid": resp.ID, "senderId": m.SelfID(),
		"text": text, "kind": "text", "timestampMs": resp.Timestamp.UnixMilli(), "isEncrypted": true,
	})
	return resp.ID, nil
}

var errNotConnectedE2EE = fmt.Errorf("encrypted chats aren't connected")
