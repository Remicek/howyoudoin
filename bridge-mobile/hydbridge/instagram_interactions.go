package hydbridge

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"time"

	"go.mau.fi/util/jsontime"
	"go.mau.fi/util/ptr"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
)

// SendReaction reacts to a message; an empty emoji removes our reaction. Instagram keeps one reaction per person,
// so a new one replaces the old.
func (ig *Instagram) SendReaction(threadKey string, messageID string, emoji string) error {
	igID, err := ig.shortThreadID(threadKey)
	if err != nil {
		return err
	}
	status := slidetypes.ReactionStatusCreated
	if emoji == "" {
		// Removing needs the emoji being removed
		current, ok := ig.ownReactions.Load(threadKey + "/" + messageID)
		if !ok {
			return nil
		}
		emoji, status = current.(string), slidetypes.ReactionStatusDeleted
	}
	_, err = ig.client.SendReaction(context.Background(), &slidetypes.CreateReactionRequest{Input: slidetypes.ReactionInput{
		Emoji:          emoji,
		MessageID:      messageID,
		ReactionStatus: status,
		ThreadID:       igID,
	}})
	if err != nil {
		return err
	}
	if status == slidetypes.ReactionStatusDeleted {
		emoji = ""
	}
	ig.trackOwnReaction(threadKey, messageID, ig.selfID, emoji)
	ig.emit(map[string]any{"type": "reaction", "threadKey": threadKey, "targetId": messageID,
		"senderId": strconv.FormatInt(ig.selfID, 10), "emoji": emoji})
	return nil
}

func (ig *Instagram) trackOwnReaction(threadKey, messageID string, senderID int64, emoji string) {
	if senderID != ig.selfID || ig.selfID == 0 {
		return
	}
	key := threadKey + "/" + messageID
	if emoji == "" {
		ig.ownReactions.Delete(key)
	} else {
		ig.ownReactions.Store(key, emoji)
	}
}

// MarkRead marks a chat read up to lastMessageID, the newest message in it, like the Instagram website does.
func (ig *Instagram) MarkRead(threadKey string, lastMessageID string) error {
	if lastMessageID == "" {
		return nil
	}
	rawID, ok := ig.igLongThreadIDs.Load(threadKey)
	if !ok {
		ids, err := ig.lookUpThreadIDs(threadKey)
		if err != nil {
			return err
		}
		rawID = ids
	}
	longID := rawID.(string)
	ctx := context.Background()
	_, err := ig.client.MarkRead(ctx, &slidetypes.MarkReadRequest{
		Metadata: slidetypes.MarkReadMetadata{IGThreadIGID: longID},
		Data:     slidetypes.MarkReadData{MessageID: lastMessageID, ItemID: ptr.Ptr("")},
	})
	if err != nil {
		return err
	}
	sentAt := time.Now()
	if t, ok := ig.messageTimes.Load(lastMessageID); ok {
		sentAt = t.(time.Time)
	}
	_, err = ig.client.MarkReadValidation(ctx, &slidetypes.MarkReadRequest{
		Metadata: slidetypes.MarkReadMetadata{IGThreadIGID: longID},
		Data:     slidetypes.MarkReadData{MessageID: lastMessageID, MessageTimestampMS: jsontime.UnixMilliString{Time: sentAt}},
	})
	return err
}

// SetTyping shows or clears "typing…" for the other people in the chat.
func (ig *Instagram) SetTyping(threadKey string, isTyping bool) error {
	igID, err := ig.shortThreadID(threadKey)
	if err != nil {
		return err
	}
	return ig.client.SetTyping(context.Background(), igID, isTyping)
}

// SendMediaFile sends the file at path (written by the app into its private storage) as a photo, video or file and
// returns its offline threading ID, which the echo carries as otid. Instagram media has no caption, so a caption
// follows as its own message.
func (ig *Instagram) SendMediaFile(threadKey string, path, mimeType, fileName, caption string) (string, error) {
	otid, err := ig.sendMedia(threadKey, path, &httpclient.MercuryUploadMedia{Filename: fileName, MimeType: mimeType})
	if err == nil && caption != "" {
		if _, captionErr := ig.SendText(threadKey, caption); captionErr != nil {
			ig.log.Warn().Err(captionErr).Msg("Failed to send a caption")
		}
	}
	return otid, err
}

// SendVoice sends the recording at path (AAC in MP4) as a voice message; waveform is comma-separated loudness
// values, 0–255.
func (ig *Instagram) SendVoice(threadKey string, path, mimeType string, durationMs int, waveform string) (string, error) {
	media := &httpclient.MercuryUploadMedia{Filename: "Voice message.m4a", MimeType: mimeType, IsVoiceClip: true}
	if values := parseWaveform(waveform); len(values) > 0 && durationMs > 0 {
		media.WaveformData = &httpclient.WaveformData{
			Amplitudes:        make([]float64, len(values)),
			SamplingFrequency: max(1, len(values)*1000/durationMs),
		}
		for i, v := range values {
			media.WaveformData.Amplitudes[i] = float64(v) / 255
		}
	}
	return ig.sendMedia(threadKey, path, media)
}

func (ig *Instagram) sendMedia(threadKey, path string, media *httpclient.MercuryUploadMedia) (string, error) {
	defer os.Remove(path)
	igID, err := ig.shortThreadID(threadKey)
	if err != nil {
		return "", err
	}
	fbid, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return "", fmt.Errorf("invalid thread key: %w", err)
	}
	media.MediaData, err = os.ReadFile(path)
	if err != nil {
		return "", err
	}
	ctx := context.Background()
	upload, err := ig.client.GetHTTP().SendMercuryUploadRequest(ctx, fbid, media)
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	attachmentID := upload.Payload.RealMetadata.GetFbId()
	if attachmentID == 0 {
		return "", errors.New("upload failed: no attachment ID")
	}
	otid := strconv.FormatInt(methods.GenerateEpochID(), 10)
	_, err = ig.client.SendMedia(ctx, &slidetypes.SendMediaRequest{
		AttachmentFBID:     strconv.FormatInt(attachmentID, 10),
		ThreadID:           igID,
		OfflineThreadingID: otid,
	})
	if err != nil {
		return "", err
	}
	return otid, nil
}

// shortThreadID is the thread's IG ID, which sending, reactions and typing use.
func (ig *Instagram) shortThreadID(threadKey string) (string, error) {
	if rawID, ok := ig.igThreadIDs.Load(threadKey); ok {
		return rawID.(string), nil
	}
	if _, err := ig.lookUpThreadIDs(threadKey); err != nil {
		return "", err
	}
	rawID, _ := ig.igThreadIDs.Load(threadKey)
	return rawID.(string), nil
}

// lookUpThreadIDs asks Instagram for a thread's IG IDs, remembers them and returns the long one.
func (ig *Instagram) lookUpThreadIDs(threadKey string) (string, error) {
	fbid, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return "", errors.New("unknown thread")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	ids, err := ig.client.FetchThreadID(ctx, fbid)
	if err != nil {
		return "", fmt.Errorf("failed to look up the thread: %w", err)
	}
	ig.igThreadIDs.Store(threadKey, ids.ShortID)
	ig.igLongThreadIDs.Store(threadKey, ids.LongID)
	ig.threadKeys.Store(ids.ShortID, threadKey)
	ig.threadKeys.Store(ids.LongID, threadKey)
	return ids.LongID, nil
}
