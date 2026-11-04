package hydbridge

import (
	"fmt"
	"strings"
	"time"

	"go.mau.fi/mautrix-meta/pkg/messagix/table"
	"go.mau.fi/whatsmeow/types"
)

// Messenger calls (see calls.go). Messenger logs a call in its chat as a message whose card ("XMA" attachment)
// has a call-back button; mautrix-meta doesn't bridge them, so they're recognised by their buttons' action
// types ("xma_rtc_…"), which name the call kind. Calls only ring in Messenger's own app.
func (m *Messenger) emitCallLogs(tbl *table.LSTable) {
	if len(tbl.LSInsertXmaAttachment) == 0 {
		return
	}
	senders := map[string]int64{}
	for _, msg := range tbl.LSInsertMessage {
		senders[msg.MessageId] = msg.SenderId
	}
	for _, msg := range tbl.LSUpsertMessage {
		senders[msg.MessageId] = msg.SenderId
	}
	for _, a := range tbl.LSInsertXmaAttachment {
		ctaTypes := strings.ToLower(strings.Join([]string{a.Cta1Type, a.Cta2Type, a.Cta3Type}, " "))
		if !strings.Contains(ctaTypes, "xma_rtc") {
			if ctaTypes = strings.TrimSpace(ctaTypes); ctaTypes != "" {
				// Diagnostics: which card buttons arrive (types only, no content)
				m.emit(map[string]any{"type": "debug", "event": "xma " + ctaTypes})
			}
			continue
		}
		state, isVideo := metaCallState(ctaTypes)
		sender := senders[a.MessageId]
		if state == callMissed && id(sender) == m.SelfID() {
			// The user's own call nobody answered
			state = callEnded
		}
		m.emit(callEvent(id(a.ThreadKey), a.MessageId, id(sender), a.TimestampMs, state, isVideo, 0))
	}
}

// metaCallState reads a call log's state and kind from its card's button types.
func metaCallState(ctaTypes string) (string, bool) {
	isVideo := strings.Contains(ctaTypes, "video")
	switch {
	case strings.Contains(ctaTypes, "missed"):
		return callMissed, isVideo
	case strings.Contains(ctaTypes, "declin"), strings.Contains(ctaTypes, "reject"):
		return callDeclined, isVideo
	default:
		return callEnded, isVideo
	}
}

// metaCallMaxAge: calls offered longer ago than this (delivered after being offline) are over already.
const metaCallMaxAge = 2 * time.Minute

// debugE2EECall notes which call events encrypted chats get (types only, no content).
func (m *Messenger) debugE2EECall(evt any) {
	m.emit(map[string]any{"type": "debug", "event": fmt.Sprintf("e2ee %T", evt)})
}

// emitE2EECallOffer shows a call ringing in an encrypted chat. Encrypted chats run on the WhatsApp protocol, so
// their calls are announced like WhatsApp's (and, like there, only Messenger's own app can answer them).
func (m *Messenger) emitE2EECallOffer(meta types.BasicCallMeta, isVideo bool) {
	if meta.CallID == "" || time.Since(meta.Timestamp) > metaCallMaxAge {
		return
	}
	caller := meta.CallCreator.ToNonAD()
	if m.e2ee != nil && m.e2ee.Store.ID != nil && caller.User == m.e2ee.Store.ID.User {
		// The user calling from another device
		return
	}
	if _, known := m.ringing.calls.Load(meta.CallID); known {
		// A call is announced both as an offer and as an offer notice
		return
	}
	threadKey := caller.User
	if !meta.GroupJID.IsEmpty() {
		threadKey = meta.GroupJID.User
	}
	m.ensureContact(caller.User)
	call := ringingCall{threadKey: threadKey, messageID: "call-" + meta.CallID, senderID: caller.User,
		timestampMs: meta.Timestamp.UnixMilli(), isVideo: isVideo}
	m.ringing.put(meta.CallID, call)
	event := callEvent(call.threadKey, call.messageID, call.senderID, call.timestampMs, callIncoming, isVideo, 0)
	event["isEncrypted"] = true
	m.emit(event)
}

// endE2EECall replaces a ringing call's message with how it ended.
func (m *Messenger) endE2EECall(callID, unanswered string) {
	call, ok := m.ringing.take(callID)
	if !ok {
		return
	}
	state, duration := unanswered, int64(0)
	if answeredAt, wasAnswered := m.answered.LoadAndDelete(callID); wasAnswered {
		state, duration = callEnded, int64(time.Since(answeredAt.(time.Time)).Seconds())
	}
	event := callEvent(call.threadKey, call.messageID, call.senderID, call.timestampMs, state, call.isVideo, duration)
	event["isEncrypted"] = true
	m.emit(event)
}
