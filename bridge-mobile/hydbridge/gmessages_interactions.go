package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/util"
)

// gmMediaRef is what DownloadMedia needs for an attachment that isn't downloaded with its message.
type gmMediaRef struct {
	ID       string `json:"id"`
	Key      []byte `json:"key"`
	MimeType string `json:"mimeType"`
}

func (g *GMessages) connectedClient() (*libgm.Client, error) {
	client := g.currentClient()
	if client == nil {
		return nil, errors.New("not connected")
	}
	return client, nil
}

// simPayload is the SIM a conversation sends from.
func (g *GMessages) simPayload(conv *gmproto.Conversation) *gmproto.SIMPayload {
	if payload := conv.GetSimCard().GetSIMData().GetSIMPayload(); payload != nil {
		return payload
	}
	if sim, ok := g.sims.Load(conv.GetDefaultOutgoingID()); ok {
		return sim.(*gmproto.SIMCard).GetSIMData().GetSIMPayload()
	}
	return nil
}

func (g *GMessages) send(threadKey, replyToID string, infos ...*gmproto.MessageInfo) (string, error) {
	client, err := g.connectedClient()
	if err != nil {
		return "", err
	}
	conv := g.conversation(threadKey)
	if conv == nil {
		return "", errors.New("unknown conversation")
	}
	tmpID := util.GenerateTmpID()
	req := &gmproto.SendMessageRequest{
		ConversationID: threadKey,
		MessagePayload: &gmproto.MessagePayload{
			TmpID:          tmpID,
			ConversationID: threadKey,
			ParticipantID:  conv.GetDefaultOutgoingID(),
			TmpID2:         tmpID,
			MessageInfo:    infos,
		},
		SIMPayload: g.simPayload(conv),
		TmpID:      tmpID,
	}
	if replyToID != "" {
		req.Reply = &gmproto.ReplyPayload{MessageID: replyToID}
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	resp, err := client.SendMessage(ctx, req)
	// Like mautrix-gmessages: the phone sometimes refuses a send for a moment
	for attempt := 0; err == nil && isTransientGMSendFailure(resp.GetStatus()) && attempt < 3; attempt++ {
		time.Sleep(time.Duration(3*(attempt+1)) * time.Second)
		resp, err = client.SendMessage(ctx, req)
	}
	if err != nil {
		return "", err
	}
	switch resp.GetStatus() {
	case gmproto.SendMessageResponse_SUCCESS:
		return tmpID, nil
	case gmproto.SendMessageResponse_FAILURE_4:
		return "", errors.New("Google Messages isn't the default SMS app on the phone")
	}
	return "", fmt.Errorf("the phone refused the message (%s)", resp.GetStatus())
}

func isTransientGMSendFailure(status gmproto.SendMessageResponse_Status) bool {
	return status == gmproto.SendMessageResponse_FAILURE_2 || status == gmproto.SendMessageResponse_FAILURE_3
}

func textInfo(text string) *gmproto.MessageInfo {
	return &gmproto.MessageInfo{Data: &gmproto.MessageInfo_MessageContent{MessageContent: &gmproto.MessageContent{Content: text}}}
}

// SendText sends a message and returns its temporary ID (the echo's otid).
func (g *GMessages) SendText(threadKey, text string) (string, error) {
	return g.send(threadKey, "", textInfo(text))
}

// SendReply sends text quoting a message (RCS shows it as a reply; SMS just gets the text).
func (g *GMessages) SendReply(threadKey, text, replyToID string) (string, error) {
	return g.send(threadKey, replyToID, textInfo(text))
}

// SendMediaFile sends the file at path, with an optional caption, as MMS or RCS.
func (g *GMessages) SendMediaFile(threadKey, path, mimeType, fileName, caption string) (string, error) {
	client, err := g.connectedClient()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	_ = os.Remove(path)
	if err != nil {
		return "", err
	}
	if fileName == "" {
		fileName = filepath.Base(path)
	}
	media, err := client.UploadMedia(data, fileName, mimeType)
	if err != nil {
		return "", fmt.Errorf("failed to upload: %w", err)
	}
	infos := []*gmproto.MessageInfo{{Data: &gmproto.MessageInfo_MediaContent{MediaContent: media}}}
	if caption != "" {
		infos = append(infos, textInfo(caption))
	}
	return g.send(threadKey, "", infos...)
}

// SendVoice sends a recording; Google Messages plays audio attachments as voice messages.
func (g *GMessages) SendVoice(threadKey, path, mimeType string) (string, error) {
	extension := ".m4a"
	if strings.Contains(mimeType, "ogg") {
		extension = ".ogg"
	}
	return g.SendMediaFile(threadKey, path, mimeType, "voice"+extension, "")
}

// SendReaction reacts to a message; an empty emoji removes our reaction.
func (g *GMessages) SendReaction(threadKey, messageID, emoji string) error {
	client, err := g.connectedClient()
	if err != nil {
		return err
	}
	action := gmproto.SendReactionRequest_ADD
	previous, hadOne := g.ownReactions.Load(messageID)
	switch {
	case emoji == "" && !hadOne:
		return nil
	case emoji == "":
		action, emoji = gmproto.SendReactionRequest_REMOVE, previous.(string)
	case hadOne:
		action = gmproto.SendReactionRequest_SWITCH
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	resp, err := client.SendReaction(ctx, &gmproto.SendReactionRequest{
		MessageID:    messageID,
		ReactionData: gmproto.MakeReactionData(emoji),
		Action:       action,
		SIMPayload:   g.simPayload(g.conversation(threadKey)),
	})
	if err != nil {
		return err
	} else if !resp.GetSuccess() {
		return errors.New("the phone refused the reaction")
	}
	return nil
}

// Unsend deletes a message (from the phone and this app; SMS can't be taken back from the other person).
func (g *GMessages) Unsend(threadKey, messageID string) error {
	client, err := g.connectedClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	resp, err := client.DeleteMessage(ctx, messageID)
	if err != nil {
		return err
	} else if !resp.GetSuccess() {
		return errors.New("the phone refused to delete the message")
	}
	return nil
}

// MarkRead marks a conversation read up to a message.
func (g *GMessages) MarkRead(threadKey, messageID string) error {
	client, err := g.connectedClient()
	if err != nil {
		return err
	}
	if messageID == "" {
		messageID = g.conversation(threadKey).GetLatestMessageID()
	}
	if messageID == "" {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return client.MarkRead(ctx, threadKey, messageID)
}

// SetTyping shows "typing…" (RCS only; Google Messages has no "stopped typing").
func (g *GMessages) SetTyping(threadKey string, isTyping bool) error {
	if !isTyping {
		return nil
	}
	client, err := g.connectedClient()
	if err != nil {
		return err
	}
	conv := g.conversation(threadKey)
	if conv == nil || conv.GetType() != gmproto.ConversationType_RCS {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return client.SetTyping(ctx, threadKey, g.simPayload(conv))
}

// RequestOlder loads messages before one (the newest page when beforeMessageID is empty); they arrive as message
// events, then a range event.
func (g *GMessages) RequestOlder(threadKey, beforeMessageID string, beforeTimestampMs int64) error {
	client, err := g.connectedClient()
	if err != nil {
		return err
	}
	var cursor *gmproto.Cursor
	if beforeMessageID != "" {
		tsMs := beforeTimestampMs
		if micro, ok := g.msgTimes.Load(beforeMessageID); ok {
			tsMs = micro.(int64) / 1000
		}
		cursor = &gmproto.Cursor{LastItemID: beforeMessageID, LastItemTimestamp: tsMs}
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	return g.fetchMessages(ctx, client, threadKey, cursor)
}

func (g *GMessages) fetchMessages(ctx context.Context, client *libgm.Client, threadKey string, cursor *gmproto.Cursor) error {
	resp, err := client.FetchMessages(ctx, threadKey, gmessagesPageSize, cursor)
	if err != nil {
		return err
	}
	emitted := 0
	for _, msg := range resp.GetMessages() {
		if cursor != nil && (msg.GetMessageID() == cursor.LastItemID || msg.GetTimestamp()/1000 > cursor.LastItemTimestamp) {
			continue
		}
		g.emitMessage(msg)
		emitted++
	}
	g.emit(map[string]any{"type": "range", "threadKey": threadKey, "hasMoreBefore": emitted > 0 && len(resp.GetMessages()) >= gmessagesPageSize/2})
	return nil
}

func (g *GMessages) mediaPath(id string) (string, error) {
	dir := filepath.Join(g.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return filepath.Join(dir, strings.NewReplacer("/", "_", ":", "_").Replace(id)), nil
}

func (g *GMessages) downloadToFile(mediaID string, key []byte, path string) error {
	if _, err := os.Stat(path); err == nil {
		return nil
	}
	client, err := g.connectedClient()
	if err != nil {
		return err
	}
	reader, err := client.DownloadMedia(mediaID, key)
	if err != nil {
		return err
	}
	defer reader.Close()
	tmp := path + ".part"
	file, err := os.Create(tmp)
	if err != nil {
		return err
	}
	_, err = io.Copy(file, reader)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(tmp, path)
	}
	if err != nil {
		_ = os.Remove(tmp)
	}
	return err
}

func (g *GMessages) downloadImage(threadKey, messageID string, media *gmproto.MediaContent) {
	path, err := g.mediaPath(messageID)
	if err != nil {
		return
	}
	if err = g.downloadToFile(media.GetMediaID(), media.GetDecryptionKey(), path); err != nil {
		g.log.Warn().Err(err).Msg("Failed to download a Google Messages image")
		return
	}
	g.emit(map[string]any{
		"type": "attachment", "threadKey": threadKey, "messageId": messageID, "kind": "image",
		"url": "file://" + path, "previewUrl": "file://" + path, "mimeType": media.GetMimeType(),
		"fileName": media.GetMediaName(), "width": media.GetDimensions().GetWidth(), "height": media.GetDimensions().GetHeight(),
	})
}

// DownloadMedia downloads a message's attachment from its mediaRef and returns the file's path.
func (g *GMessages) DownloadMedia(threadKey, messageID, mediaRef string) (string, error) {
	var ref gmMediaRef
	if err := json.Unmarshal([]byte(mediaRef), &ref); err != nil {
		return "", err
	}
	path, err := g.mediaPath(messageID)
	if err != nil {
		return "", err
	}
	return path, g.downloadToFile(ref.ID, ref.Key, path)
}

// FetchAvatars asks for the pictures of these chats (comma-separated keys); one-to-one chats show the other
// person's contact photo. They arrive as avatar events.
func (g *GMessages) FetchAvatars(threadKeys string) error {
	client, err := g.connectedClient()
	if err != nil {
		return err
	}
	byParticipant := map[string]string{}
	for _, key := range strings.Split(threadKeys, ",") {
		conv := g.conversation(strings.TrimSpace(key))
		if conv == nil || conv.GetIsGroupChat() || len(conv.GetOtherParticipants()) != 1 {
			continue
		}
		byParticipant[visibleParticipant(conv, conv.GetOtherParticipants()[0])] = conv.GetConversationID()
	}
	if len(byParticipant) == 0 {
		return nil
	}
	ids := make([]string, 0, len(byParticipant))
	for id := range byParticipant {
		ids = append(ids, id)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	resp, err := client.GetParticipantThumbnail(ctx, ids...)
	if err != nil {
		return err
	}
	for _, thumb := range resp.GetThumbnail() {
		key, image := byParticipant[thumb.GetIdentifier()], thumb.GetData().GetImageBuffer()
		if key == "" || len(image) == 0 {
			continue
		}
		path, err := g.mediaPath("avatar-" + thumb.GetIdentifier())
		if err != nil || os.WriteFile(path, image, 0o600) != nil {
			continue
		}
		g.emit(map[string]any{"type": "avatar", "threadKey": key, "url": "file://" + path})
	}
	return nil
}
