package hydbridge

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"testing"

	"go.mau.fi/whatsmeow/util/cbcutil"
)

// A video preview uploaded by Messenger's own Android app (586.0.0.55.92), with the media key and plaintext hash
// from its message: it must decrypt with the same derivation we use for ours.
const (
	officialPreviewKey    = "7f1d8aa3c2b646dd61e79900ac3dbb8fbcd05a1756a935df8ad43a3454d8bb2e"
	officialPreviewSHA256 = "a9799860f863721904955dd9c8ff53100215d02075627ae8994c29a47c38df87"
)

func decryptPreview(t *testing.T, data, mediaKey []byte) []byte {
	t.Helper()
	iv, cipherKey, macKey := previewKeys(mediaKey)
	ciphertext, gotMAC := data[:len(data)-10], data[len(data)-10:]
	mac := hmac.New(sha256.New, macKey)
	mac.Write(iv)
	mac.Write(ciphertext)
	if !hmac.Equal(mac.Sum(nil)[:10], gotMAC) {
		t.Fatal("MAC doesn't match")
	}
	plain, err := cbcutil.Decrypt(cipherKey, iv, ciphertext)
	if err != nil {
		t.Fatal(err)
	}
	return plain
}

func TestOfficialMessengerPreviewDecrypts(t *testing.T) {
	data, err := os.ReadFile("testdata/messenger_preview.enc")
	if err != nil {
		t.Fatal(err)
	}
	key, _ := hex.DecodeString(officialPreviewKey)
	plain := decryptPreview(t, data, key)
	sum := sha256.Sum256(plain)
	if hex.EncodeToString(sum[:]) != officialPreviewSHA256 || !bytes.HasPrefix(plain, []byte{0xff, 0xd8}) {
		t.Fatal("not the original JPEG")
	}
}

func TestEncryptPreviewRoundTrip(t *testing.T) {
	jpeg := []byte{0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17}
	key := bytes.Repeat([]byte{7}, 32)
	upload, err := encryptPreview(jpeg, key)
	if err != nil {
		t.Fatal(err)
	}
	if got := decryptPreview(t, upload, key); !bytes.Equal(got, jpeg) {
		t.Fatalf("round trip changed the data: %x", got)
	}
}
