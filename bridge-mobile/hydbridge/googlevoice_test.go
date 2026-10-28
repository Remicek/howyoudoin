package hydbridge

import (
	"os"
	"strings"
	"testing"

	"go.mau.fi/mautrix-gvoice/pkg/libgv/gvproto"
)

func TestGoogleVoiceDescribesCalls(t *testing.T) {
	missed := &gvproto.Message{Type: gvproto.Message_MISSED_CALL}
	if got := googleVoiceCallText(missed); got != "📞 Missed call" {
		t.Errorf("missed call: %q", got)
	}
	voicemail := &gvproto.Message{Type: gvproto.Message_VOICEMAIL, DurationSeconds: 75,
		Transcript: &gvproto.Message_Transcript{Tokens: []*gvproto.Message_TranscriptToken{
			{Text: []byte("call")}, {Text: []byte("me")}, {Text: []byte("back")},
		}}}
	if got := googleVoiceCallText(voicemail); got != "🎙 Voicemail (1:15): call me back" {
		t.Errorf("voicemail: %q", got)
	}
}

func TestGoogleVoiceNeedsTheSessionCookies(t *testing.T) {
	if _, err := NewGoogleVoice(`{"SID":"x"}`, t.TempDir(), nil); err == nil {
		t.Fatal("a login without HSID, SSID, APISID and SAPISID was accepted")
	}
}

// Against the real voice.google.com with cookies that can't work. Runs only with HYD_LIVE=1.
func TestGoogleVoiceLiveBadCookies(t *testing.T) {
	if os.Getenv("HYD_LIVE") != "1" {
		t.Skip("set HYD_LIVE=1 to call the real Google Voice server")
	}
	g, err := NewGoogleVoice(`{"SID":"a","HSID":"b","SSID":"c","APISID":"d","SAPISID":"e"}`, t.TempDir(),
		&recordingListener{})
	if err != nil {
		t.Fatal(err)
	}
	err = g.Connect()
	if err == nil || !strings.Contains(err.Error(), "Google Voice") {
		t.Fatalf("got %v", err)
	}
	t.Log(err)
}
