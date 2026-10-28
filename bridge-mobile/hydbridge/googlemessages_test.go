package hydbridge

import (
	"os"
	"strings"
	"testing"
)

func TestGoogleMessagesIsNotLinkedWithoutAPairing(t *testing.T) {
	g, err := NewGoogleMessages(t.TempDir(), &recordingListener{})
	if err != nil {
		t.Fatal(err)
	}
	if g.IsLinked() {
		t.Fatal("a fresh client says it's paired")
	}
	if err := g.Connect(); err == nil {
		t.Fatal("connecting without a pairing worked")
	}
}

// Asks Google's real pairing service for a QR code (no account needed). Runs only with HYD_LIVE=1.
func TestGoogleMessagesLiveShowsAPairingQRCode(t *testing.T) {
	if os.Getenv("HYD_LIVE") != "1" {
		t.Skip("set HYD_LIVE=1 to call the real Google Messages service")
	}
	events := &recordingListener{}
	g, err := NewGoogleMessages(t.TempDir(), events)
	if err != nil {
		t.Fatal(err)
	}
	if err := g.StartLinking(); err != nil {
		t.Fatal(err)
	}
	defer g.CancelLinking()
	pairing := events.waitFor(t, "pairing", nil)
	qr, _ := pairing["qr"].(string)
	if !strings.HasPrefix(qr, "https://support.google.com/messages/?p=web_computer#") {
		t.Fatalf("unexpected QR payload %q", qr)
	}
}
