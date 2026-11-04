package hydbridge

import (
	"strconv"
	"strings"
	"time"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
)

// Instagram calls (see calls.go). Instagram announces a call in its chat with a notice ("remol270 started an audio
// call") and posts another when it's over ("Audio call ended"); both are of content type IG_VIDEO_CALL_XMAT. A call
// is a room the other person joins from Instagram's inbox ("Join"), and the end notice reads the same whether
// anyone joined or not (tested 2026-11-04), so a call shows as "a call started · join it in Instagram" and then
// as a call, never as missed.
const instagramCallContentType = "IG_VIDEO_CALL_XMAT"

// instagramCallMaxAge: a call started longer ago than this has ended (its end notice may have been missed).
const instagramCallMaxAge = 2 * time.Hour

type instagramCall struct {
	messageID   string
	senderID    string
	timestampMs int64
	isVideo     bool
}

func isInstagramCallNotice(msg *slidetypes.Message) bool {
	return msg != nil && msg.ContentType == instagramCallContentType
}

// emitCallNotice shows a call notice: a start becomes the call's message, an end updates that message. From history
// (live false), starts are shown as calls that ended and end notices are skipped (their start already says it).
func (ig *Instagram) emitCallNotice(threadKey string, msg *slidetypes.Message, live bool) {
	text := strings.ToLower(firstNonEmpty(msg.IGDSnippet, msg.TextBody, instagramNoticeText(msg)))
	isVideo := strings.Contains(text, "video")
	timestampMs := msg.TimestampMS.UnixMilli()
	value, ongoing := ig.calls.Load(threadKey)
	// The notices are worded in the account's language; English is recognised, otherwise a notice that comes
	// while a call is going on is its end
	isEnd := strings.Contains(text, "ended") ||
		(ongoing && value.(instagramCall).messageID != msg.MessageID && !strings.Contains(text, "started"))
	if isEnd {
		if !live {
			return
		}
		call := instagramCall{messageID: msg.MessageID, senderID: strconv.FormatInt(msg.SenderFBID, 10),
			timestampMs: timestampMs, isVideo: isVideo}
		if ongoing {
			ig.calls.Delete(threadKey)
			call = value.(instagramCall)
		}
		ig.emit(callEvent(threadKey, call.messageID, call.senderID, call.timestampMs, callEnded, call.isVideo, 0))
		return
	}
	call := instagramCall{messageID: msg.MessageID, senderID: strconv.FormatInt(msg.SenderFBID, 10),
		timestampMs: timestampMs, isVideo: isVideo}
	state := callStarted
	if !live || time.Since(time.UnixMilli(timestampMs)) > instagramCallMaxAge {
		state = callEnded
	} else {
		ig.calls.Store(threadKey, call)
	}
	ig.emit(callEvent(threadKey, call.messageID, call.senderID, call.timestampMs, state, isVideo, 0))
	if live {
		ig.emit(map[string]any{"type": "thread", "key": threadKey, "lastActivityMs": timestampMs})
	}
}

func instagramNoticeText(msg *slidetypes.Message) string {
	admin, ok := msg.Content.Content.(*slidetypes.MessageContentAdminText)
	if !ok {
		return ""
	}
	var text strings.Builder
	for _, f := range admin.TextFragments {
		text.WriteString(f.Plaintext)
	}
	return text.String()
}
