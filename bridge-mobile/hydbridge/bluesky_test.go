package hydbridge

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type recordingListener struct {
	lock   sync.Mutex
	events []map[string]any
}

func (l *recordingListener) OnEvent(data string) {
	var event map[string]any
	_ = json.Unmarshal([]byte(data), &event)
	l.lock.Lock()
	l.events = append(l.events, event)
	l.lock.Unlock()
}

// find returns the first event of a type whose fields include all of want.
func (l *recordingListener) find(eventType string, want map[string]any) map[string]any {
	l.lock.Lock()
	defer l.lock.Unlock()
	for _, e := range l.events {
		if e["type"] != eventType {
			continue
		}
		matches := true
		for k, v := range want {
			if e[k] != v {
				matches = false
				break
			}
		}
		if matches {
			return e
		}
	}
	return nil
}

func (l *recordingListener) waitFor(t *testing.T, eventType string, want map[string]any) map[string]any {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if e := l.find(eventType, want); e != nil {
			return e
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("no %s event with %v; got %v", eventType, want, l.events)
	return nil
}

// fakeBluesky answers the few XRPC calls the client makes, as Bluesky's server and chat service do.
func fakeBluesky(t *testing.T) *httptest.Server {
	var srv *httptest.Server
	var logCalls int
	var lock sync.Mutex
	srv = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		nsid := strings.TrimPrefix(r.URL.Path, "/xrpc/")
		if strings.HasPrefix(nsid, "chat.bsky.") {
			if r.Header.Get("Atproto-Proxy") != blueskyChatProxy || r.Header.Get("Authorization") != "Bearer access" {
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"error":"InvalidRequest","message":"not proxied"}`))
				return
			}
		}
		reply := func(body string) { _, _ = w.Write([]byte(body)) }
		switch nsid {
		case "com.atproto.server.createSession":
			var creds map[string]string
			_ = json.NewDecoder(r.Body).Decode(&creds)
			if creds["password"] != "app-pass" {
				w.WriteHeader(http.StatusUnauthorized)
				reply(`{"error":"AuthenticationRequired","message":"Invalid identifier or password"}`)
				return
			}
			reply(`{"accessJwt":"access","refreshJwt":"refresh","did":"did:plc:me","handle":"me.bsky.social",
				"didDoc":{"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"` +
				srv.URL + `"}]}}`)
		case "chat.bsky.convo.listConvos":
			reply(`{"convos":[{"id":"c1","unreadCount":1,"members":[
				{"did":"did:plc:me","handle":"me.bsky.social"},
				{"did":"did:plc:alice","handle":"alice.bsky.social","displayName":"Alice","avatar":"https://a/img"}],
				"lastMessage":{"$type":"chat.bsky.convo.defs#messageView","id":"m2","text":"hi","sentAt":"2026-10-01T10:00:01Z","sender":{"did":"did:plc:alice"}}}]}`)
		case "chat.bsky.convo.getMessages":
			reply(`{"messages":[
				{"$type":"chat.bsky.convo.defs#messageView","id":"m2","text":"hi","sentAt":"2026-10-01T10:00:01Z","sender":{"did":"did:plc:alice"},
				 "reactions":[{"value":"👍","sender":{"did":"did:plc:me"}}]},
				{"$type":"chat.bsky.convo.defs#deletedMessageView","id":"m1","sentAt":"2026-10-01T10:00:00Z","sender":{"did":"did:plc:me"}}],
				"cursor":"older"}`)
		case "chat.bsky.convo.getLog":
			lock.Lock()
			logCalls++
			n := logCalls
			lock.Unlock()
			if n == 1 || r.URL.Query().Get("cursor") != "l1" {
				reply(`{"logs":[],"cursor":"l1"}`)
				return
			}
			reply(`{"logs":[{"$type":"chat.bsky.convo.defs#logCreateMessage","convoId":"c1","message":
				{"$type":"chat.bsky.convo.defs#messageView","id":"m3","text":"new one","sentAt":"2026-10-01T10:05:00Z","sender":{"did":"did:plc:alice"}}}],
				"cursor":"l2"}`)
		case "chat.bsky.convo.sendMessage":
			reply(`{"$type":"chat.bsky.convo.defs#messageView","id":"m4","text":"sent","sentAt":"2026-10-01T10:06:00Z","sender":{"did":"did:plc:me"}}`)
		default:
			w.WriteHeader(http.StatusNotFound)
			reply(`{"error":"MethodNotImplemented","message":"` + nsid + `"}`)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func newTestBluesky(t *testing.T, srv *httptest.Server, password string) (*Bluesky, *recordingListener) {
	blueskyEntryway, blueskyPollInterval = srv.URL, 50*time.Millisecond
	listener := &recordingListener{}
	b, err := NewBluesky(`{"identifier":"@me.bsky.social","password":"`+password+`"}`, t.TempDir(), listener)
	if err != nil {
		t.Fatal(err)
	}
	b.http = srv.Client()
	t.Cleanup(b.Disconnect)
	return b, listener
}

func TestBlueskyWrongPasswordSaysSo(t *testing.T) {
	b, _ := newTestBluesky(t, fakeBluesky(t), "wrong")
	err := b.Connect()
	if err == nil || !strings.Contains(err.Error(), "Wrong Bluesky handle or app password") {
		t.Fatalf("got %v", err)
	}
}

func TestBlueskyLoadsTheInboxAndFollowsNewMessages(t *testing.T) {
	b, events := newTestBluesky(t, fakeBluesky(t), "app-pass")
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	events.waitFor(t, "self", map[string]any{"id": "did:plc:me", "name": "@me.bsky.social"})
	thread := events.waitFor(t, "thread", map[string]any{"key": "c1", "name": "Alice", "isGroup": false})
	if thread["lastReadMs"].(float64) >= thread["lastActivityMs"].(float64) {
		t.Errorf("an unread chat should read as unread: %v", thread)
	}
	events.waitFor(t, "contact", map[string]any{"id": "did:plc:alice", "name": "Alice"})
	events.waitFor(t, "message", map[string]any{"threadKey": "c1", "id": "m2", "text": "hi", "senderId": "did:plc:alice"})
	events.waitFor(t, "reaction", map[string]any{"targetId": "m2", "senderId": "did:plc:me", "emoji": "👍"})
	events.waitFor(t, "range", map[string]any{"threadKey": "c1", "hasMoreBefore": true})
	if events.find("message", map[string]any{"id": "m1"}) != nil {
		t.Error("a deleted message was shown")
	}
	// From the event log, after connecting
	events.waitFor(t, "message", map[string]any{"threadKey": "c1", "id": "m3", "text": "new one"})

	id, err := b.SendText("c1", "sent")
	if err != nil || id != "m4" {
		t.Fatalf("sending: %q %v", id, err)
	}
}

func TestBlueskySharedPostBecomesALink(t *testing.T) {
	embed := json.RawMessage(`{"$type":"app.bsky.embed.record#view","record":{"uri":"at://did:plc:x/app.bsky.feed.post/3abc","author":{"handle":"bob.bsky.social"}}}`)
	if got := blueskyEmbedText(embed); got != "https://bsky.app/profile/bob.bsky.social/post/3abc" {
		t.Fatalf("got %q", got)
	}
}
