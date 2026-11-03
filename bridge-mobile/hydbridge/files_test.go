package hydbridge

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// Login files are encrypted once there's a storage key, and files from before still read.
func TestSecretFiles(t *testing.T) {
	dir := t.TempDir()
	old := filepath.Join(dir, "old.json")
	if err := os.WriteFile(old, []byte(`{"token":"plain"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := SetStorageKey(bytes.Repeat([]byte{7}, 32)); err != nil {
		t.Fatal(err)
	}
	defer storageCipher.Store(nil)

	if data, err := readSecretFile(old); err != nil || string(data) != `{"token":"plain"}` {
		t.Fatalf("a file from before encryption: %q %v", data, err)
	}
	if raw, _ := os.ReadFile(old); bytes.Contains(raw, []byte("plain")) {
		t.Fatal("a file from before encryption was left readable after reading it")
	}
	path := filepath.Join(dir, "session.json")
	if err := writeSecretFile(path, []byte(`{"token":"secret"}`)); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(path)
	if bytes.Contains(raw, []byte("secret")) {
		t.Fatal("the file is readable without the key")
	}
	if data, err := readSecretFile(path); err != nil || string(data) != `{"token":"secret"}` {
		t.Fatalf("read back: %q %v", data, err)
	}
	if err := SetStorageKey(bytes.Repeat([]byte{8}, 32)); err != nil {
		t.Fatal(err)
	}
	if _, err := readSecretFile(path); err == nil {
		t.Fatal("another key opened the file")
	}
	if SetStorageKey([]byte("short")) == nil {
		t.Fatal("a short key was accepted")
	}
}

func TestSafeMediaNameKeepsTraversalNamesInTheDir(t *testing.T) {
	for _, name := range []string{"", ".", ".."} {
		if got := safeMediaName(name); got == name || filepath.Base(filepath.Join("/media", got)) != got {
			t.Errorf("safeMediaName(%q) = %q, escapes the media dir", name, got)
		}
	}
	if got := safeMediaName("1234567890"); got != "1234567890" {
		t.Errorf("an ordinary ID changed: %q", got)
	}
}

func TestLimitedWriterFailsPastTheLimit(t *testing.T) {
	var buf bytes.Buffer
	w := &limitedWriter{w: &buf, limit: 4}
	if _, err := w.Write([]byte("abcd")); err != nil {
		t.Fatalf("write within the limit failed: %v", err)
	}
	if _, err := w.Write([]byte("e")); !errors.Is(err, errWriteLimit) {
		t.Fatalf("write past the limit: got %v, want errWriteLimit", err)
	}
}
