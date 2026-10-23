package hydbridge

import (
	"encoding/json"

	"maunium.net/go/mautrix/crypto/attachment"
)

// EncryptedAttachment is a file encrypted for an encrypted Matrix room: upload Ciphertext, then send FileJSON (key,
// iv, hashes) with the uploaded mxc:// URL added as "url", in the event's "file" field.
type EncryptedAttachment struct {
	Ciphertext []byte
	FileJSON   string
}

// EncryptAttachment encrypts a file with a new random key (AES-256-CTR, SHA-256 of the ciphertext).
func EncryptAttachment(data []byte) (*EncryptedAttachment, error) {
	file := attachment.NewEncryptedFile()
	ciphertext := make([]byte, len(data))
	copy(ciphertext, data)
	file.EncryptInPlace(ciphertext)
	fileJSON, err := json.Marshal(file)
	if err != nil {
		return nil, err
	}
	return &EncryptedAttachment{Ciphertext: ciphertext, FileJSON: string(fileJSON)}, nil
}

// DecryptAttachment decrypts a downloaded file with the "file" object of its event; fails if the hash doesn't match.
func DecryptAttachment(ciphertext []byte, fileJSON string) ([]byte, error) {
	var file attachment.EncryptedFile
	if err := json.Unmarshal([]byte(fileJSON), &file); err != nil {
		return nil, err
	}
	data := make([]byte, len(ciphertext))
	copy(data, ciphertext)
	if err := file.DecryptInPlace(data); err != nil {
		return nil, err
	}
	return data, nil
}
