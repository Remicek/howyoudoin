package hydbridge

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	"go.mau.fi/whatsmeow/types"

	"go.mau.fi/mautrix-meta/pkg/messagix/socket"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

// emitInteractions covers attachments, reactions, typing and read receipts of regular chats.
func (m *Messenger) emitInteractions(tbl *table.LSTable) {
	for _, a := range tbl.LSInsertBlobAttachment {
		m.emitAttachment(a.ThreadKey, a.MessageId, attachmentKind(a.AttachmentType), a.PlayableUrl, a.PreviewUrl,
			firstNonEmpty(a.AttachmentMimeType, a.PlayableUrlMimeType), a.Filename, a.PreviewWidth, a.PreviewHeight)
	}
	for _, a := range tbl.LSInsertStickerAttachment {
		m.emitAttachment(a.ThreadKey, a.MessageId, "sticker", firstNonEmpty(a.PlayableUrl, a.ImageUrl), a.PreviewUrl,
			a.PlayableUrlMimeType, "", a.PreviewWidth, a.PreviewHeight)
	}
	for _, r := range tbl.LSUpsertReaction {
		m.emit(map[string]any{"type": "reaction", "threadKey": id(r.ThreadKey), "targetId": r.MessageId,
			"senderId": id(r.ActorId), "emoji": r.Reaction})
	}
	for _, r := range tbl.LSDeleteReaction {
		m.emit(map[string]any{"type": "reaction", "threadKey": id(r.ThreadKey), "targetId": r.MessageId,
			"senderId": id(r.ActorId), "emoji": ""})
	}
	for _, t := range tbl.LSUpdateTypingIndicator {
		m.emit(map[string]any{"type": "typing", "threadKey": id(t.ThreadKey), "senderId": id(t.SenderId), "isTyping": t.IsTyping})
	}
	for _, r := range tbl.LSUpdateReadReceipt {
		m.emit(map[string]any{"type": "read", "threadKey": id(r.ThreadKey), "contactId": id(r.ContactId),
			"watermarkMs": r.ReadWatermarkTimestampMs})
	}
}

func (m *Messenger) emitAttachment(threadKey int64, messageID, kind, url, previewURL, mimeType, fileName string, width, height int64) {
	if messageID == "" || (url == "" && previewURL == "") {
		return
	}
	m.emit(map[string]any{
		"type": "attachment", "threadKey": id(threadKey), "messageId": messageID, "kind": kind,
		"url": firstNonEmpty(url, previewURL), "previewUrl": firstNonEmpty(previewURL, url),
		"mimeType": mimeType, "fileName": fileName, "width": width, "height": height,
	})
}

func attachmentKind(t table.AttachmentType) string {
	switch t {
	case table.AttachmentTypeImage, table.AttachmentTypeAnimatedImage:
		return "image"
	case table.AttachmentTypeVideo:
		return "video"
	case table.AttachmentTypeAudio:
		return "audio"
	case table.AttachmentTypeSticker:
		return "sticker"
	default:
		return "file"
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// SendReaction reacts to a message in a regular chat; an empty emoji removes the reaction.
func (m *Messenger) SendReaction(threadKey string, messageID string, emoji string) error {
	key, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return fmt.Errorf("invalid thread key: %w", err)
	}
	resp, err := m.client.ExecuteTasks(context.Background(), &socket.SendReactionTask{
		ThreadKey:       key,
		TimestampMs:     time.Now().UnixMilli(),
		MessageID:       messageID,
		ActorID:         m.selfID,
		Reaction:        emoji,
		SyncGroup:       1,
		SendAttribution: table.MESSENGER_INBOX_IN_THREAD,
	})
	if err != nil {
		return err
	}
	m.emitTable(resp)
	return nil
}

// MarkRead marks a chat read up to now. For encrypted chats lastMessageID/senderID name the newest message.
func (m *Messenger) MarkRead(threadKey string, isEncrypted bool, isGroup bool, lastMessageID string, senderID string) error {
	if isEncrypted {
		if m.e2ee == nil {
			return errors.New("encrypted chats aren't connected")
		} else if lastMessageID == "" {
			return nil
		}
		chat := types.NewJID(threadKey, chatServer(isGroup))
		sender := types.NewJID(senderID, types.MessengerServer)
		return m.e2ee.MarkRead(context.Background(), []types.MessageID{lastMessageID}, time.Now(), chat, sender)
	}
	key, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return fmt.Errorf("invalid thread key: %w", err)
	}
	_, err = m.client.ExecuteTasks(context.Background(), &socket.ThreadMarkReadTask{
		ThreadId:            key,
		LastReadWatermarkTs: time.Now().UnixMilli(),
		SyncGroup:           1,
	})
	return err
}

// SetTyping shows or clears "typing…" for the other people in the chat.
func (m *Messenger) SetTyping(threadKey string, isEncrypted bool, isGroup bool, isTyping bool) error {
	if isEncrypted {
		if m.e2ee == nil {
			return errors.New("encrypted chats aren't connected")
		}
		state := types.ChatPresencePaused
		if isTyping {
			state = types.ChatPresenceComposing
			// Like mautrix-meta's send_presence_on_typing: Messenger ignores typing from devices that aren't available
			if err := m.e2ee.SendPresence(context.Background(), types.PresenceAvailable); err != nil {
				m.log.Warn().Err(err).Msg("Failed to send available presence")
			}
		}
		return m.e2ee.SendChatPresence(context.Background(), types.NewJID(threadKey, chatServer(isGroup)), state, types.ChatPresenceMediaText)
	}
	key, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return fmt.Errorf("invalid thread key: %w", err)
	}
	groupFlag, typingFlag, threadType := int64(0), int64(0), int64(table.ONE_TO_ONE)
	if isGroup {
		groupFlag, threadType = 1, int64(table.GROUP_THREAD)
	}
	if isTyping {
		typingFlag = 1
	}
	return m.client.ExecuteStatelessTask(context.Background(), &socket.UpdatePresenceTask{
		ThreadKey: key, IsGroupThread: groupFlag, IsTyping: typingFlag, SyncGroup: 1, ThreadType: threadType,
	})
}

func chatServer(isGroup bool) string {
	if isGroup {
		return types.GroupServer
	}
	return types.MessengerServer
}
