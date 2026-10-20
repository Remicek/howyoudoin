package hydbridge

import (
	"context"
	"errors"
	"strconv"
	"time"

	"go.mau.fi/mautrix-telegram/pkg/gotd/tg"
)

// SendReply sends text replying to a message ("<thread key>/<id>").
func (t *Telegram) SendReply(threadKey, text, replyToID string) (string, error) {
	_, replyMsgID, err := splitTelegramMessageID(replyToID)
	if err != nil {
		return t.SendText(threadKey, text)
	}
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	rid := randomID()
	resp, err := t.client.API().MessagesSendMessage(ctx, &tg.MessagesSendMessageRequest{
		Peer: peer, Message: text, RandomID: rid, ReplyTo: &tg.InputReplyToMessage{ReplyToMsgID: replyMsgID},
	})
	if err != nil {
		return "", friendlyTelegramError(err)
	}
	t.pendingReplies.Store(rid, replyToID)
	t.echoSent(ctx, threadKey, rid, text, resp)
	return strconv.FormatInt(rid, 10), nil
}

// EditMessage changes the text of one of our messages.
func (t *Telegram) EditMessage(threadKey, messageID, text string) error {
	_, msgID, err := splitTelegramMessageID(messageID)
	if err != nil {
		return err
	}
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if _, err = t.client.API().MessagesEditMessage(ctx, &tg.MessagesEditMessageRequest{Peer: peer, ID: msgID, Message: text}); err != nil {
		return friendlyTelegramError(err)
	}
	t.emit(map[string]any{"type": "edit", "threadKey": threadKey, "messageId": messageID, "text": text})
	return nil
}

// Unsend deletes one of our messages for everyone.
func (t *Telegram) Unsend(threadKey, messageID string) error {
	_, msgID, err := splitTelegramMessageID(messageID)
	if err != nil {
		return err
	}
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	switch p := peer.(type) {
	case *tg.InputPeerChannel:
		_, err = t.client.API().ChannelsDeleteMessages(ctx, &tg.ChannelsDeleteMessagesRequest{
			Channel: &tg.InputChannel{ChannelID: p.ChannelID, AccessHash: p.AccessHash}, ID: []int{msgID},
		})
	case nil:
		err = errors.New("unknown chat")
	default:
		_, err = t.client.API().MessagesDeleteMessages(ctx, &tg.MessagesDeleteMessagesRequest{Revoke: true, ID: []int{msgID}})
	}
	if err != nil {
		return friendlyTelegramError(err)
	}
	t.emit(map[string]any{"type": "unsend", "threadKey": threadKey, "messageId": messageID})
	return nil
}

// telegramReplyTo is the ID ("<thread key>/<id>") of the message this one replies to, if any.
func telegramReplyTo(key string, msg *tg.Message) string {
	header, ok := msg.ReplyTo.(*tg.MessageReplyHeader)
	if !ok || header.ReplyToMsgID == 0 {
		return ""
	}
	return key + "/" + strconv.Itoa(header.ReplyToMsgID)
}
