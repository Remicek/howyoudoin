package hydbridge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waConsumerApplication"
	"go.mau.fi/whatsmeow/proto/waMediaTransport"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

const maxEncryptedMediaBytes = 50 * 1024 * 1024

// downloadEncryptedMedia decrypts an encrypted chat's attachment into dataDir/media and emits an attachment
// event pointing at the file. paths overrides download paths by object ID (restored history provides them).
func (m *Messenger) downloadEncryptedMedia(threadKey, messageID string, content *waConsumerApplication.ConsumerApplication_Content, paths map[string]string) {
	if m.e2ee == nil || m.dataDir == "" || messageID == "" {
		return
	}
	var transport *waMediaTransport.WAMediaTransport
	var mediaType whatsmeow.MediaType
	var kind, fileName string
	var width, height, seconds uint32
	switch {
	case content.GetImageMessage() != nil:
		t, err := content.GetImageMessage().Decode()
		if err != nil {
			return
		}
		transport, mediaType, kind = t.GetIntegral().GetTransport(), whatsmeow.MediaImage, "image"
		width, height = t.GetAncillary().GetWidth(), t.GetAncillary().GetHeight()
	case content.GetStickerMessage() != nil:
		t, err := content.GetStickerMessage().Decode()
		if err != nil {
			return
		}
		// Messenger's own stickers aren't uploaded with the message; their image is fetched by ID
		if fetchID := t.GetAncillary().GetReceiverFetchID(); fetchID != "" {
			go m.fetchSticker(threadKey, messageID, fetchID)
			return
		}
		transport, mediaType, kind = t.GetIntegral().GetTransport(), whatsmeow.MediaImage, "sticker"
	case content.GetVideoMessage() != nil:
		t, err := content.GetVideoMessage().Decode()
		if err != nil {
			return
		}
		transport, mediaType, kind = t.GetIntegral().GetTransport(), whatsmeow.MediaVideo, "video"
		seconds = t.GetAncillary().GetSeconds()
		width, height = t.GetAncillary().GetWidth(), t.GetAncillary().GetHeight()
	case content.GetAudioMessage() != nil:
		t, err := content.GetAudioMessage().Decode()
		if err != nil {
			return
		}
		transport, mediaType, kind = t.GetIntegral().GetTransport(), whatsmeow.MediaAudio, "audio"
		seconds = t.GetAncillary().GetSeconds()
	case content.GetDocumentMessage() != nil:
		t, err := content.GetDocumentMessage().Decode()
		if err != nil {
			return
		}
		transport, mediaType, kind = t.GetIntegral().GetTransport(), whatsmeow.MediaDocument, "file"
		fileName = content.GetDocumentMessage().GetFileName()
	default:
		return
	}
	if transport == nil || transport.GetIntegral() == nil {
		return
	}
	integral := transport.GetIntegral()
	if path := paths[transport.GetAncillary().GetObjectID()]; path != "" {
		integral = proto.CloneOf(integral)
		integral.DirectPath = proto.String(path)
	}
	if integral.GetDirectPath() == "" || transport.GetAncillary().GetFileLength() > maxEncryptedMediaBytes {
		return
	}
	mimeType := transport.GetAncillary().GetMimetype()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		data, err := m.e2ee.DownloadFB(ctx, integral, mediaType)
		if err != nil {
			m.log.Err(err).Msg("Failed to download encrypted media")
			return
		}
		dir := filepath.Join(m.dataDir, "media")
		if err = os.MkdirAll(dir, 0o700); err != nil {
			return
		}
		sum := sha256.Sum256([]byte(threadKey + "/" + messageID))
		file := filepath.Join(dir, hex.EncodeToString(sum[:16]))
		if err = writeFileAtomic(file, data); err != nil {
			return
		}
		if kind == "video" {
			// The file's own size and length win: the sender's may be missing, or not turned for a portrait video
			if video := mp4Meta(data); video.width > 0 {
				width, height = uint32(video.width), uint32(video.height)
				if seconds == 0 {
					seconds = video.seconds
				}
			}
		}
		event := map[string]any{
			"type": "attachment", "threadKey": threadKey, "messageId": messageID, "kind": kind,
			"url": "file://" + file, "previewUrl": "file://" + file, "mimeType": mimeType, "fileName": fileName,
			"width": width, "height": height,
		}
		if seconds > 0 {
			event["durationMs"] = int64(seconds) * 1000
		}
		m.emit(event)
	}()
}

// SendEncryptedReaction reacts to a message in an encrypted chat; an empty emoji removes the reaction.
func (m *Messenger) SendEncryptedReaction(threadKey string, isGroup bool, messageID string, messageSenderID string, emoji string) error {
	if m.e2ee == nil {
		return errNotConnectedE2EE
	}
	chat := types.NewJID(threadKey, chatServer(isGroup))
	key := &waCommon.MessageKey{RemoteJID: proto.String(chat.String()), ID: proto.String(messageID)}
	if messageSenderID == m.SelfID() {
		key.FromMe = proto.Bool(true)
	}
	if isGroup {
		key.Participant = proto.String(types.NewJID(messageSenderID, types.MessengerServer).String())
	}
	now := time.Now()
	msg := &waConsumerApplication.ConsumerApplication{
		Payload: &waConsumerApplication.ConsumerApplication_Payload{
			Payload: &waConsumerApplication.ConsumerApplication_Payload_Content{
				Content: &waConsumerApplication.ConsumerApplication_Content{
					Content: &waConsumerApplication.ConsumerApplication_Content_ReactionMessage{
						ReactionMessage: &waConsumerApplication.ConsumerApplication_ReactionMessage{
							Key:               key,
							Text:              proto.String(emoji),
							SenderTimestampMS: proto.Int64(now.UnixMilli()),
						},
					},
				},
			},
		},
	}
	if _, err := m.e2ee.SendFBMessage(context.Background(), chat, msg, nil); err != nil {
		return err
	}
	// Our own reactions aren't echoed back by the encrypted protocol
	m.emit(map[string]any{"type": "reaction", "threadKey": threadKey, "targetId": messageID, "senderId": m.SelfID(), "emoji": emoji})
	return nil
}

// fetchSticker resolves a Messenger sticker to its CDN image and emits it as the message's attachment.
func (m *Messenger) fetchSticker(threadKey, messageID, fetchID string) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	media, err := m.client.FetchReceiverFetchMedia(ctx, fetchID)
	if err != nil || media == nil || media.URL == "" {
		m.log.Err(err).Msg("Failed to fetch sticker")
		return
	}
	m.emit(map[string]any{
		"type": "attachment", "threadKey": threadKey, "messageId": messageID, "kind": "sticker",
		"url": media.URL, "previewUrl": media.URL, "mimeType": media.MimeType, "width": media.Width, "height": media.Height,
	})
}
