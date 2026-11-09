package hydbridge

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"

	"go.mau.fi/whatsmeow/proto/waMediaTransport"
	wasocket "go.mau.fi/whatsmeow/socket"
	"go.mau.fi/whatsmeow/util/cbcutil"
	"go.mau.fi/whatsmeow/util/hkdfutil"
	"google.golang.org/protobuf/proto"
)

// Messenger's apps show a video's frame from a separately uploaded preview image, encrypted with its own media key
// under this HKDF info (found in Messenger's native code); they ignore the inline JPEGThumbnail.
const messengerPreviewKeys = "Messenger Preview Keys"

// Upload types to try for the preview, in order; the first one the server accepts is remembered.
var previewUploadTypes = []string{"preview", "thumbnail-video", "image"}

var previewHTTP = &http.Client{Timeout: 60 * time.Second}

// uploadPreview encrypts and uploads a video's preview JPEG the way Messenger's apps do and returns the
// downloadable thumbnail that goes into the video's transport.
func (m *Messenger) uploadPreview(ctx context.Context, jpeg []byte) (*waMediaTransport.WAMediaTransport_Ancillary_Thumbnail_DownloadableThumbnail, error) {
	mediaKey := make([]byte, 32)
	if _, err := rand.Read(mediaKey); err != nil {
		return nil, err
	}
	upload, err := encryptPreview(jpeg, mediaKey)
	if err != nil {
		return nil, err
	}
	fileSHA256 := sha256.Sum256(jpeg)
	encSHA256 := sha256.Sum256(upload)

	conn, err := m.e2ee.DangerousInternals().RefreshMediaConn(ctx, false)
	if err != nil {
		return nil, fmt.Errorf("media connection: %w", err)
	}
	if len(conn.Hosts) == 0 {
		return nil, fmt.Errorf("no media hosts")
	}
	// Messenger uploads only work on the last host (rupload.facebook.com), as in whatsmeow
	host := conn.Hosts[len(conn.Hosts)-1].Hostname
	token := base64.URLEncoding.EncodeToString(encSHA256[:])
	types := previewUploadTypes
	if m.previewUploadType != "" {
		types = []string{m.previewUploadType}
	}
	var lastErr error
	for _, mmsType := range types {
		u := url.URL{
			Scheme:   "https",
			Host:     host,
			Path:     fmt.Sprintf("/wa-msgr/mms/%s/%s", mmsType, token),
			RawQuery: url.Values{"auth": {conn.Auth}, "token": {token}}.Encode(),
		}
		var resp struct {
			DirectPath string `json:"direct_path"`
			ObjectID   string `json:"object_id"`
		}
		if lastErr = postPreview(ctx, u.String(), upload, &resp); lastErr != nil {
			m.log.Warn().Err(lastErr).Str("type", mmsType).Msg("Preview upload refused")
			continue
		}
		if m.previewUploadType == "" {
			m.log.Info().Str("type", mmsType).Msg("Preview upload accepted")
		}
		m.previewUploadType = mmsType
		return &waMediaTransport.WAMediaTransport_Ancillary_Thumbnail_DownloadableThumbnail{
			FileSHA256:        fileSHA256[:],
			FileEncSHA256:     encSHA256[:],
			DirectPath:        proto.String(resp.DirectPath),
			MediaKey:          mediaKey,
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
			ObjectID:          proto.String(resp.ObjectID),
		}, nil
	}
	return nil, lastErr
}

// previewKeys expands a preview's media key into its IV, AES key and MAC key.
func previewKeys(mediaKey []byte) (iv, cipherKey, macKey []byte) {
	expanded := hkdfutil.SHA256(mediaKey, nil, []byte(messengerPreviewKeys), 112)
	return expanded[:16], expanded[16:48], expanded[48:80]
}

// encryptPreview returns what gets uploaded: AES-CBC ciphertext followed by the first 10 bytes of its HMAC.
func encryptPreview(jpeg, mediaKey []byte) ([]byte, error) {
	iv, cipherKey, macKey := previewKeys(mediaKey)
	ciphertext, err := cbcutil.Encrypt(cipherKey, iv, jpeg)
	if err != nil {
		return nil, err
	}
	mac := hmac.New(sha256.New, macKey)
	mac.Write(iv)
	mac.Write(ciphertext)
	return append(ciphertext, mac.Sum(nil)[:10]...), nil
}

func postPreview(ctx context.Context, uploadURL string, data []byte, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, uploadURL, bytes.NewReader(data))
	if err != nil {
		return err
	}
	req.ContentLength = int64(len(data))
	req.Header.Set("Origin", wasocket.Origin)
	req.Header.Set("Referer", wasocket.Origin+"/")
	resp, err := previewHTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	if err = json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("bad upload response: %w", err)
	}
	return nil
}
