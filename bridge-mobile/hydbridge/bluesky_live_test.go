package hydbridge

import (
	"os"
	"strings"
	"testing"
)

// Against the real bsky.social with a login that can't work: checks that sign-in errors read as intended.
// Runs only with HYD_LIVE=1.
func TestBlueskyLiveWrongLogin(t *testing.T) {
	if os.Getenv("HYD_LIVE") != "1" {
		t.Skip("set HYD_LIVE=1 to call the real Bluesky server")
	}
	b, err := NewBluesky(`{"identifier":"howyoudoin-test-no-such-user.bsky.social","password":"aaaa-bbbb-cccc-dddd"}`,
		t.TempDir(), &recordingListener{})
	if err != nil {
		t.Fatal(err)
	}
	err = b.Connect()
	if err == nil || !strings.Contains(err.Error(), "Wrong Bluesky handle or app password") {
		t.Fatalf("got %v", err)
	}
}
