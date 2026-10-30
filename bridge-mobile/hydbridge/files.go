package hydbridge

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	cryptorand "crypto/rand"
	"errors"
	"fmt"
	"math/rand/v2"
	"os"
	"sync/atomic"
)

// partPath is a temporary name next to path for one writer. Downloads go there and are renamed into place when
// complete, so a file at path is always whole: a second download of the same media (e.g. our own sent photo, seen
// both as the send's echo and as the network's update) never shows a half-written file.
func partPath(path string) string {
	return fmt.Sprintf("%s.part-%d", path, rand.Int64())
}

// writeFileAtomic writes data to path so that it appears all at once.
func writeFileAtomic(path string, data []byte) error {
	tmp := partPath(path)
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

// Login files (sessions, cookies, encryption keys) are encrypted with a key the app keeps in the platform's key
// store (Android: Keystore) and hands over at start with SetStorageKey; without one (tests, iOS so far) they're
// written as they are. Files from before encryption are still read, and written encrypted the next time.
var storageCipher atomic.Pointer[cipher.AEAD]

// secretFileMagic starts an encrypted login file; then come the GCM nonce and the sealed data.
var secretFileMagic = []byte("HYDENC1\n")

// SetStorageKey sets the 32-byte key login files are encrypted with. Call it once, before any client is created.
func SetStorageKey(key []byte) error {
	if len(key) != 32 {
		return errors.New("the storage key must be 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	storageCipher.Store(&aead)
	return nil
}

// writeSecretFile writes a login file, encrypted when there's a storage key.
func writeSecretFile(path string, data []byte) error {
	if aead := storageCipher.Load(); aead != nil {
		nonce := make([]byte, (*aead).NonceSize())
		if _, err := cryptorand.Read(nonce); err != nil {
			return err
		}
		sealed := append(append(append([]byte{}, secretFileMagic...), nonce...), (*aead).Seal(nil, nonce, data, nil)...)
		return writeFileAtomic(path, sealed)
	}
	return writeFileAtomic(path, data)
}

// readSecretFile reads a login file written by writeSecretFile, or one from before encryption.
func readSecretFile(path string) ([]byte, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	aead := storageCipher.Load()
	if !bytes.HasPrefix(data, secretFileMagic) {
		// From before encryption: encrypted now, so it doesn't wait for the client's next save
		if aead != nil {
			_ = writeSecretFile(path, data)
		}
		return data, nil
	}
	if aead == nil {
		return nil, errors.New("the login file is encrypted, but there's no storage key")
	}
	sealed := data[len(secretFileMagic):]
	size := (*aead).NonceSize()
	if len(sealed) < size {
		return nil, errors.New("the login file is damaged")
	}
	return (*aead).Open(nil, sealed[:size], sealed[size:], nil)
}
