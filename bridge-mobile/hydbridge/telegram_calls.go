package hydbridge

import (
	"strconv"
	"time"

	"go.mau.fi/mautrix-telegram/pkg/gotd/tg"
)

// Telegram calls (see calls.go). A one-to-one call rings as an UpdatePhoneCall, which only the Telegram app can
// answer; when it's over, Telegram adds a service message to the chat saying how it went, which replaces the
// ringing one (both are keyed by the call's ID). Group video chats are service messages too.

// telegramCallMessageID is the message ID of a one-to-one call, whether ringing or over.
func telegramCallMessageID(key string, callID int64) string {
	return key + "/call" + strconv.FormatInt(callID, 10)
}

// telegramCallMaxAge: a call requested longer ago than this (seen after being offline) has stopped ringing.
const telegramCallMaxAge = 2 * time.Minute

func (t *Telegram) onPhoneCall(raw tg.PhoneCallClass) {
	call, ok := raw.(*tg.PhoneCallRequested)
	if !ok || call.AdminID == t.selfID.Load() || time.Since(time.Unix(int64(call.Date), 0)) > telegramCallMaxAge {
		// Only calls to the user ring; the rest of a call's life ends in a service message
		return
	}
	caller := strconv.FormatInt(call.AdminID, 10)
	key := "u" + caller
	t.emitThread(key, int64(call.Date)*1000, true)
	t.emit(callEvent(key, telegramCallMessageID(key, call.ID), caller, int64(call.Date)*1000, callIncoming, call.Video, 0))
}

// emitServiceMessage shows the service messages that are calls; it says whether msg was one.
func (t *Telegram) emitServiceMessage(msg *tg.MessageService) bool {
	key := peerKey(msg.PeerID)
	if key == "" {
		return false
	}
	sender := ""
	switch {
	case msg.Out:
		sender = strconv.FormatInt(t.selfID.Load(), 10)
	case msg.FromID != nil:
		sender = peerSenderID(msg.FromID)
	default:
		sender = peerSenderID(msg.PeerID)
	}
	timestamp := int64(msg.Date) * 1000
	switch action := msg.Action.(type) {
	case *tg.MessageActionPhoneCall:
		state := callEnded
		switch action.Reason.(type) {
		case *tg.PhoneCallDiscardReasonMissed:
			state = callMissed
		case *tg.PhoneCallDiscardReasonBusy:
			state = callDeclined
		}
		if action.Duration == 0 && state == callEnded {
			// Hung up before it was answered
			state = callMissed
		}
		if msg.Out && state == callMissed {
			// The user's own call nobody answered
			state = callEnded
		}
		t.emit(callEvent(key, telegramCallMessageID(key, action.CallID), sender, timestamp, state, action.Video, int64(action.Duration)))
	case *tg.MessageActionGroupCall:
		state := callStarted
		if action.Duration > 0 {
			state = callEnded
		}
		t.emit(callEvent(key, key+"/"+strconv.Itoa(msg.ID), sender, timestamp, state, false, int64(action.Duration)))
	default:
		return false
	}
	t.msgDates.Store(key+"/"+strconv.Itoa(msg.ID), timestamp)
	return true
}
