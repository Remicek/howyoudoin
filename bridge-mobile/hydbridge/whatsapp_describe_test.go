package hydbridge

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

// Businesses' templates, buttons and polls show as their text rather than "unsupported".
func TestWhatsAppRichMessagesShowAsText(t *testing.T) {
	cases := map[string]*waE2E.Message{
		"Your code\nIt's 123456\nDon't share it": {TemplateMessage: &waE2E.TemplateMessage{
			HydratedTemplate: &waE2E.TemplateMessage_HydratedFourRowTemplate{
				Title:               &waE2E.TemplateMessage_HydratedFourRowTemplate_HydratedTitleText{HydratedTitleText: "Your code"},
				HydratedContentText: proto.String("It's 123456"),
				HydratedFooterText:  proto.String("Don't share it"),
			},
		}},
		"Pick a time\nMorning or evening?": {ButtonsMessage: &waE2E.ButtonsMessage{
			Header:      &waE2E.ButtonsMessage_Text{Text: "Pick a time"},
			ContentText: proto.String("Morning or evening?"),
		}},
		"📊 Lunch?":  {PollCreationMessageV3: &waE2E.PollCreationMessage{Name: proto.String("Lunch?")}},
		"👤 Ann Lee": {ContactMessage: &waE2E.ContactMessage{DisplayName: proto.String("Ann Lee")}},
		"Morning":   {ButtonsResponseMessage: &waE2E.ButtonsResponseMessage{Response: &waE2E.ButtonsResponseMessage_SelectedDisplayText{SelectedDisplayText: "Morning"}}},
	}
	for want, msg := range cases {
		if kind, text := describeWAMessage(msg); kind != "text" || text != want {
			t.Errorf("got %q %q, want %q", kind, text, want)
		}
	}
	if kind, _ := describeWAMessage(&waE2E.Message{}); kind != "other" {
		t.Errorf("an empty message is %q", kind)
	}
}

// Messages in a wrapper type we don't list, and types whose text is in fields we don't list (e.g. businesses'
// login-code templates), still show as their text.
func TestWhatsAppUnknownMessagesShowTheirText(t *testing.T) {
	wrapped := &waE2E.Message{BotForwardedMessage: &waE2E.FutureProofMessage{
		Message: &waE2E.Message{Conversation: proto.String("941212 is your verification code.")},
	}}
	if kind, text := describeWAMessage(wrapped); kind != "text" || text != "941212 is your verification code." {
		t.Errorf("wrapped: got %q %q", kind, text)
	}
	invite := &waE2E.Message{NewsletterAdminInviteMessage: &waE2E.NewsletterAdminInviteMessage{
		Caption: proto.String("Join as an admin"),
	}}
	if kind, text := describeWAMessage(invite); kind != "text" || text != "Join as an admin" {
		t.Errorf("unknown type: got %q %q", kind, text)
	}
	// Text in a quoted message isn't this message's
	quoting := &waE2E.Message{NewsletterAdminInviteMessage: &waE2E.NewsletterAdminInviteMessage{
		ContextInfo: &waE2E.ContextInfo{QuotedMessage: &waE2E.Message{Conversation: proto.String("quoted")}},
	}}
	if kind, _ := describeWAMessage(quoting); kind != "other" {
		t.Errorf("quoted text leaked: %q", kind)
	}
}

// Businesses' one-time passcodes reach linked devices only as a placeholder: kind "otp", which the app explains.
func TestWhatsAppOneTimeCodePlaceholder(t *testing.T) {
	msg := &waE2E.Message{PlaceholderMessage: &waE2E.PlaceholderMessage{
		Type: waE2E.PlaceholderMessage_MASK_LINKED_DEVICES.Enum(),
	}}
	if kind, text := describeWAMessage(msg); kind != "otp" || text != "" {
		t.Errorf("got %q %q", kind, text)
	}
}
