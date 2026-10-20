package hydbridge

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/mautrix-meta/pkg/messagix/socket"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waConsumerApplication"
	"go.mau.fi/whatsmeow/proto/waMsgApplication"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// SendReply sends text replying to a message (by ID and its sender's ID), in a regular or encrypted chat.
func (m *Messenger) SendReply(threadKey string, isEncrypted, isGroup bool, text, replyToID, replySenderID string) (string, error) {
	if isEncrypted {
		return m.sendEncryptedReply(threadKey, isGroup, text, replyToID, replySenderID)
	}
	key, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return "", fmt.Errorf("invalid thread key: %w", err)
	}
	sender, _ := strconv.ParseInt(replySenderID, 10, 64)
	otid := methods.GenerateEpochID()
	resp, err := m.client.ExecuteTasks(context.Background(), &socket.SendMessageTask{
		ThreadId:         key,
		Otid:             otid,
		Source:           table.MESSENGER_INBOX_IN_THREAD,
		InitiatingSource: table.FACEBOOK_INBOX,
		SendType:         table.TEXT,
		SyncGroup:        1,
		Text:             text,
		ReplyMetaData:    &socket.ReplyMetaData{ReplyMessageId: replyToID, ReplySourceType: 1, ReplyType: 0, ReplySender: sender},
	})
	if err != nil {
		return "", err
	}
	m.emitTable(resp)
	return strconv.FormatInt(otid, 10), nil
}

func (m *Messenger) sendEncryptedReply(threadKey string, isGroup bool, text, replyToID, replySenderID string) (string, error) {
	if m.e2ee == nil {
		return "", errNotConnectedE2EE
	}
	chat := types.NewJID(threadKey, chatServer(isGroup))
	msg := consumerContent(&waConsumerApplication.ConsumerApplication_Content{
		Content: &waConsumerApplication.ConsumerApplication_Content_MessageText{MessageText: &waCommon.MessageText{Text: &text}},
	})
	meta := &waMsgApplication.MessageApplication_Metadata{QuotedMessage: &waMsgApplication.MessageApplication_Metadata_QuotedMessage{
		StanzaID:    proto.String(replyToID),
		Participant: proto.String(types.NewJID(replySenderID, types.MessengerServer).String()),
	}}
	resp, err := m.e2ee.SendFBMessage(context.Background(), chat, msg, meta)
	if err != nil {
		return "", err
	}
	m.emit(map[string]any{
		"type": "message", "threadKey": threadKey, "id": resp.ID, "otid": resp.ID, "senderId": m.SelfID(),
		"text": text, "kind": "text", "timestampMs": resp.Timestamp.UnixMilli(), "isEncrypted": true, "replyToId": replyToID,
	})
	return resp.ID, nil
}

// EditMessage changes the text of one of our messages.
func (m *Messenger) EditMessage(threadKey string, isEncrypted, isGroup bool, messageID, text string) error {
	if isEncrypted {
		if m.e2ee == nil {
			return errNotConnectedE2EE
		}
		chat := types.NewJID(threadKey, chatServer(isGroup))
		msg := consumerContent(&waConsumerApplication.ConsumerApplication_Content{
			Content: &waConsumerApplication.ConsumerApplication_Content_EditMessage{EditMessage: &waConsumerApplication.ConsumerApplication_EditMessage{
				Key:         m.ownMessageKey(chat, messageID, isGroup),
				Message:     &waCommon.MessageText{Text: &text},
				TimestampMS: proto.Int64(time.Now().UnixMilli()),
			}},
		})
		if _, err := m.e2ee.SendFBMessage(context.Background(), chat, msg, nil); err != nil {
			return err
		}
	} else {
		resp, err := m.client.ExecuteTasks(context.Background(), &socket.EditMessageTask{MessageID: messageID, Text: text})
		if err != nil {
			return err
		}
		m.emitTable(resp)
	}
	m.emit(map[string]any{"type": "edit", "threadKey": threadKey, "messageId": messageID, "text": text})
	return nil
}

// Unsend deletes one of our messages for everyone.
func (m *Messenger) Unsend(threadKey string, isEncrypted, isGroup bool, messageID string) error {
	if isEncrypted {
		if m.e2ee == nil {
			return errNotConnectedE2EE
		}
		chat := types.NewJID(threadKey, chatServer(isGroup))
		revoke := &waConsumerApplication.ConsumerApplication{
			Payload: &waConsumerApplication.ConsumerApplication_Payload{
				Payload: &waConsumerApplication.ConsumerApplication_Payload_ApplicationData{
					ApplicationData: &waConsumerApplication.ConsumerApplication_ApplicationData{
						ApplicationContent: &waConsumerApplication.ConsumerApplication_ApplicationData_Revoke{
							Revoke: &waConsumerApplication.ConsumerApplication_RevokeMessage{Key: m.ownMessageKey(chat, messageID, isGroup)},
						},
					},
				},
			},
		}
		if _, err := m.e2ee.SendFBMessage(context.Background(), chat, revoke, nil); err != nil {
			return err
		}
	} else {
		resp, err := m.client.ExecuteTasks(context.Background(), &socket.DeleteMessageTask{MessageId: messageID})
		if err != nil {
			return err
		}
		m.emitTable(resp)
	}
	m.emit(map[string]any{"type": "unsend", "threadKey": threadKey, "messageId": messageID})
	return nil
}

func (m *Messenger) ownMessageKey(chat types.JID, messageID string, isGroup bool) *waCommon.MessageKey {
	key := &waCommon.MessageKey{RemoteJID: proto.String(chat.String()), ID: proto.String(messageID), FromMe: proto.Bool(true)}
	if isGroup {
		key.Participant = proto.String(types.NewJID(m.SelfID(), types.MessengerServer).String())
	}
	return key
}

func consumerContent(content *waConsumerApplication.ConsumerApplication_Content) *waConsumerApplication.ConsumerApplication {
	return &waConsumerApplication.ConsumerApplication{
		Payload: &waConsumerApplication.ConsumerApplication_Payload{
			Payload: &waConsumerApplication.ConsumerApplication_Payload_Content{Content: content},
		},
	}
}
