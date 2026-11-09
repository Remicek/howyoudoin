package hydbridge

import (
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
	"testing"

	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
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

// A photo's HD copy arrives as a message of its own, wrapped and linked to the original: it isn't shown twice,
// and the media inside a wrapper is what gets downloaded.
func TestWhatsAppHDCopiesAndWrappedMedia(t *testing.T) {
	photo := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Caption: proto.String("")}}
	hd := &waE2E.Message{AssociatedChildMessage: &waE2E.FutureProofMessage{Message: &waE2E.Message{
		ImageMessage: photo.ImageMessage,
		MessageContextInfo: &waE2E.MessageContextInfo{MessageAssociation: &waE2E.MessageAssociation{
			AssociationType: waE2E.MessageAssociation_HD_IMAGE_DUAL_UPLOAD.Enum(),
		}},
	}}}
	if !waIsHDCopy(hd) {
		t.Error("HD copy not recognised")
	}
	if waIsHDCopy(photo) {
		t.Error("a plain photo taken for an HD copy")
	}
	album := &waE2E.Message{ImageMessage: photo.ImageMessage, MessageContextInfo: &waE2E.MessageContextInfo{
		MessageAssociation: &waE2E.MessageAssociation{AssociationType: waE2E.MessageAssociation_MEDIA_ALBUM.Enum()},
	}}
	if waIsHDCopy(album) {
		t.Error("an album's photo taken for an HD copy")
	}
	if inner := waMediaMessage(hd); inner.GetImageMessage() == nil {
		t.Error("wrapped photo not unwrapped for download")
	}
	if kind, _ := describeWAMessage(&waE2E.Message{AlbumMessage: &waE2E.AlbumMessage{}}); kind != "album" {
		t.Errorf("album is %q", kind)
	}
}

// A sent video carries its display size (portrait videos are stored landscape with a rotation) and length.
func TestMP4Meta(t *testing.T) {
	box := func(kind string, body ...[]byte) []byte {
		var b []byte
		for _, part := range body {
			b = append(b, part...)
		}
		size := make([]byte, 4)
		binary.BigEndian.PutUint32(size, uint32(len(b)+8))
		return append(append(size, kind...), b...)
	}
	u32 := func(v uint32) []byte { b := make([]byte, 4); binary.BigEndian.PutUint32(b, v); return b }
	mvhd := box("mvhd", u32(0), u32(0), u32(0), u32(600), u32(600*13+250), make([]byte, 80))
	tkhd := func(a, b uint32, w, h uint32) []byte {
		head := append(u32(0), make([]byte, 36)...)
		matrix := append(append(u32(a), u32(b)...), make([]byte, 28)...)
		return box("tkhd", head, matrix, u32(w<<16), u32(h<<16))
	}
	audio := box("trak", tkhd(0x10000, 0, 0, 0))
	rotated := box("trak", tkhd(0, 0x10000, 1920, 1080))
	file := append(box("ftyp", []byte("isom")), box("moov", mvhd, audio, rotated)...)
	meta := mp4Meta(file)
	if meta.width != 1080 || meta.height != 1920 || meta.seconds != 13 {
		t.Errorf("got %+v", meta)
	}
	if got := mp4Meta([]byte("not a video")); got != (videoMeta{}) {
		t.Errorf("garbage gave %+v", got)
	}
}

// A reply quotes a recent photo or video whole; older ones fall out.
func TestWhatsAppRecentMedia(t *testing.T) {
	var r waRecentMedia
	video := &waE2E.Message{VideoMessage: &waE2E.VideoMessage{Mimetype: proto.String("video/mp4")}}
	r.put("v", &waE2E.Message{EphemeralMessage: &waE2E.FutureProofMessage{Message: video}})
	r.put("t", &waE2E.Message{Conversation: proto.String("hi")})
	if r.get("v").GetVideoMessage() == nil || r.get("t") != nil {
		t.Fatalf("got %v / %v", r.get("v"), r.get("t"))
	}
	for i := 0; i < waRecentMediaLimit; i++ {
		r.put(fmt.Sprint(i), video)
	}
	if r.get("v") != nil || len(r.order) != waRecentMediaLimit {
		t.Errorf("kept %d, oldest still there: %v", len(r.order), r.get("v") != nil)
	}
}

// Instagram's videos carry their length only in the DASH manifest.
func TestDashDurationMs(t *testing.T) {
	for manifest, want := range map[string]int{
		`<MPD mediaPresentationDuration="PT6.016S" minBufferTime="PT1S">`: 6016,
		`<MPD mediaPresentationDuration="PT1M2.5S">`:                      62500,
		`<MPD mediaPresentationDuration="PT1H0M0S">`:                      3600000,
		`no manifest`: 0,
	} {
		if got := dashDurationMs(manifest); got != want {
			t.Errorf("%q: got %d, want %d", manifest, got, want)
		}
	}
}

// Facebook's consent page shows as what to do, not the raw redirect.
func TestFriendlyMetaError(t *testing.T) {
	consent := fmt.Errorf("failed to send request: %w", httpclient.RedirectedError{Type: httpclient.ErrConsentRequired, URL: "https://www.messenger.com/privacy/consent/"})
	if got := friendlyMetaError(consent, "messenger.com").Error(); !strings.Contains(got, "cookie choices") || strings.Contains(got, "https://") {
		t.Errorf("consent: %q", got)
	}
	if got := metaActionURL(consent); got != "https://www.messenger.com/privacy/consent/" {
		t.Errorf("action URL: %q", got)
	}
	if got := friendlyMetaError(errors.New("boom"), "messenger.com").Error(); got != "failed to load inbox: boom" {
		t.Errorf("other: %q", got)
	}
}
