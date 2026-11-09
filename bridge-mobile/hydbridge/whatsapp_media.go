package hydbridge

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"sync"

	"go.mau.fi/whatsmeow/proto/waE2E"
)

// waAssociation is how WhatsApp links a message to another one (an album's photo, a photo's HD copy), from the
// message itself or from the child message it wraps.
func waAssociation(msg *waE2E.Message) *waE2E.MessageAssociation {
	if a := msg.GetMessageContextInfo().GetMessageAssociation(); a != nil {
		return a
	}
	return msg.GetAssociatedChildMessage().GetMessage().GetMessageContextInfo().GetMessageAssociation()
}

// waIsHDCopy reports a photo's or video's HD copy. WhatsApp sends one a few seconds after the original and folds
// it into the original's bubble; as a message of its own it would show the photo or video twice.
func waIsHDCopy(msg *waE2E.Message) bool {
	switch waAssociation(msg).GetAssociationType() {
	case waE2E.MessageAssociation_HD_IMAGE_DUAL_UPLOAD, waE2E.MessageAssociation_HD_VIDEO_DUAL_UPLOAD,
		waE2E.MessageAssociation_HEVC_VIDEO_DUAL_UPLOAD, waE2E.MessageAssociation_STREAMED_HD_VIDEO_DUAL_UPLOAD:
		return true
	}
	return false
}

// waMediaMessage unwraps the layers media can sit under (disappearing, view-once, captioned documents, wrapped
// child messages) down to the message whose media can be downloaded.
func waMediaMessage(msg *waE2E.Message) *waE2E.Message {
	for depth := 0; depth < 5 && msg != nil; depth++ {
		var inner *waE2E.Message
		switch {
		case msg.GetImageMessage() != nil, msg.GetVideoMessage() != nil, msg.GetAudioMessage() != nil,
			msg.GetDocumentMessage() != nil, msg.GetStickerMessage() != nil:
			return msg
		case msg.GetEphemeralMessage() != nil:
			inner = msg.GetEphemeralMessage().GetMessage()
		case msg.GetViewOnceMessage() != nil:
			inner = msg.GetViewOnceMessage().GetMessage()
		case msg.GetViewOnceMessageV2() != nil:
			inner = msg.GetViewOnceMessageV2().GetMessage()
		case msg.GetViewOnceMessageV2Extension() != nil:
			inner = msg.GetViewOnceMessageV2Extension().GetMessage()
		case msg.GetDocumentWithCaptionMessage() != nil:
			inner = msg.GetDocumentWithCaptionMessage().GetMessage()
		default:
			inner = waWrappedMessage(msg)
		}
		if inner == nil {
			return msg
		}
		msg = inner
	}
	return msg
}

// saveVideoPreview stores the small preview picture WhatsApp sends with a video, so the chat shows a frame of it
// before (and without) downloading the video. Returns its file URL, or "".
func (w *WhatsApp) saveVideoPreview(chat, messageID string, msg *waE2E.Message) string {
	thumb := waMediaMessage(msg).GetVideoMessage().GetJPEGThumbnail()
	if len(thumb) == 0 {
		return ""
	}
	dir := filepath.Join(w.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return ""
	}
	sum := sha256.Sum256([]byte(chat + "/" + messageID + "/preview"))
	file := filepath.Join(dir, hex.EncodeToString(sum[:16])+".jpg")
	if err := writeFileAtomic(file, thumb); err != nil {
		return ""
	}
	return "file://" + file
}

// waRecentMediaLimit is how many recent media messages are kept for quoting.
const waRecentMediaLimit = 500

// waRecentMedia keeps the most recent media messages by ID, so a reply can quote one whole.
type waRecentMedia struct {
	mu    sync.Mutex
	order []string
	byID  map[string]*waE2E.Message
}

// put remembers msg when it carries a photo, video, voice message, file or sticker.
func (r *waRecentMedia) put(id string, msg *waE2E.Message) {
	media := waMediaMessage(msg)
	if id == "" || media == nil || media.GetImageMessage() == nil && media.GetVideoMessage() == nil &&
		media.GetAudioMessage() == nil && media.GetDocumentMessage() == nil && media.GetStickerMessage() == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.byID == nil {
		r.byID = make(map[string]*waE2E.Message)
	}
	if _, known := r.byID[id]; !known {
		r.order = append(r.order, id)
	}
	r.byID[id] = media
	for len(r.order) > waRecentMediaLimit {
		delete(r.byID, r.order[0])
		r.order = r.order[1:]
	}
}

// get is the media message with the ID, or nil when it isn't among the recent ones.
func (r *waRecentMedia) get(id string) *waE2E.Message {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.byID[id]
}
