package hydbridge

import (
	"os"
	"strings"
	"testing"

	"go.mau.fi/mautrix-linkedin/pkg/linkedingo"
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

// The cookies LinkedIn refreshed are used again for the same login, but a new login starts from its own.
func TestLinkedInKeepsRefreshedCookiesForTheSameLogin(t *testing.T) {
	dir := t.TempDir()
	login := `{"li_at":"first","JSESSIONID":"\"ajax:1\""}`
	l, err := NewLinkedIn(login, dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	refreshed, err := linkedingo.NewJarFromCookieHeader(`li_at=first; JSESSIONID="ajax:1"; lidc=refreshed`)
	if err != nil {
		t.Fatal(err)
	}
	l.jar = refreshed
	l.saveCookies()

	again, err := NewLinkedIn(login, dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := again.jar.GetCookie("lidc"); got != "refreshed" {
		t.Fatalf("the refreshed cookie wasn't used again: %q", got)
	}
	other, err := NewLinkedIn(`{"li_at":"second","JSESSIONID":"\"ajax:2\""}`, dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := other.jar.GetCookie("li_at"); got != "second" {
		t.Fatalf("a new login used the old cookies: %q", got)
	}
}

// A chat with someone new is a new-chat key until its first message creates it; known chats are used as they are.
func TestLinkedInNewChatKeys(t *testing.T) {
	l, err := NewLinkedIn(`{"li_at":"x","JSESSIONID":"\"ajax:1\""}`, t.TempDir(), nil)
	if err != nil {
		t.Fatal(err)
	}
	l.directChats.Store("known", "urn:li:msg_conversation:(1,2)")
	if key, _ := l.StartChat("known"); key != "urn:li:msg_conversation:(1,2)" {
		t.Fatalf("an existing chat wasn't reused: %q", key)
	}
	key, _ := l.StartChat("someone")
	if key != "new:someone" {
		t.Fatalf("got %q", key)
	}
	if resolved, person := l.resolveKey(key); resolved != key || person != "someone" {
		t.Fatalf("before the first message: %q %q", resolved, person)
	}
	l.started.Store(key, "urn:li:msg_conversation:(3,4)")
	if resolved, person := l.resolveKey(key); resolved != "urn:li:msg_conversation:(3,4)" || person != "" {
		t.Fatalf("after the first message: %q %q", resolved, person)
	}
}
