package hydbridge

import (
	"context"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// SendReply sends text quoting another message (by ID, its sender's JID and its text, which WhatsApp shows in the
// quote).
func (w *WhatsApp) SendReply(chatJID, text, replyToID, replySenderJID, quotedText string) (string, error) {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return "", err
	}
	quoteSender := replySenderJID
	if sender, err := types.ParseJID(replySenderJID); err == nil {
		quoteSender = sender.ToNonAD().String()
	}
	// WhatsApp shows a quoted photo or video with its preview only when the quote carries the message itself
	quoted := w.recentMedia.get(replyToID)
	if quoted == nil {
		quoted = &waE2E.Message{Conversation: proto.String(quotedText)}
	}
	msg := &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
		Text: proto.String(text),
		ContextInfo: &waE2E.ContextInfo{
			StanzaID:      proto.String(replyToID),
			Participant:   proto.String(quoteSender),
			QuotedMessage: quoted,
		},
	}}
	resp, err := client.SendMessage(context.Background(), chat, msg)
	if err != nil {
		return "", err
	}
	w.emit(map[string]any{
		"type": "message", "threadKey": chat.String(), "id": resp.ID, "otid": resp.ID, "senderId": client.Store.ID.ToNonAD().String(),
		"text": text, "kind": "text", "timestampMs": resp.Timestamp.UnixMilli(), "isEncrypted": true, "replyToId": replyToID,
	})
	return resp.ID, nil
}

// EditMessage changes the text of one of our messages.
func (w *WhatsApp) EditMessage(chatJID, messageID, text string) error {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return err
	}
	edit := client.BuildEdit(chat, messageID, &waE2E.Message{Conversation: proto.String(text)})
	if _, err = client.SendMessage(context.Background(), chat, edit); err != nil {
		return err
	}
	w.emit(map[string]any{"type": "edit", "threadKey": chat.String(), "messageId": messageID, "text": text})
	return nil
}

// Unsend deletes one of our messages for everyone.
func (w *WhatsApp) Unsend(chatJID, messageID string) error {
	client, chat, err := w.clientAndChat(chatJID)
	if err != nil {
		return err
	}
	if _, err = client.SendMessage(context.Background(), chat, client.BuildRevoke(chat, types.EmptyJID, messageID)); err != nil {
		return err
	}
	w.emit(map[string]any{"type": "unsend", "threadKey": chat.String(), "messageId": messageID})
	return nil
}

// waReplyTo is the ID of the message this one quotes, if any.
func waReplyTo(msg *waE2E.Message) string {
	if inner := waMediaMessage(msg); inner != msg && inner != nil {
		// Disappearing, view-once and wrapped messages keep the reply on the message inside
		if id := waReplyTo(inner); id != "" {
			return id
		}
	}
	for _, info := range []*waE2E.ContextInfo{
		msg.GetExtendedTextMessage().GetContextInfo(), msg.GetImageMessage().GetContextInfo(),
		msg.GetVideoMessage().GetContextInfo(), msg.GetAudioMessage().GetContextInfo(),
		msg.GetDocumentMessage().GetContextInfo(), msg.GetStickerMessage().GetContextInfo(),
		msg.GetLocationMessage().GetContextInfo(), msg.GetContactMessage().GetContextInfo(),
	} {
		if id := info.GetStanzaID(); id != "" {
			return id
		}
	}
	return ""
}
