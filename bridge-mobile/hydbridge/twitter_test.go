package hydbridge

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"go.mau.fi/mautrix-twitter/pkg/twittermeow/crypto"
)

func TestTwitterKeyStoreKeepsKeysOnDisk(t *testing.T) {
	path := filepath.Join(t.TempDir(), "keys.json")
	ctx := context.Background()
	store, err := loadTwitterKeyStore(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, v := range []string{"9", "10", "2"} {
		if err := store.PutConversationKey(ctx, &crypto.ConversationKey{ConversationID: "c1", KeyVersion: v, Key: []byte(v)}); err != nil {
			t.Fatal(err)
		}
	}
	if err := store.PutConversationToken(ctx, "c1", "token"); err != nil {
		t.Fatal(err)
	}
	reloaded, err := loadTwitterKeyStore(path)
	if err != nil {
		t.Fatal(err)
	}
	latest, err := reloaded.GetLatestConversationKey(ctx, "c1")
	if err != nil || latest.KeyVersion != "10" {
		t.Fatalf("latest key: %v %v", latest, err)
	}
	if token, _ := reloaded.GetConversationToken(ctx, "c1"); token != "token" {
		t.Fatalf("token %q", token)
	}
	if reloaded.hasOwnKeys() {
		t.Fatal("no own keys were stored")
	}
	if _, err := reloaded.GetOwnSigningKey(ctx); err != crypto.ErrKeyNotFound {
		t.Fatalf("own key: %v", err)
	}
}

func TestTwitterSplitsTheRecoveredSecret(t *testing.T) {
	secret := make([]byte, 64)
	for i := range secret {
		secret[i] = byte(i)
	}
	decrypt, signing := twitterSplitSecret(secret)
	if decrypt == "" || signing == "" || decrypt == signing {
		t.Fatalf("got %q %q", decrypt, signing)
	}
	if d, s := twitterSplitSecret(secret[:32]); d == "" || s != "" {
		t.Fatalf("a single key: %q %q", d, s)
	}
}

// Against the real x.com with cookies that can't work. Runs only with HYD_LIVE=1.
func TestTwitterLiveBadCookies(t *testing.T) {
	if os.Getenv("HYD_LIVE") != "1" {
		t.Skip("set HYD_LIVE=1 to call the real X server")
	}
	x, err := NewTwitter(`{"auth_token":"0000000000000000000000000000000000000000","ct0":"0000","passcode":"1234"}`,
		t.TempDir(), &recordingListener{})
	if err != nil {
		t.Fatal(err)
	}
	err = x.Connect()
	if err == nil || !strings.Contains(err.Error(), twitterAuthFailedText) {
		t.Fatalf("got %v", err)
	}
	t.Log(err)
}
