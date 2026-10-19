package hydbridge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"go.mau.fi/mautrix-telegram/pkg/gotd/telegram/downloader"
	"go.mau.fi/mautrix-telegram/pkg/gotd/telegram/uploader"
	"go.mau.fi/mautrix-telegram/pkg/gotd/tg"
)

// describeMedia fills in a message event's kind for its media. Photos and stickers are downloaded right away (they
// show inline); videos, voice messages and files get a mediaRef and are downloaded when opened (DownloadMedia).
func (t *Telegram) describeMedia(key, id string, msg *tg.Message, event map[string]any) {
	switch media := msg.Media.(type) {
	case *tg.MessageMediaPhoto:
		photo, ok := media.Photo.(*tg.Photo)
		if !ok {
			event["kind"] = "other"
			return
		}
		event["kind"] = "image"
		size, w, h := pickPhotoSize(photo.Sizes)
		if size == "" {
			return
		}
		location := &tg.InputPhotoFileLocation{ID: photo.ID, AccessHash: photo.AccessHash, FileReference: photo.FileReference, ThumbSize: size}
		go t.downloadAttachment(key, id, "image", location, "image/jpeg", "", w, h)
	case *tg.MessageMediaDocument:
		doc, ok := media.Document.(*tg.Document)
		if !ok {
			event["kind"] = "other"
			return
		}
		kind, fileName, w, h := documentKind(doc)
		event["kind"] = kind
		if seconds := documentSeconds(doc); seconds > 0 {
			event["durationMs"] = int64(seconds * 1000)
		}
		switch {
		case kind == "sticker":
			// Static stickers are WebP images; animated ones show their still preview
			location := &tg.InputDocumentFileLocation{ID: doc.ID, AccessHash: doc.AccessHash, FileReference: doc.FileReference}
			mime := doc.MimeType
			if mime != "image/webp" {
				thumb, tw, th := pickPhotoSize(doc.Thumbs)
				if thumb == "" {
					event["kind"], event["text"] = "text", stickerEmoji(doc)
					return
				}
				location.ThumbSize, mime, w, h = thumb, "image/webp", tw, th
			}
			go t.downloadAttachment(key, id, "sticker", location, mime, "", w, h)
		default:
			event["mediaRef"] = "doc"
			if kind == "file" && msg.Message == "" {
				// The file card shows the text as the file's name until it's downloaded
				event["text"] = fileName
			}
		}
	case *tg.MessageMediaGeo, *tg.MessageMediaGeoLive, *tg.MessageMediaVenue:
		event["kind"], event["text"] = "text", firstNonEmpty(msg.Message, "📍 Location")
	case *tg.MessageMediaContact:
		event["kind"], event["text"] = "text", "👤 "+strings.TrimSpace(media.FirstName+" "+media.LastName+" "+media.PhoneNumber)
	case *tg.MessageMediaPoll:
		event["kind"], event["text"] = "text", "📊 Poll"
	case *tg.MessageMediaWebPage, *tg.MessageMediaEmpty:
		// A link preview; the text has the link
	default:
		if msg.Message == "" {
			event["kind"] = "other"
		}
	}
}

// pickPhotoSize picks the largest regular size up to ~1280px ("y" or smaller), skipping stripped/progressive sizes.
func pickPhotoSize(sizes []tg.PhotoSizeClass) (string, int, int) {
	best, bw, bh := "", 0, 0
	for _, raw := range sizes {
		size, ok := raw.(*tg.PhotoSize)
		if !ok {
			if progressive, ok := raw.(*tg.PhotoSizeProgressive); ok && progressive.W <= 1280 && progressive.W > bw {
				best, bw, bh = progressive.Type, progressive.W, progressive.H
			}
			continue
		}
		if size.W <= 1280 && size.H <= 1280 && size.W > bw {
			best, bw, bh = size.Type, size.W, size.H
		}
	}
	return best, bw, bh
}

func documentKind(doc *tg.Document) (kind, fileName string, w, h int) {
	kind = "file"
	for _, raw := range doc.Attributes {
		switch attr := raw.(type) {
		case *tg.DocumentAttributeFilename:
			fileName = attr.FileName
		case *tg.DocumentAttributeSticker:
			kind = "sticker"
		case *tg.DocumentAttributeVideo:
			if kind != "sticker" {
				kind = "video"
			}
			w, h = attr.W, attr.H
		case *tg.DocumentAttributeAudio:
			kind = "audio"
		case *tg.DocumentAttributeAnimated:
			if kind != "sticker" {
				kind = "video"
			}
		case *tg.DocumentAttributeImageSize:
			w, h = attr.W, attr.H
		}
	}
	if kind == "file" && strings.HasPrefix(doc.MimeType, "image/") && doc.MimeType != "image/gif" && fileName == "" {
		kind = "image"
	}
	return kind, fileName, w, h
}

// documentSeconds is an audio or video document's length.
func documentSeconds(doc *tg.Document) float64 {
	for _, raw := range doc.Attributes {
		switch attr := raw.(type) {
		case *tg.DocumentAttributeAudio:
			return float64(attr.Duration)
		case *tg.DocumentAttributeVideo:
			return float64(attr.Duration)
		}
	}
	return 0
}

func stickerEmoji(doc *tg.Document) string {
	for _, raw := range doc.Attributes {
		if attr, ok := raw.(*tg.DocumentAttributeSticker); ok && attr.Alt != "" {
			return attr.Alt
		}
	}
	return "Sticker"
}

func (t *Telegram) mediaPath(id string) (string, error) {
	dir := filepath.Join(t.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	sum := sha256.Sum256([]byte(id))
	return filepath.Join(dir, hex.EncodeToString(sum[:16])), nil
}

// downloadAttachment saves a photo or sticker in the media folder and emits it as the message's attachment.
func (t *Telegram) downloadAttachment(key, id, kind string, location tg.InputFileLocationClass, mime, fileName string, w, h int) {
	path, err := t.mediaPath(id)
	if err != nil {
		return
	}
	if _, err := os.Stat(path); err != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		_, err = downloader.NewDownloader().Download(t.client.API(), location).ToPath(ctx, path)
		cancel()
		if err != nil {
			_ = os.Remove(path)
			t.log.Warn().Err(err).Msg("Failed to download Telegram media")
			return
		}
	}
	t.emit(map[string]any{
		"type": "attachment", "threadKey": key, "messageId": id, "kind": kind, "url": "file://" + path,
		"previewUrl": "file://" + path, "mimeType": mime, "fileName": fileName, "width": w, "height": h,
	})
}

// DownloadMedia downloads a message's video, voice message or file and returns the file's path.
func (t *Telegram) DownloadMedia(threadKey, messageID, _ string) (string, error) {
	path, err := t.mediaPath(messageID)
	if err != nil {
		return "", err
	}
	if _, err := os.Stat(path); err == nil {
		return path, nil
	}
	msg, err := t.fetchMessage(threadKey, messageID)
	if err != nil {
		return "", err
	}
	media, ok := msg.Media.(*tg.MessageMediaDocument)
	if !ok {
		return "", errors.New("the message has no file")
	}
	doc, ok := media.Document.(*tg.Document)
	if !ok {
		return "", errors.New("the file is no longer available")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	location := &tg.InputDocumentFileLocation{ID: doc.ID, AccessHash: doc.AccessHash, FileReference: doc.FileReference}
	if _, err = downloader.NewDownloader().Download(t.client.API(), location).ToPath(ctx, path); err != nil {
		_ = os.Remove(path)
		return "", err
	}
	return path, nil
}

// fetchMessage loads a message again, for a fresh file reference (they expire).
func (t *Telegram) fetchMessage(threadKey, messageID string) (*tg.Message, error) {
	_, msgID, err := splitTelegramMessageID(messageID)
	if err != nil {
		return nil, err
	}
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	ids := []tg.InputMessageClass{&tg.InputMessageID{ID: msgID}}
	var resp tg.MessagesMessagesClass
	if channel, ok := peer.(*tg.InputPeerChannel); ok {
		resp, err = t.client.API().ChannelsGetMessages(ctx, &tg.ChannelsGetMessagesRequest{
			Channel: &tg.InputChannel{ChannelID: channel.ChannelID, AccessHash: channel.AccessHash}, ID: ids,
		})
	} else {
		resp, err = t.client.API().MessagesGetMessages(ctx, ids)
	}
	if err != nil {
		return nil, err
	}
	var messages []tg.MessageClass
	switch r := resp.(type) {
	case *tg.MessagesMessages:
		messages = r.Messages
	case *tg.MessagesMessagesSlice:
		messages = r.Messages
	case *tg.MessagesChannelMessages:
		messages = r.Messages
	}
	for _, raw := range messages {
		if msg, ok := raw.(*tg.Message); ok && msg.ID == msgID {
			return msg, nil
		}
	}
	return nil, errors.New("the message is no longer available")
}

// SendMediaFile sends the file at path as a photo or a file (video and audio play from it in Telegram too) and
// returns the ID the echo carries as otid.
func (t *Telegram) SendMediaFile(threadKey, path, mimeType, fileName, caption string, width, height int) (string, error) {
	defer os.Remove(path)
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	file, err := uploader.NewUploader(t.client.API()).FromPath(ctx, path, firstNonEmpty(fileName, "file"))
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	var media tg.InputMediaClass
	kind, _ := mediaKind(mimeType)
	switch kind {
	case "image":
		media = &tg.InputMediaUploadedPhoto{File: file}
	default:
		attributes := []tg.DocumentAttributeClass{&tg.DocumentAttributeFilename{FileName: firstNonEmpty(fileName, "file")}}
		switch kind {
		case "video":
			attributes = append(attributes, &tg.DocumentAttributeVideo{W: width, H: height, SupportsStreaming: true})
		case "audio":
			attributes = append(attributes, &tg.DocumentAttributeAudio{})
		}
		media = &tg.InputMediaUploadedDocument{File: file, MimeType: mimeType, Attributes: attributes}
	}
	rid := randomID()
	resp, err := t.client.API().MessagesSendMedia(ctx, &tg.MessagesSendMediaRequest{Peer: peer, Media: media, Message: caption, RandomID: rid})
	if err != nil {
		return "", friendlyTelegramError(err)
	}
	// The echo with its media comes as an update
	t.handleSent(ctx, threadKey, rid, resp)
	return strconv.FormatInt(rid, 10), nil
}

// FetchAvatars downloads the profile pictures of these chats (comma-separated thread keys) and emits avatar events.
func (t *Telegram) FetchAvatars(threadKeys string) error {
	go func() {
		for _, key := range strings.Split(threadKeys, ",") {
			if key = strings.TrimSpace(key); key != "" {
				t.fetchAvatar(key)
			}
		}
	}()
	return nil
}

func (t *Telegram) fetchAvatar(key string) {
	peer := t.inputPeer(key)
	if peer == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var photo tg.ChatPhotoClass
	var userPhoto tg.UserProfilePhotoClass
	switch p := peer.(type) {
	case *tg.InputPeerUser:
		users, err := t.client.API().UsersGetUsers(ctx, []tg.InputUserClass{&tg.InputUser{UserID: p.UserID, AccessHash: p.AccessHash}})
		if err != nil || len(users) == 0 {
			return
		}
		if user, ok := users[0].(*tg.User); ok {
			userPhoto = user.Photo
		}
	case *tg.InputPeerChat:
		full, err := t.client.API().MessagesGetChats(ctx, []int64{p.ChatID})
		if err != nil {
			return
		}
		for _, raw := range full.GetChats() {
			if chat, ok := raw.(*tg.Chat); ok {
				photo = chat.Photo
			}
		}
	case *tg.InputPeerChannel:
		full, err := t.client.API().ChannelsGetChannels(ctx, []tg.InputChannelClass{&tg.InputChannel{ChannelID: p.ChannelID, AccessHash: p.AccessHash}})
		if err != nil {
			return
		}
		for _, raw := range full.GetChats() {
			if channel, ok := raw.(*tg.Channel); ok {
				photo = channel.Photo
			}
		}
	}
	var photoID int64
	if p, ok := userPhoto.(*tg.UserProfilePhoto); ok {
		photoID = p.PhotoID
	} else if p, ok := photo.(*tg.ChatPhoto); ok {
		photoID = p.PhotoID
	}
	if photoID == 0 {
		return
	}
	path, err := t.mediaPath("avatar/" + key + "/" + strconv.FormatInt(photoID, 10))
	if err != nil {
		return
	}
	if _, err := os.Stat(path); err != nil {
		location := &tg.InputPeerPhotoFileLocation{Peer: peer, PhotoID: photoID}
		if _, err = downloader.NewDownloader().Download(t.client.API(), location).ToPath(ctx, path); err != nil {
			_ = os.Remove(path)
			return
		}
	}
	t.emit(map[string]any{"type": "avatar", "threadKey": key, "url": "file://" + path})
}

// SendVoice sends the recording at path (Ogg/Opus) as a voice message, which Telegram shows with a play button and
// waveform. waveform is comma-separated loudness values, 0–255.
func (t *Telegram) SendVoice(threadKey, path, mimeType string, durationMs int, waveform string) (string, error) {
	defer os.Remove(path)
	peer, err := t.peerFor(threadKey)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	file, err := uploader.NewUploader(t.client.API()).FromPath(ctx, path, "voice.ogg")
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	if strings.HasPrefix(mimeType, "audio/ogg") {
		mimeType = "audio/ogg"
	}
	media := &tg.InputMediaUploadedDocument{
		File:     file,
		MimeType: mimeType,
		Attributes: []tg.DocumentAttributeClass{&tg.DocumentAttributeAudio{
			Voice:    true,
			Duration: (durationMs + 500) / 1000,
			Waveform: telegramWaveform(scaleWaveform(waveform, 100, 31)),
		}},
	}
	rid := randomID()
	resp, err := t.client.API().MessagesSendMedia(ctx, &tg.MessagesSendMediaRequest{Peer: peer, Media: media, RandomID: rid})
	if err != nil {
		return "", friendlyTelegramError(err)
	}
	t.handleSent(ctx, threadKey, rid, resp)
	return strconv.FormatInt(rid, 10), nil
}

// telegramWaveform packs 5-bit samples (0–31) one after another, lowest bits first, as Telegram stores waveforms.
func telegramWaveform(samples []byte) []byte {
	if len(samples) == 0 {
		return nil
	}
	out := make([]byte, (len(samples)*5+7)/8)
	for i, s := range samples {
		bit := i * 5
		v := uint16(s&31) << (bit % 8)
		out[bit/8] |= byte(v)
		if bit/8+1 < len(out) {
			out[bit/8+1] |= byte(v >> 8)
		}
	}
	return out
}
