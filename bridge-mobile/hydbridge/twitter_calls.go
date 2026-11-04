package hydbridge

import (
	"time"

	"go.mau.fi/mautrix-twitter/pkg/twittermeow/data/types"
)

// twitterCallMaxAge: a call started longer ago than this has stopped ringing.
const twitterCallMaxAge = 2 * time.Minute

// emitCall shows an X call (see calls.go). X posts separate entries when a call starts and when it's missed or
// ends; only a start that's ringing now is shown, the rest of the call is in the entry that follows. In practice an
// unanswered call only gets its "missed" entry, posted when it stops ringing (tested 2026-11-04), so X is LOGGED.
func (t *Twitter) emitCall(call *types.XChatCall) {
	timestamp := twitterMillis(call.Time)
	state := call.State
	if state == callStarted {
		if call.SenderID == t.selfID || time.Since(time.UnixMilli(timestamp)) > twitterCallMaxAge {
			return
		}
		state = callIncoming
	}
	if state == callMissed && call.SenderID == t.selfID {
		// The user's own call nobody answered
		state = callEnded
	}
	t.emit(callEvent(call.ConversationID, call.ID, call.SenderID, timestamp, state, !call.IsAudioOnly, call.DurationSeconds))
	t.emit(map[string]any{"type": "thread", "key": call.ConversationID, "lastActivityMs": timestamp})
}
