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
