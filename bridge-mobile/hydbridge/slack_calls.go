package hydbridge

import (
	"strings"
	"sync"
	"time"

	"github.com/slack-go/slack"
)

// slackHuddleRingAge: a huddle started in a direct message this recently rings like a call.
const slackHuddleRingAge = 2 * time.Minute

// slackHuddleCreatorWait: how long a huddle's message waits for its room (which names who started it) before it's
// shown anyway. The message itself is posted by Slackbot, so it can't tell an incoming huddle from the user's own.
const slackHuddleCreatorWait = 3 * time.Second

// slackHuddle is a huddle seen in a conversation: its message (Slackbot's "huddle_thread", which the app shows as
// the call) and its room (sh_room_* events: who started it, and how it ended). Either can arrive first.
type slackHuddle struct {
	mu          sync.Mutex
	ts          string // the huddle message's timestamp, its message ID; "" until the message arrives
	timestampMs int64
	createdBy   string
	ended       bool
	accepted    bool
	rejected    bool
	durationSec int64
	shown       string // the state last emitted
	waiting     bool   // a timer will show it if the room doesn't come first
}

func (s *Slack) huddle(channelID string) *slackHuddle {
	h, _ := s.huddles.LoadOrStore(channelID, &slackHuddle{})
	return h.(*slackHuddle)
}

// emitHuddle shows a Slack huddle (see calls.go), which can only be joined from the Slack app. One started by
// someone else in a direct message is an incoming call while it's new; anything else is a huddle that started.
func (s *Slack) emitHuddle(channelID string, msg *slack.Message) {
	h := s.huddle(channelID)
	h.mu.Lock()
	if h.ts != "" && h.ts != msg.Timestamp {
		// A new huddle in this conversation: forget the last one (keeping the lock held)
		h.createdBy, h.ended, h.accepted, h.rejected, h.durationSec, h.shown = "", false, false, false, 0, ""
	}
	// The room may have come first: what it said stays
	h.ts, h.timestampMs = msg.Timestamp, slackTSMillis(msg.Timestamp)
	if h.createdBy == "" && msg.User != "" && msg.User != "USLACKBOT" {
		h.createdBy = msg.User
	}
	if h.createdBy == "" && !h.ended && isSlackDM(channelID) && !h.waiting {
		h.waiting = true
		h.mu.Unlock()
		time.AfterFunc(slackHuddleCreatorWait, func() {
			h.mu.Lock()
			defer h.mu.Unlock()
			h.waiting = false
			s.showHuddle(channelID, h)
		})
		return
	}
	defer h.mu.Unlock()
	s.showHuddle(channelID, h)
}

// handleHuddleRoom follows a huddle's room: who started it, and how it ended.
func (s *Slack) handleHuddleRoom(room *slack.SHRoom, huddle *slack.SHRoomHuddle) {
	channelID := ""
	if huddle != nil {
		channelID = huddle.ChannelID
	}
	if channelID == "" && len(room.Channels) > 0 {
		channelID = room.Channels[0]
	}
	if channelID == "" {
		return
	}
	h := s.huddle(channelID)
	h.mu.Lock()
	defer h.mu.Unlock()
	if room.CreatedBy != "" {
		h.createdBy = room.CreatedBy
		// The message waiting for this can show now (its timer then finds nothing changed)
		h.waiting = false
	}
	h.accepted = h.accepted || room.WasAccepted || len(room.ParticipantHistory) > 1
	h.rejected = room.WasRejected
	if room.HasEnded || room.DateEnd > 0 {
		h.ended = true
		if room.DateEnd > room.DateStart && room.DateStart > 0 {
			h.durationSec = room.DateEnd - room.DateStart
		}
	}
	s.showHuddle(channelID, h)
}

// showHuddle emits the huddle's message in its current state, once its message is known. Called with h.mu held.
func (s *Slack) showHuddle(channelID string, h *slackHuddle) {
	if h.ts == "" || h.waiting {
		return
	}
	isDM := isSlackDM(channelID)
	fromOther := h.createdBy != s.selfID
	state, duration := callStarted, int64(0)
	switch {
	case h.ended && isDM && fromOther && h.rejected:
		state = callDeclined
	case h.ended && isDM && fromOther && !h.accepted:
		state = callMissed
	case h.ended:
		state = callEnded
		if h.accepted {
			duration = h.durationSec
		}
	case isDM && fromOther && time.Since(time.UnixMilli(h.timestampMs)) < slackHuddleRingAge:
		state = callIncoming
	}
	if state == h.shown {
		return
	}
	h.shown = state
	sender := h.createdBy
	if sender == "" {
		sender = "USLACKBOT"
	}
	s.emit(callEvent(channelID, h.ts, sender, h.timestampMs, state, false, duration))
}

func isSlackDM(channelID string) bool { return strings.HasPrefix(channelID, "D") }
