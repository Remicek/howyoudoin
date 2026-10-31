package hydbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// ChatClient is one network logged in from its web page or with a password (Bluesky, LinkedIn, Discord, Google
// Voice, X), as the app calls it: every network has the same methods, so the app needs one binding for all of them.
// What a network can't do fails with ErrUnsupported.
type ChatClient interface {
	Connect() error
	Disconnect()
	SendText(threadKey, text string) (string, error)
	SendReply(threadKey, text, replyToID string) (string, error)
	EditMessage(threadKey, messageID, text string) error
	Unsend(threadKey, messageID string) error
	SendReaction(threadKey, messageID, emoji string) error
	MarkRead(threadKey, messageID string) error
	SetTyping(threadKey string, isTyping bool) error
	SendMediaFile(threadKey, path, mimeType, fileName, caption string) (string, error)
	RequestOlder(threadKey, beforeMessageID string) error
	// SearchPeople looks for people on the network (by name or handle) and returns them as a JSON array of
	// {"id", "name", "handle", "avatarUrl"}.
	SearchPeople(query string) (string, error)
	// StartChat opens the one-to-one chat with a person found by SearchPeople (sending its thread event) and
	// returns its thread key.
	StartChat(personID string) (string, error)
	// AnswerCaptcha takes the token of a CAPTCHA a request failed for (its error starts with "captcha_required:"
	// and the CAPTCHA as JSON); the request is then made again.
	AnswerCaptcha(token string) error
}

// foundPerson is one SearchPeople result.
type foundPerson struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Handle    string `json:"handle,omitempty"`
	AvatarURL string `json:"avatarUrl,omitempty"`
}

func peopleJSON(people []foundPerson) (string, error) {
	if people == nil {
		people = []foundPerson{}
	}
	data, err := json.Marshal(people)
	return string(data), err
}

// ErrUnsupported is what a ChatClient answers for something its network doesn't have.
var ErrUnsupported = errors.New("not supported on this network")

// NewChatClient makes the client for a network by its name ("bluesky", "linkedin", "discord", "googlevoice")
// from the login it was given (a JSON object; see each network's constructor).
func NewChatClient(network, credentialsJSON, dataDir string, listener Listener) (ChatClient, error) {
	switch network {
	case "bluesky":
		b, err := NewBluesky(credentialsJSON, dataDir, listener)
		if err != nil {
			return nil, err
		}
		return blueskyChat{b}, nil
	case "linkedin":
		l, err := NewLinkedIn(credentialsJSON, dataDir, listener)
		if err != nil {
			return nil, err
		}
		return linkedInChat{l}, nil
	case "discord":
		return NewDiscord(credentialsJSON, dataDir, listener)
	case "twitter":
		return NewTwitter(credentialsJSON, dataDir, listener)
	case "googlevoice":
		g, err := NewGoogleVoice(credentialsJSON, dataDir, listener)
		if err != nil {
			return nil, err
		}
		return googleVoiceChat{g}, nil
	}
	return nil, fmt.Errorf("unknown network %q", network)
}

// Bluesky chats have no replies, edits, typing or attachments.
type blueskyChat struct{ *Bluesky }

func (c blueskyChat) SendReply(threadKey, text, _ string) (string, error) {
	return c.SendText(threadKey, text)
}
func (blueskyChat) EditMessage(_, _, _ string) error                   { return ErrUnsupported }
func (blueskyChat) SetTyping(_ string, _ bool) error                   { return nil }
func (blueskyChat) SendMediaFile(_, _, _, _, _ string) (string, error) { return "", ErrUnsupported }
func (blueskyChat) AnswerCaptcha(_ string) error                       { return ErrUnsupported }

// LinkedIn marks a whole conversation read.
type linkedInChat struct{ *LinkedIn }

func (c linkedInChat) MarkRead(threadKey, _ string) error { return c.LinkedIn.MarkRead(threadKey) }
func (linkedInChat) AnswerCaptcha(_ string) error         { return ErrUnsupported }

// Google Voice texts can't be replied to, edited, unsent or reacted to, and only photos can be sent.
type googleVoiceChat struct{ *GoogleVoice }

func (c googleVoiceChat) SendReply(threadKey, text, _ string) (string, error) {
	return c.SendText(threadKey, text)
}
func (googleVoiceChat) EditMessage(_, _, _ string) error  { return ErrUnsupported }
func (googleVoiceChat) Unsend(_, _ string) error          { return ErrUnsupported }
func (googleVoiceChat) SendReaction(_, _, _ string) error { return ErrUnsupported }
func (googleVoiceChat) SetTyping(_ string, _ bool) error  { return nil }
func (c googleVoiceChat) MarkRead(threadKey, _ string) error {
	return c.GoogleVoice.MarkRead(threadKey)
}
func (c googleVoiceChat) SendMediaFile(threadKey, path, mimeType, _, caption string) (string, error) {
	return c.GoogleVoice.SendMediaFile(threadKey, path, mimeType, caption)
}
func (googleVoiceChat) SearchPeople(_ string) (string, error) { return "", ErrUnsupported }
func (googleVoiceChat) StartChat(_ string) (string, error)    { return "", ErrUnsupported }
func (googleVoiceChat) AnswerCaptcha(_ string) error          { return ErrUnsupported }

var (
	_ ChatClient = blueskyChat{}
	_ ChatClient = linkedInChat{}
	_ ChatClient = (*Discord)(nil)
	_ ChatClient = googleVoiceChat{}
)

// phoneDigits is a phone number as its digits (with the country code), or "" when the query isn't one.
func phoneDigits(query string) string {
	var digits strings.Builder
	for _, r := range strings.TrimSpace(query) {
		switch {
		case r >= '0' && r <= '9':
			digits.WriteRune(r)
		case r == '+' && digits.Len() == 0, r == ' ', r == '-', r == '(', r == ')', r == '.':
		default:
			return ""
		}
	}
	if digits.Len() < 7 || digits.Len() > 15 {
		return ""
	}
	return digits.String()
}
