package hydbridge

import (
	"context"
	"encoding/json"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"go.mau.fi/mautrix-signal/pkg/signalmeow/events"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"go.mau.fi/mautrix-telegram/pkg/gotd/tg"
	twtypes "go.mau.fi/mautrix-twitter/pkg/twittermeow/data/types"
	"go.mau.fi/whatsmeow"
	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/types"
	waEvents "go.mau.fi/whatsmeow/types/events"

	"github.com/bwmarrin/discordgo"
	"github.com/google/uuid"
	"github.com/slack-go/slack"
	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/util/jsontime"
)

func TestCallTextDescribesEachState(t *testing.T) {
	cases := []struct {
		state    string
		isVideo  bool
		duration int64
		want     string
	}{
		{callIncoming, false, 0, "📞 Incoming call"},
		{callIncoming, true, 0, "📞 Incoming video call"},
		{callMissed, true, 0, "📞 Missed video call"},
		{callDeclined, false, 0, "📞 Declined call"},
		{callEnded, false, 75, "📞 Call (1:15)"},
		{callEnded, true, 0, "📞 Video call"},
		{callStarted, false, 0, "📞 Started a call"},
	}
	for _, c := range cases {
		if got := callText(c.state, c.isVideo, c.duration); got != c.want {
			t.Errorf("%s video=%v %ds: %q, want %q", c.state, c.isVideo, c.duration, got, c.want)
		}
	}
}

func TestWhatsAppCallRingsThenShowsHowItEnded(t *testing.T) {
	l := &recordingListener{}
	w := &WhatsApp{listener: l}
	caller := types.NewJID("48111222333", types.DefaultUserServer)
	meta := types.BasicCallMeta{From: caller, CallCreator: caller, CallID: "C1", Timestamp: time.Now()}
	w.handleEvent(&waEvents.CallOfferNotice{BasicCallMeta: meta, Media: "video"})
	w.handleEvent(&waEvents.CallOffer{BasicCallMeta: meta}) // the same call again: not a second message
	ringing := l.find("message", map[string]any{"id": "call-C1", "callState": callIncoming})
	if ringing == nil || ringing["isVideo"] != true || ringing["threadKey"] != caller.String() || ringing["kind"] != "call" {
		t.Fatalf("ringing call: %v", ringing)
	}
	w.handleEvent(&waEvents.CallTerminate{BasicCallMeta: meta})
	if l.find("message", map[string]any{"id": "call-C1", "callState": callMissed}) == nil {
		t.Fatal("unanswered call wasn't shown as missed")
	}
	if n := countEvents(l, "message"); n != 2 {
		t.Errorf("%d message events, want 2", n)
	}

	// Old offers (delivered after being offline) don't ring
	old := meta
	old.CallID, old.Timestamp = "C2", time.Now().Add(-time.Hour)
	w.handleEvent(&waEvents.CallOfferNotice{BasicCallMeta: old, Media: "audio"})
	if l.find("message", map[string]any{"id": "call-C2"}) != nil {
		t.Error("an hour-old call rang")
	}
}

func TestWhatsAppCallAnsweredOnPhoneAfterItStoppedRinging(t *testing.T) {
	l := &recordingListener{}
	w := &WhatsApp{listener: l}
	caller := types.NewJID("48111222333", types.DefaultUserServer)
	meta := types.BasicCallMeta{From: caller, CallCreator: caller, CallID: "C1", Timestamp: time.Now()}
	w.handleEvent(&waEvents.CallOffer{BasicCallMeta: meta})
	// The phone answers: this device is told to stop ringing first, then that the phone answered
	w.handleEvent(&waEvents.CallTerminate{BasicCallMeta: meta})
	w.handleEvent(&waEvents.CallAccept{BasicCallMeta: meta})
	if l.find("message", map[string]any{"id": "call-C1", "callState": callEnded}) == nil {
		t.Fatal("a call answered on the phone stayed missed")
	}
	// The end of the conversation
	before := countEvents(l, "message")
	w.handleEvent(&waEvents.CallTerminate{BasicCallMeta: meta})
	if countEvents(l, "message") != before+1 {
		t.Error("the end of an answered call wasn't shown")
	}
}

func TestWhatsAppGroupCallJoinedFromSummary(t *testing.T) {
	selfPN := types.NewJID("48100000001", types.DefaultUserServer)
	device := &store.Device{ID: &types.JID{User: selfPN.User, Device: 7, Server: types.DefaultUserServer},
		LID: types.NewJID("111", types.HiddenUserServer)}
	caller := types.NewJID("48100000002", types.DefaultUserServer)
	group := types.NewJID("1203", types.GroupServer)
	summary := func(selfState string) *waBinary.Node {
		return &waBinary.Node{Tag: "terminate", Content: []waBinary.Node{{
			Tag: "call_summary", Attrs: waBinary.Attrs{"call_duration": "46288"},
			Content: []waBinary.Node{
				{Tag: "user", Attrs: waBinary.Attrs{"jid": types.NewJID("222", types.HiddenUserServer), "state": "connected", "user_pn": caller}},
				{Tag: "user", Attrs: waBinary.Attrs{"jid": device.LID, "state": selfState, "user_pn": selfPN}},
			},
		}}}
	}
	// The phone joins: this device hears "accepted elsewhere" (no accept), then the summary as the call ends
	l := &recordingListener{}
	w := &WhatsApp{listener: l, client: &whatsmeow.Client{Store: device}}
	meta := types.BasicCallMeta{From: caller, CallCreator: caller, GroupJID: group, CallID: "G-joined", Timestamp: time.Now()}
	w.handleEvent(&waEvents.CallOffer{BasicCallMeta: meta})
	w.handleEvent(&waEvents.CallTerminate{BasicCallMeta: meta, Reason: "accepted_elsewhere"})
	if l.find("message", map[string]any{"id": "call-G-joined", "callState": callEnded}) == nil {
		t.Fatal("a group call the phone joined wasn't shown as answered")
	}
	w.handleEvent(&waEvents.CallTerminate{BasicCallMeta: meta, Reason: "group_call_ended", Data: summary("connected")})
	if l.find("message", map[string]any{"id": "call-G-joined", "callState": callEnded, "durationMs": float64(46000)}) == nil {
		t.Error("the end of a joined group call didn't add its length")
	}
	if l.find("message", map[string]any{"id": "call-G-joined", "callState": callMissed}) != nil {
		t.Error("a joined group call became missed")
	}

	for _, tc := range []struct{ selfState, want string }{{"connected", callEnded}, {"invited", callMissed}} {
		l := &recordingListener{}
		w := &WhatsApp{listener: l, client: &whatsmeow.Client{Store: device}}
		meta := types.BasicCallMeta{From: caller, CallCreator: caller, GroupJID: group, CallID: "G-" + tc.selfState, Timestamp: time.Now()}
		w.handleEvent(&waEvents.CallOffer{BasicCallMeta: meta})
		w.handleEvent(&waEvents.CallTerminate{BasicCallMeta: meta, Reason: "group_call_ended", Data: summary(tc.selfState)})
		e := l.find("message", map[string]any{"id": "call-G-" + tc.selfState, "callState": tc.want})
		if e == nil {
			t.Errorf("user %s: not shown as %s", tc.selfState, tc.want)
		} else if tc.want == callEnded && e["durationMs"] != float64(46000) {
			t.Errorf("joined group call lasted %v, want 46000", e["durationMs"])
		}
	}
}

func TestSignalCallKeepsVideoAndAnsweredElsewhere(t *testing.T) {
	l := &recordingListener{}
	s := &Signal{listener: l}
	sender := uuid.New()
	info := events.MessageInfo{Sender: sender, ChatID: sender.String()}
	now := uint64(time.Now().UnixMilli())
	s.handleEvent(&events.Call{Info: info, Timestamp: now, IsRinging: true, CallID: 7, IsVideo: true})
	if e := l.find("message", map[string]any{"id": "call|7", "callState": callIncoming, "isVideo": true}); e == nil {
		t.Fatal("ringing video call missing")
	}
	s.handleEvent(&events.Call{Info: info, Timestamp: now, CallID: 7, HangupType: signalpb.CallMessage_Hangup_HANGUP_ACCEPTED})
	s.handleEvent(&events.Call{Info: info, Timestamp: now, CallID: 7})
	if e := l.find("message", map[string]any{"id": "call|7", "callState": callEnded, "isVideo": true}); e == nil {
		t.Fatal("call answered on the phone wasn't shown as ended")
	}

	s.handleEvent(&events.Call{Info: info, Timestamp: now, IsRinging: true, CallID: 8})
	s.handleEvent(&events.Call{Info: info, Timestamp: now, CallID: 8, HangupType: signalpb.CallMessage_Hangup_HANGUP_DECLINED})
	if l.find("message", map[string]any{"id": "call|8", "callState": callDeclined, "isVideo": false}) == nil {
		t.Fatal("declined voice call missing")
	}
}

func TestTelegramCallRingsAndItsLogReplacesIt(t *testing.T) {
	l := &recordingListener{}
	tc := &Telegram{listener: l, store: loadTelegramStore(filepath.Join(t.TempDir(), "telegram-state.json"))}
	tc.selfID.Store(1)
	tc.onPhoneCall(&tg.PhoneCallRequested{ID: 99, AdminID: 42, ParticipantID: 1, Video: true, Date: int(time.Now().Unix())})
	ringing := l.find("message", map[string]any{"id": "u42/call99", "callState": callIncoming, "isVideo": true})
	if ringing == nil || ringing["threadKey"] != "u42" || ringing["senderId"] != "42" {
		t.Fatalf("ringing call: %v", ringing)
	}
	tc.onNewMessage(&tg.MessageService{ID: 5, PeerID: &tg.PeerUser{UserID: 42}, FromID: &tg.PeerUser{UserID: 42},
		Date: int(time.Now().Unix()), Action: &tg.MessageActionPhoneCall{CallID: 99, Video: true,
			Reason: &tg.PhoneCallDiscardReasonMissed{}}})
	if l.find("message", map[string]any{"id": "u42/call99", "callState": callMissed}) == nil {
		t.Fatal("missed call log didn't replace the ringing call")
	}
	tc.emitMessage(&tg.MessageService{ID: 6, PeerID: &tg.PeerUser{UserID: 42}, Out: true,
		Date: int(time.Now().Unix()), Action: &tg.MessageActionPhoneCall{CallID: 100, Duration: 61,
			Reason: &tg.PhoneCallDiscardReasonHangup{}}})
	if e := l.find("message", map[string]any{"id": "u42/call100", "callState": callEnded, "text": "📞 Call (1:01)"}); e == nil {
		t.Fatal("ended call from history missing")
	}
	// Other service messages (someone joined, a pin...) still aren't messages
	before := countEvents(l, "message")
	tc.emitMessage(&tg.MessageService{ID: 7, PeerID: &tg.PeerChat{ChatID: 3}, Action: &tg.MessageActionChatJoinedByLink{}})
	if countEvents(l, "message") != before {
		t.Error("a non-call service message was shown")
	}
}

func TestDiscordCallState(t *testing.T) {
	started := time.Now().Add(-30 * time.Second)
	ended := started.Add(90 * time.Second)
	call := func(participants []string, endedAt *time.Time, at time.Time) *discordCallMessage {
		m := &discordCallMessage{Timestamp: at}
		m.Call = &struct {
			Participants   []string   `json:"participants"`
			EndedTimestamp *time.Time `json:"ended_timestamp"`
		}{participants, endedAt}
		return m
	}
	check := func(name string, m *discordCallMessage, caller, wantState string, wantDuration int64) {
		state, duration := discordCallState(m, caller, "me")
		if state != wantState || duration != wantDuration {
			t.Errorf("%s: %q %d, want %q %d", name, state, duration, wantState, wantDuration)
		}
	}
	check("ringing", call([]string{"them"}, nil, started), "them", callIncoming, 0)
	check("stale ringing", call([]string{"them"}, nil, started.Add(-time.Hour)), "them", "", 0)
	check("missed", call([]string{"them"}, &ended, started), "them", callMissed, 0)
	check("answered", call([]string{"them", "me"}, &ended, started), "them", callEnded, 90)
	check("own call", call([]string{"me"}, &ended, started), "me", callEnded, 90)
}

func TestTwitterCallStartRingsOnlyFromOthers(t *testing.T) {
	l := &recordingListener{}
	x := &Twitter{listener: l, selfID: "me"}
	now := strconv.FormatInt(time.Now().UnixMilli(), 10)
	x.handleEvent(context.Background(), &twtypes.XChatCall{ID: "s1", Time: now, ConversationID: "c", SenderID: "them", State: "started"})
	x.handleEvent(context.Background(), &twtypes.XChatCall{ID: "s2", Time: now, ConversationID: "c", SenderID: "me", State: "started"})
	x.handleEvent(context.Background(), &twtypes.XChatCall{ID: "s3", Time: now, ConversationID: "c", SenderID: "them", State: "ended",
		DurationSeconds: 5, IsAudioOnly: true})
	if l.find("message", map[string]any{"id": "s1", "callState": callIncoming, "isVideo": true}) == nil {
		t.Error("incoming video call missing")
	}
	if l.find("message", map[string]any{"id": "s2"}) != nil {
		t.Error("the user's own call rang")
	}
	if l.find("message", map[string]any{"id": "s3", "callState": callEnded, "isVideo": false, "durationMs": float64(5000)}) == nil {
		t.Error("ended voice call missing")
	}
}

func TestSlackHuddleInDMRings(t *testing.T) {
	l := &recordingListener{}
	s := &Slack{listener: l, selfID: "U1"}
	ts := strconv.FormatFloat(float64(time.Now().UnixMilli())/1000, 'f', 6, 64)
	s.emitHuddle("D123", &slack.Message{Msg: slack.Msg{Timestamp: ts, User: "U2", SubType: "huddle_thread"}})
	s.emitHuddle("C123", &slack.Message{Msg: slack.Msg{Timestamp: ts + "1", User: "U2", SubType: "huddle_thread"}})
	if l.find("message", map[string]any{"threadKey": "D123", "callState": callIncoming}) == nil {
		t.Error("huddle in a DM didn't ring")
	}
	if l.find("message", map[string]any{"threadKey": "C123", "callState": callStarted}) == nil {
		t.Error("huddle in a channel missing")
	}
}

// Huddle messages are posted by Slackbot: who started a huddle comes from its room, in either order.
func TestSlackHuddleCreatorFromRoom(t *testing.T) {
	l := &recordingListener{}
	s := &Slack{listener: l, selfID: "U1"}
	now := float64(time.Now().UnixMilli()) / 1000
	own := strconv.FormatFloat(now, 'f', 6, 64)
	theirs := strconv.FormatFloat(now+1, 'f', 6, 64)

	// The user's own huddle: room first, then Slackbot's message
	s.handleHuddleRoom(&slack.SHRoom{CreatedBy: "U1"}, &slack.SHRoomHuddle{ChannelID: "D1"})
	s.emitHuddle("D1", &slack.Message{Msg: slack.Msg{Timestamp: own, User: "USLACKBOT", SubType: "huddle_thread"}})
	if l.find("message", map[string]any{"id": own, "callState": callIncoming}) != nil {
		t.Error("the user's own huddle rang")
	}
	if l.find("message", map[string]any{"id": own, "callState": callStarted}) == nil {
		t.Error("the user's own huddle missing")
	}

	// Someone else's: message first (it waits for the room), then the room, then the room ending unanswered
	s.emitHuddle("D2", &slack.Message{Msg: slack.Msg{Timestamp: theirs, User: "USLACKBOT", SubType: "huddle_thread"}})
	if l.find("message", map[string]any{"id": theirs}) != nil {
		t.Error("a huddle of unknown origin was shown before its room came")
	}
	s.handleHuddleRoom(&slack.SHRoom{CreatedBy: "U2", ParticipantHistory: []string{"U2"}}, &slack.SHRoomHuddle{ChannelID: "D2"})
	if l.find("message", map[string]any{"id": theirs, "callState": callIncoming, "senderId": "U2"}) == nil {
		t.Error("incoming huddle didn't ring")
	}
	s.handleHuddleRoom(&slack.SHRoom{CreatedBy: "U2", ParticipantHistory: []string{"U2"}, HasEnded: true},
		&slack.SHRoomHuddle{ChannelID: "D2"})
	if l.find("message", map[string]any{"id": theirs, "callState": callMissed}) == nil {
		t.Error("unanswered huddle didn't become a missed call")
	}
}

func TestSlackHuddleShownWithoutRoomAfterWaiting(t *testing.T) {
	l := &recordingListener{}
	s := &Slack{listener: l, selfID: "U1"}
	ts := strconv.FormatFloat(float64(time.Now().UnixMilli())/1000, 'f', 6, 64)
	s.emitHuddle("D3", &slack.Message{Msg: slack.Msg{Timestamp: ts, User: "USLACKBOT", SubType: "huddle_thread"}})
	time.Sleep(slackHuddleCreatorWait + 200*time.Millisecond)
	if l.find("message", map[string]any{"id": ts, "callState": callIncoming}) == nil {
		t.Error("huddle never shown without its room")
	}
}

func TestMetaCallStateFromButtons(t *testing.T) {
	cases := map[string][2]any{
		"xma_rtc_missed_video":   {callMissed, true},
		"xma_rtc_ended_audio":    {callEnded, false},
		"xma_rtc_declined_audio": {callDeclined, false},
	}
	for cta, want := range cases {
		state, isVideo := metaCallState(cta)
		if state != want[0] || isVideo != want[1] {
			t.Errorf("%s: %s video=%v", cta, state, isVideo)
		}
	}
}

func countEvents(l *recordingListener, eventType string) int {
	l.lock.Lock()
	defer l.lock.Unlock()
	n := 0
	for _, e := range l.events {
		if e["type"] == eventType {
			n++
		}
	}
	return n
}

func TestDiscordHistoryCall(t *testing.T) {
	l := &recordingListener{}
	d := &Discord{listener: l, selfID: "me"}
	d.channels.Store("C1", &discordgo.Channel{ID: "C1"})
	at := time.Now().Add(-5 * time.Minute).UTC()
	raw := func(id, participants string, ended bool) json.RawMessage {
		end := ""
		if ended {
			end = `,"ended_timestamp":"` + at.Add(time.Minute).Format(time.RFC3339) + `"`
		}
		return json.RawMessage(`{"id":"` + id + `","channel_id":"C1","type":3,"timestamp":"` + at.Format(time.RFC3339) +
			`","author":{"id":"them"},"call":{"participants":[` + participants + `]` + end + `}}`)
	}
	d.handleRawCallMessage(raw("M1", `"them"`, true), false)
	if l.find("message", map[string]any{"id": "M1", "callState": callMissed}) == nil {
		t.Error("an unanswered call from history isn't missed")
	}
	d.handleRawCallMessage(raw("M2", `"them","me"`, true), false)
	if l.find("message", map[string]any{"id": "M2", "callState": callEnded, "durationMs": float64(60000)}) == nil {
		t.Error("an answered call from history isn't ended with its length")
	}
	at = time.Now().UTC()
	d.handleRawCallMessage(raw("M3", `"them"`, false), false)
	if l.find("message", map[string]any{"id": "M3"}) != nil {
		t.Error("a call from history rang")
	}
}

func TestInstagramCallNotices(t *testing.T) {
	l := &recordingListener{}
	ig := &Instagram{listener: l}
	notice := func(id, snippet string, at time.Time) *slidetypes.Delta {
		return &slidetypes.Delta{ThreadIGID: "T1", Data: &slidetypes.AdminMessageEvent{Message: &slidetypes.Message{
			MessageID: id, SenderFBID: 42, ContentType: "IG_VIDEO_CALL_XMAT", IGDSnippet: snippet,
			TimestampMS: jsontime.UnixMilliString{Time: at},
		}}}
	}
	now := time.Now()
	ig.handleDelta(notice("start", "remol270 started an audio call", now))
	if l.find("message", map[string]any{"id": "start", "kind": "call", "callState": callStarted, "isVideo": false}) == nil {
		t.Fatal("a call that started wasn't shown")
	}
	ig.handleDelta(notice("end", "Audio call ended", now.Add(20*time.Second)))
	if l.find("message", map[string]any{"id": "start", "callState": callEnded}) == nil {
		t.Fatal("the end didn't update the call")
	}
	if l.find("message", map[string]any{"id": "end"}) != nil {
		t.Error("the end notice was shown as a message of its own")
	}

	// From history: the start is a call that ended, the end notice isn't shown
	ig.emitMessage("T2", notice("old", "remol270 started a video call", now.Add(-time.Hour)).Data.(*slidetypes.AdminMessageEvent).Message)
	ig.emitMessage("T2", notice("oldEnd", "Video call ended", now.Add(-time.Hour)).Data.(*slidetypes.AdminMessageEvent).Message)
	if l.find("message", map[string]any{"id": "old", "callState": callEnded, "isVideo": true}) == nil {
		t.Error("a call from history wasn't shown as ended")
	}
	if l.find("message", map[string]any{"id": "oldEnd"}) != nil {
		t.Error("an end notice from history was shown")
	}
}
