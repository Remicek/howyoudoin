package hydbridge

import (
	"os"
	"strings"
	"testing"
)

// Against the real linkedin.com with cookies that can't work: the login must fail as an expired session.
// Runs only with HYD_LIVE=1.
func TestLinkedInLiveBadCookies(t *testing.T) {
	if os.Getenv("HYD_LIVE") != "1" {
		t.Skip("set HYD_LIVE=1 to call the real LinkedIn server")
	}
	l, err := NewLinkedIn(`{"li_at":"AQEDAThisIsNotAValidSession","JSESSIONID":"\"ajax:0000000000000000000\""}`,
		t.TempDir(), &recordingListener{})
	if err != nil {
		t.Fatal(err)
	}
	err = l.Connect()
	if err == nil || !strings.Contains(err.Error(), "LinkedIn session expired") {
		t.Fatalf("got %v", err)
	}
	t.Log(err)
}

func TestLinkedInNeedsBothCookies(t *testing.T) {
	if _, err := NewLinkedIn(`{"li_at":"x"}`, t.TempDir(), nil); err == nil {
		t.Fatal("a login without JSESSIONID was accepted")
	}
}
