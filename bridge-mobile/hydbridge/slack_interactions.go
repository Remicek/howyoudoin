package hydbridge

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"time"

	"github.com/slack-go/slack"

	"go.mau.fi/mautrix-slack/pkg/emoji"
)

func slackContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), time.Minute)
}

// threadRoot is the thread a reply goes into: the replied-to message's own thread, or the message itself.
func (s *Slack) threadRoot(ctx context.Context, channelID, ts string) string {
	resp, err := s.client.GetConversationHistoryContext(ctx, &slack.GetConversationHistoryParameters{
		ChannelID: channelID, Latest: ts, Oldest: ts, Inclusive: true, Limit: 1,
	})
	if err == nil && len(resp.Messages) == 1 && resp.Messages[0].ThreadTimestamp != "" {
		return resp.Messages[0].ThreadTimestamp
	}
	return ts
}

// SendText posts a message and returns its timestamp (the echo's otid).
func (s *Slack) SendText(channelID, text string) (string, error) {
	ctx, cancel := slackContext()
	defer cancel()
	_, ts, err := s.client.PostMessageContext(ctx, channelID, slack.MsgOptionText(text, true), slack.MsgOptionAsUser(true))
	return ts, err
}

// SendReply posts into the thread of the replied-to message.
func (s *Slack) SendReply(channelID, text, replyToID string) (string, error) {
	ctx, cancel := slackContext()
	defer cancel()
	root := s.threadRoot(ctx, channelID, replyToID)
	_, ts, err := s.client.PostMessageContext(ctx, channelID, slack.MsgOptionText(text, true), slack.MsgOptionAsUser(true),
		slack.MsgOptionTS(root))
	return ts, err
}

// EditMessage changes the text of one of our messages.
func (s *Slack) EditMessage(channelID, messageID, text string) error {
	ctx, cancel := slackContext()
	defer cancel()
	_, _, _, err := s.client.UpdateMessageContext(ctx, channelID, messageID, slack.MsgOptionText(text, true))
	return err
}

// Unsend deletes one of our messages.
func (s *Slack) Unsend(channelID, messageID string) error {
	ctx, cancel := slackContext()
	defer cancel()
	_, _, err := s.client.DeleteMessageContext(ctx, channelID, messageID)
	return err
}

// SendReaction sets our reaction on a message (replacing the one we had); an empty emoji removes it.
func (s *Slack) SendReaction(channelID, messageID, value string) error {
	ctx, cancel := slackContext()
	defer cancel()
	ref := slack.NewRefToMessage(channelID, messageID)
	key := channelID + "/" + messageID
	if previous, ok := s.ownReactions.Load(key); ok {
		if err := s.client.RemoveReactionContext(ctx, previous.(string), ref); err != nil {
			return err
		}
		s.ownReactions.Delete(key)
	}
	if value == "" {
		return nil
	}
	name := emoji.GetShortcode(value)
	if name == "" {
		return errors.New("Slack has no name for this emoji")
	}
	return s.client.AddReactionContext(ctx, name, ref)
}

// MarkRead marks a conversation read up to a message.
func (s *Slack) MarkRead(channelID, messageID string) error {
	if messageID == "" {
		return nil
	}
	ctx, cancel := slackContext()
	defer cancel()
	return s.client.MarkConversationContext(ctx, channelID, messageID)
}

// SetTyping shows "typing…" (Slack has no "stopped typing"; it times out).
func (s *Slack) SetTyping(channelID string, isTyping bool) error {
	s.lock.Lock()
	rtm := s.rtm
	s.lock.Unlock()
	if rtm == nil || !isTyping {
		return nil
	}
	rtm.SendMessage(rtm.NewTypingMessage(channelID))
	return nil
}

// SendMediaFile uploads a file into a conversation and returns the file's ID (the echo's otid).
func (s *Slack) SendMediaFile(channelID, path, fileName, caption string) (string, error) {
	defer os.Remove(path)
	info, err := os.Stat(path)
	if err != nil {
		return "", err
	}
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	if fileName == "" {
		fileName = filepath.Base(path)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	uploaded, err := s.client.UploadFileContext(ctx, slack.UploadFileParameters{
		Reader: file, FileSize: int(info.Size()), Filename: fileName, Channel: channelID, InitialComment: caption,
	})
	if err != nil {
		return "", err
	}
	return uploaded.ID, nil
}

// SendVoice uploads a recording as an audio file.
func (s *Slack) SendVoice(channelID, path, mimeType string) (string, error) {
	name := "Voice message.m4a"
	if mimeType == "audio/ogg" {
		name = "Voice message.ogg"
	}
	return s.SendMediaFile(channelID, path, name, "")
}

// RequestOlder loads messages before one (the newest when beforeMessageID is empty); they arrive as message
// events, then a range event.
func (s *Slack) RequestOlder(channelID, beforeMessageID string) error {
	ctx, cancel := slackContext()
	defer cancel()
	return s.fetchHistory(ctx, channelID, beforeMessageID)
}

func (s *Slack) fetchHistory(ctx context.Context, channelID, before string) error {
	resp, err := s.client.GetConversationHistoryContext(ctx, &slack.GetConversationHistoryParameters{
		ChannelID: channelID, Latest: before, Limit: slackPageSize,
	})
	if err != nil {
		return err
	}
	for i := range resp.Messages {
		msg := &resp.Messages[i]
		switch msg.SubType {
		case slack.MsgSubTypeChannelJoin, slack.MsgSubTypeChannelLeave, slack.MsgSubTypeGroupJoin, slack.MsgSubTypeGroupLeave:
			continue
		}
		s.emitMessage(ctx, channelID, msg)
	}
	s.emit(map[string]any{"type": "range", "threadKey": channelID, "hasMoreBefore": resp.HasMore})
	return nil
}
