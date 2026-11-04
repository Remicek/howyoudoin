package hydbridge

import (
	"fmt"
	"sync"
	"time"
)

// Calls on the networks HowYouDoin connects to (all but Matrix) ring in the network's own app: HowYouDoin can't
// answer them. Like Beeper's bridges, each call becomes a message of kind "call" in its chat instead, which the app
// shows (and notifies about) as "Incoming video call · answer in WhatsApp", "Missed call" and so on. The message
// carries the call's state, so the app words it in the user's language; "text" is an English fallback.

// What happened to a call, as the "callState" of its message.
const (
	// callIncoming is ringing now. A later event about the same call (same message ID) replaces it.
	callIncoming = "incoming"
	callMissed   = "missed"
	callEnded    = "ended"
	callDeclined = "declined"
	// callStarted is a group call or huddle someone started, which can be joined from the network's app.
	callStarted = "started"
)

// callEvent is the message event for a call. durationSec is 0 when unknown or not answered.
func callEvent(threadKey, id, senderID string, timestampMs int64, state string, isVideo bool, durationSec int64) map[string]any {
	event := map[string]any{
		"type": "message", "threadKey": threadKey, "id": id, "senderId": senderID, "kind": "call",
		"callState": state, "isVideo": isVideo, "text": callText(state, isVideo, durationSec), "timestampMs": timestampMs,
	}
	if durationSec > 0 {
		event["durationMs"] = durationSec * 1000
	}
	return event
}

// callText is the English description of a call, for clients that don't word it themselves.
func callText(state string, isVideo bool, durationSec int64) string {
	call := "call"
	if isVideo {
		call = "video call"
	}
	text := ""
	switch state {
	case callIncoming:
		text = "📞 Incoming " + call
	case callMissed:
		text = "📞 Missed " + call
	case callDeclined:
		text = "📞 Declined " + call
	case callStarted:
		text = "📞 Started a " + call
	default:
		if isVideo {
			text = "📞 Video call"
		} else {
			text = "📞 Call"
		}
	}
	if durationSec > 0 {
		text += fmt.Sprintf(" (%d:%02d)", durationSec/60, durationSec%60)
	}
	return text
}

// ringingCalls remembers calls announced as incoming until the network says how they ended, so the end replaces
// the same message. Entries expire on their own: some networks never report the end of a call answered elsewhere.
type ringingCalls struct {
	calls sync.Map // call ID → ringingCall
}

type ringingCall struct {
	threadKey, messageID, senderID string
	timestampMs                    int64
	isVideo                        bool
	startedAt                      time.Time
}

const ringingCallMaxAge = time.Hour

func (r *ringingCalls) put(callID string, call ringingCall) {
	call.startedAt = time.Now()
	r.calls.Store(callID, call)
}

// take returns the call announced under callID (and forgets it), if there was one recently.
func (r *ringingCalls) take(callID string) (ringingCall, bool) {
	value, ok := r.calls.LoadAndDelete(callID)
	if !ok {
		return ringingCall{}, false
	}
	call := value.(ringingCall)
	return call, time.Since(call.startedAt) < ringingCallMaxAge
}
