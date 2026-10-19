package hydbridge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/mautrix-meta/pkg/messagix/socket"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waConsumerApplication"
	"go.mau.fi/whatsmeow/proto/waMediaTransport"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// SendMediaFile sends the file at path (written by the app into its private storage) as a photo, video, voice
// message or file, depending on mimeType, and returns the ID the echoed message carries. Regular chats upload to
// Messenger and reference the upload; encrypted chats upload encrypted media like WhatsApp does.
func (m *Messenger) SendMediaFile(threadKey string, isEncrypted, isGroup bool, path, mimeType, fileName, caption string, width, height int) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	if isEncrypted {
		return m.sendEncryptedMedia(threadKey, isGroup, path, data, mimeType, fileName, caption, width, height)
	}
	defer os.Remove(path)
	key, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return "", fmt.Errorf("invalid thread key: %w", err)
	}
	ctx := context.Background()
	upload, err := m.client.GetHTTP().SendMercuryUploadRequest(ctx, key, &httpclient.MercuryUploadMedia{
		Filename:  fileName,
		MimeType:  mimeType,
		MediaData: data,
	})
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	attachmentID := upload.Payload.RealMetadata.GetFbId()
	if attachmentID == 0 {
		return "", fmt.Errorf("upload failed: no attachment ID")
	}
	otid := methods.GenerateEpochID()
	resp, err := m.client.ExecuteTasks(ctx, &socket.SendMessageTask{
		ThreadId:         key,
		Otid:             otid,
		Source:           table.MESSENGER_INBOX_IN_THREAD,
		InitiatingSource: table.FACEBOOK_INBOX,
		SendType:         table.MEDIA,
		SyncGroup:        1,
		Text:             caption,
		AttachmentFBIds:  []int64{attachmentID},
	}, &socket.ThreadMarkReadTask{
		ThreadId:            key,
		LastReadWatermarkTs: time.Now().UnixMilli(),
		SyncGroup:           1,
	})
	if err != nil {
		return "", err
	}
	// The echo carries the CDN copy of the attachment
	m.emitTable(resp)
	return strconv.FormatInt(otid, 10), nil
}

func (m *Messenger) sendEncryptedMedia(threadKey string, isGroup bool, path string, data []byte, mimeType, fileName, caption string, width, height int) (string, error) {
	if m.e2ee == nil {
		return "", errNotConnectedE2EE
	}
	kind, mediaType := mediaKind(mimeType)
	ctx := context.Background()
	up, err := m.e2ee.Upload(ctx, data, mediaType)
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	// Messenger apps refuse to show media without thumbnail dimensions
	thumbW, thumbH := clampTo400(width, height)
	if thumbW == 0 {
		thumbW, thumbH = 400, 400
	}
	transport := &waMediaTransport.WAMediaTransport{
		Integral: &waMediaTransport.WAMediaTransport_Integral{
			FileSHA256:        up.FileSHA256,
			MediaKey:          up.MediaKey,
			FileEncSHA256:     up.FileEncSHA256,
			DirectPath:        &up.DirectPath,
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
		},
		Ancillary: &waMediaTransport.WAMediaTransport_Ancillary{
			FileLength: proto.Uint64(uint64(len(data))),
			Mimetype:   &mimeType,
			Thumbnail: &waMediaTransport.WAMediaTransport_Ancillary_Thumbnail{
				ThumbnailWidth:  proto.Uint32(uint32(thumbW)),
				ThumbnailHeight: proto.Uint32(uint32(thumbH)),
			},
			ObjectID: &up.ObjectID,
		},
	}
	var captionText *waCommon.MessageText
	if caption != "" {
		captionText = &waCommon.MessageText{Text: &caption}
	}
	var content waConsumerApplication.ConsumerApplication_Content_Content
	switch kind {
	case "image":
		msg := &waConsumerApplication.ConsumerApplication_ImageMessage{Caption: captionText}
		err = msg.Set(&waMediaTransport.ImageTransport{
			Integral:  &waMediaTransport.ImageTransport_Integral{Transport: transport},
			Ancillary: &waMediaTransport.ImageTransport_Ancillary{Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height))},
		})
		content = &waConsumerApplication.ConsumerApplication_Content_ImageMessage{ImageMessage: msg}
	case "video":
		msg := &waConsumerApplication.ConsumerApplication_VideoMessage{Caption: captionText}
		err = msg.Set(&waMediaTransport.VideoTransport{
			Integral:  &waMediaTransport.VideoTransport_Integral{Transport: transport},
			Ancillary: &waMediaTransport.VideoTransport_Ancillary{Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height))},
		})
		content = &waConsumerApplication.ConsumerApplication_Content_VideoMessage{VideoMessage: msg}
	case "audio":
		msg := &waConsumerApplication.ConsumerApplication_AudioMessage{PTT: proto.Bool(false)}
		err = msg.Set(&waMediaTransport.AudioTransport{
			Integral:  &waMediaTransport.AudioTransport_Integral{Transport: transport},
			Ancillary: &waMediaTransport.AudioTransport_Ancillary{},
		})
		content = &waConsumerApplication.ConsumerApplication_Content_AudioMessage{AudioMessage: msg}
	default:
		msg := &waConsumerApplication.ConsumerApplication_DocumentMessage{FileName: &fileName}
		err = msg.Set(&waMediaTransport.DocumentTransport{
			Integral:  &waMediaTransport.DocumentTransport_Integral{Transport: transport},
			Ancillary: &waMediaTransport.DocumentTransport_Ancillary{},
		})
		content = &waConsumerApplication.ConsumerApplication_Content_DocumentMessage{DocumentMessage: msg}
	}
	if err != nil {
		return "", fmt.Errorf("failed to build the message: %w", err)
	}
	resp, err := m.e2ee.SendFBMessage(ctx, types.NewJID(threadKey, chatServer(isGroup)), &waConsumerApplication.ConsumerApplication{
		Payload: &waConsumerApplication.ConsumerApplication_Payload{
			Payload: &waConsumerApplication.ConsumerApplication_Payload_Content{
				Content: &waConsumerApplication.ConsumerApplication_Content{Content: content},
			},
		},
	}, nil)
	if err != nil {
		return "", err
	}
	// The encrypted protocol doesn't echo our own messages
	m.emit(map[string]any{
		"type": "message", "threadKey": threadKey, "id": resp.ID, "otid": resp.ID, "senderId": m.SelfID(),
		"text": caption, "kind": kind, "timestampMs": resp.Timestamp.UnixMilli(), "isEncrypted": true,
	})
	// Keep our copy as the echo's media
	dir := filepath.Join(m.dataDir, "media")
	if os.MkdirAll(dir, 0o700) == nil {
		sum := sha256.Sum256([]byte(threadKey + "/" + resp.ID))
		cached := filepath.Join(dir, hex.EncodeToString(sum[:16]))
		if os.Rename(path, cached) == nil {
			m.emit(map[string]any{
				"type": "attachment", "threadKey": threadKey, "messageId": resp.ID, "kind": kind,
				"url": "file://" + cached, "previewUrl": "file://" + cached, "mimeType": mimeType, "fileName": fileName,
				"width": width, "height": height,
			})
		}
	}
	return resp.ID, nil
}

func mediaKind(mimeType string) (string, whatsmeow.MediaType) {
	switch {
	case strings.HasPrefix(mimeType, "image/"):
		return "image", whatsmeow.MediaImage
	case strings.HasPrefix(mimeType, "video/"):
		return "video", whatsmeow.MediaVideo
	case strings.HasPrefix(mimeType, "audio/"):
		return "audio", whatsmeow.MediaAudio
	}
	return "file", whatsmeow.MediaDocument
}

func clampTo400(w, h int) (int, int) {
	if w <= 400 && h <= 400 {
		return w, h
	}
	if w > h {
		return 400, h * 400 / w
	}
	return w * 400 / h, 400
}
