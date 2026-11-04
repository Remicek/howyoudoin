package hydbridge

import (
	"encoding/json"
	"slices"
	"time"
)

// Discord calls (see calls.go). A call in a DM or group DM is a message of type 3 that Discord posts when the
// call starts (it rings then) and updates when it ends, with who joined. discordgo's Message leaves the call
// details out, so they're read from the raw gateway event.

type discordCallMessage struct {
	ID        string    `json:"id"`
	ChannelID string    `json:"channel_id"`
	Type      int       `json:"type"`
	Timestamp time.Time `json:"timestamp"`
	Author    *struct {
		ID string `json:"id"`
	} `json:"author"`
	Call *struct {
		Participants   []string   `json:"participants"`
		EndedTimestamp *time.Time `json:"ended_timestamp"`
	} `json:"call"`
}

// discordCallMaxAge: a call started longer ago than this that's still going isn't ringing any more.
const discordCallMaxAge = 2 * time.Minute

// handleRawCallMessage shows a call's message: live from the gateway, or from history (where a call that hasn't
// ended doesn't ring).
func (d *Discord) handleRawCallMessage(raw json.RawMessage, live bool) {
	var m discordCallMessage
	if err := json.Unmarshal(raw, &m); err != nil || m.Type != 3 || m.Call == nil || !d.isPrivate(m.ChannelID) {
		return
	}
	caller := ""
	if m.Author != nil {
		caller = m.Author.ID
	}
	state, duration := discordCallState(&m, caller, d.selfID)
	if !live && state == callIncoming {
		state = ""
	}
	if state == "" {
		return
	}
	d.emit(callEvent(m.ChannelID, m.ID, caller, m.Timestamp.UnixMilli(), state, false, duration))
	if live {
		d.emit(map[string]any{"type": "thread", "key": m.ChannelID, "lastActivityMs": m.Timestamp.UnixMilli()})
	}
}

// discordCallState is how the call in m went for the user selfID; "" for one not worth showing.
func discordCallState(m *discordCallMessage, caller, selfID string) (string, int64) {
	if m.Call.EndedTimestamp == nil {
		if caller == selfID {
			return callEnded, 0
		}
		if time.Since(m.Timestamp) > discordCallMaxAge {
			return "", 0
		}
		return callIncoming, 0
	}
	duration := int64(m.Call.EndedTimestamp.Sub(m.Timestamp).Seconds())
	if caller != selfID && !slices.Contains(m.Call.Participants, selfID) {
		return callMissed, 0
	}
	return callEnded, duration
}
