package hydbridge

import (
	"context"
	"strconv"
	"time"

	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
)

// SendReply sends text quoting a message ("<author ACI>:<timestamp>"); quotedText is shown in the quote.
func (s *Signal) SendReply(threadKey, text, replyToID, quotedText string) (string, error) {
	author, quotedTs, err := splitSignalMessageID(replyToID)
	if err != nil {
		return s.SendText(threadKey, text)
	}
	ts := uint64(time.Now().UnixMilli())
	authorACI := author.String()
	msg := &signalpb.DataMessage{Body: &text, Timestamp: &ts, Quote: &signalpb.DataMessage_Quote{
		Id: &quotedTs, AuthorAci: &authorACI, Text: &quotedText, Type: signalpb.DataMessage_Quote_NORMAL.Enum(),
	}}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := s.send(ctx, threadKey, signalmeow.WrapDataMessage(msg)); err != nil {
		return "", err
	}
	// Signal shows an edit as the whole new message, so edits of this reply repeat its quote
	s.sentQuotes.Store(ts, msg.Quote)
	s.echoReply(threadKey, ts, text, replyToID)
	return strconv.FormatUint(ts, 10), nil
}

// EditMessage changes the text of one of our messages ("<our ACI>:<timestamp>").
func (s *Signal) EditMessage(threadKey, messageID, text string) error {
	_, target, err := splitSignalMessageID(messageID)
	if err != nil {
		return err
	}
	ts := uint64(time.Now().UnixMilli())
	edited := &signalpb.DataMessage{Body: &text, Timestamp: &ts}
	if quote, ok := s.sentQuotes.Load(target); ok {
		edited.Quote = quote.(*signalpb.DataMessage_Quote)
	}
	content := signalmeow.WrapEditMessage(&signalpb.EditMessage{TargetSentTimestamp: &target, DataMessage: edited})
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := s.send(ctx, threadKey, content); err != nil {
		return err
	}
	s.emit(map[string]any{"type": "edit", "threadKey": threadKey, "messageId": messageID, "text": text})
	return nil
}

// Unsend deletes one of our messages for everyone.
func (s *Signal) Unsend(threadKey, messageID string) error {
	_, target, err := splitSignalMessageID(messageID)
	if err != nil {
		return err
	}
	ts := uint64(time.Now().UnixMilli())
	msg := &signalpb.DataMessage{Timestamp: &ts, Delete: &signalpb.DataMessage_Delete{TargetSentTimestamp: &target}}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := s.send(ctx, threadKey, signalmeow.WrapDataMessage(msg)); err != nil {
		return err
	}
	s.emit(map[string]any{"type": "unsend", "threadKey": threadKey, "messageId": messageID})
	return nil
}

func (s *Signal) echoReply(threadKey string, ts uint64, text, replyToID string) {
	self := s.selfID()
	s.sentThreads.Store(ts, threadKey)
	s.emit(map[string]any{"type": "message", "threadKey": threadKey, "id": self + ":" + strconv.FormatUint(ts, 10),
		"otid": strconv.FormatUint(ts, 10), "senderId": self, "text": text, "kind": "text", "timestampMs": int64(ts),
		"isEncrypted": true, "replyToId": replyToID})
}

// signalReplyTo is the ID of the message this one quotes, if any.
func signalReplyTo(msg *signalpb.DataMessage) string {
	quote := msg.GetQuote()
	if quote == nil || quote.GetId() == 0 {
		return ""
	}
	author, err := signalmeow.ParseStringOrBinaryUUID(quote.GetAuthorAci(), quote.GetAuthorAciBinary())
	if err != nil {
		return ""
	}
	return author.String() + ":" + strconv.FormatUint(quote.GetId(), 10)
}
