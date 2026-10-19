package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/rs/zerolog"

	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
)

// Instagram is an Instagram DM account (mautrix-meta's instameow client) running in the app. It emits the same
// JSON events as Messenger; thread keys are thread FBIDs and contact IDs are the users' interop FBIDs.
type Instagram struct {
	client   *instameow.Client
	listener Listener
	log      zerolog.Logger

	lock   sync.Mutex
	cancel context.CancelFunc

	// IG thread ID → thread key, for typing notifications (which use the IG ID), and back, for sending
	threadKeys  sync.Map
	igThreadIDs sync.Map
	// Threads being fetched because a message arrived for one the inbox sync didn't have
	threadsLoading sync.Map
}

// NewInstagram takes the instagram.com session cookies as a JSON object; sessionid, csrftoken and ds_user_id are
// required.
func NewInstagram(cookiesJSON string, listener Listener) (*Instagram, error) {
	values := map[cookies.MetaCookieName]string{}
	if err := json.Unmarshal([]byte(cookiesJSON), &values); err != nil {
		return nil, fmt.Errorf("invalid cookies: %w", err)
	}
	jar := &cookies.Cookies{Platform: types.Instagram}
	jar.UpdateValues(values)
	if missing := jar.GetMissingCookieNames(); len(missing) > 0 {
		return nil, fmt.Errorf("missing cookies: %v", missing)
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "instameow").Logger().Level(zerolog.InfoLevel)
	ig := &Instagram{listener: listener, log: log}
	ig.client = instameow.NewClient(instameow.ClientParams{Cookies: jar, Log: log, EventHandler: ig.handleEvent})
	return ig, nil
}

// Connect loads the inbox (threads, people and recent messages) and opens the realtime connection.
func (ig *Instagram) Connect() error {
	ig.lock.Lock()
	if ig.cancel != nil {
		ig.lock.Unlock()
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(ig.log.WithContext(context.Background()))
	ig.cancel = cancel
	ig.lock.Unlock()

	viewer, mailbox, err := ig.client.LoadIndex(ctx)
	if err != nil {
		ig.resetCancel()
		return fmt.Errorf("failed to load inbox: %w", err)
	}
	selfID := strconv.FormatInt(ig.client.GetOwnFBID(), 10)
	ig.emit(map[string]any{"type": "self", "id": selfID, "name": viewer.Data.Username})
	for _, edge := range mailbox.ThreadsByFolder.Edges {
		ig.emitThread(edge.Node.AsIGDirectThread)
	}
	for _, thread := range mailbox.PinnedThreadsV2 {
		ig.emitThread(thread.AsIGDirectThread)
	}
	go ig.client.Connect(ctx)
	return nil
}

func (ig *Instagram) resetCancel() {
	ig.lock.Lock()
	defer ig.lock.Unlock()
	if ig.cancel != nil {
		ig.cancel()
		ig.cancel = nil
	}
}

// Disconnect closes the realtime connection; Connect can be called again.
func (ig *Instagram) Disconnect() {
	ig.client.Disconnect()
	ig.resetCancel()
	ig.emitState("disconnected", "")
}

// SendText sends a text message to a thread (by thread key) and returns its offline threading ID (the echo carries
// it as otid).
func (ig *Instagram) SendText(threadKey string, text string) (string, error) {
	rawID, ok := ig.igThreadIDs.Load(threadKey)
	if !ok {
		return "", errors.New("unknown thread")
	}
	threadIGID := rawID.(string)
	otid := strconv.FormatInt(methods.GenerateEpochID(), 10)
	_, err := ig.client.SendMessage(context.Background(), &slidetypes.SendTextRequest{
		IGThreadIGID:       &threadIGID,
		OfflineThreadingID: otid,
		Text:               slidetypes.SensitiveString{Value: text},
	})
	if err != nil {
		return "", err
	}
	return otid, nil
}

func (ig *Instagram) handleEvent(_ context.Context, rawEvt slidetypes.ClientEvent) error {
	switch evt := rawEvt.(type) {
	case *slidetypes.Connected:
		ig.emitState("connected", "")
	case *slidetypes.Disconnected:
		ig.emitState("reconnecting", "")
	case *slidetypes.AuthError:
		ig.emitState("error", "Instagram session expired, log in again")
	case *slidetypes.Delta:
		ig.handleDelta(evt)
	case *slidetypes.TypingNotification:
		if key, ok := ig.threadKeys.Load(evt.ThreadID); ok {
			ig.emit(map[string]any{"type": "typing", "threadKey": key, "senderId": strconv.FormatInt(evt.SenderID, 10),
				"isTyping": evt.ActivityStatus != 0})
		}
	}
	return nil
}

func (ig *Instagram) handleDelta(delta *slidetypes.Delta) {
	threadKey := delta.ThreadIGID
	switch evt := delta.Data.(type) {
	case *slidetypes.NewMessageEvent:
		ig.emitMessage(threadKey, evt.Message)
		if _, known := ig.igThreadIDs.Load(threadKey); !known {
			// A conversation started after connecting (e.g. someone new writing): load it so it shows up
			go ig.loadNewThread(threadKey)
		}
	case *slidetypes.CreateReactionEvent:
		ig.emit(map[string]any{"type": "reaction", "threadKey": threadKey, "targetId": evt.MessageID,
			"senderId": strconv.FormatInt(evt.Reaction.SenderFBID, 10), "emoji": evt.Reaction.Reaction})
	case *slidetypes.DeleteReactionEvent:
		ig.emit(map[string]any{"type": "reaction", "threadKey": threadKey, "targetId": evt.MessageID,
			"senderId": strconv.FormatInt(evt.Reaction.SenderFBID, 10), "emoji": ""})
	case *slidetypes.ReadReceiptEvent:
		ig.emit(map[string]any{"type": "read", "threadKey": threadKey,
			"contactId":   strconv.FormatInt(evt.ReadReceipt.ParticipantFBID, 10),
			"watermarkMs": evt.ReadReceipt.WatermarkTimestampMS.UnixMilli()})
	}
}

func (ig *Instagram) emitThread(thread *slidetypes.ThreadInfo) {
	if thread == nil || thread.ThreadFBID == "" {
		return
	}
	key := thread.ThreadFBID
	ig.threadKeys.Store(thread.ID, key)
	ig.threadKeys.Store(thread.ThreadID, key)
	ig.igThreadIDs.Store(key, thread.ID)
	for _, user := range thread.Users {
		if user != nil && user.InteropMessagingUserFBID != 0 {
			id := strconv.FormatInt(user.InteropMessagingUserFBID, 10)
			ig.emit(map[string]any{"type": "contact", "id": id, "name": firstNonEmpty(user.FullName, user.Username),
				"firstName": user.Username, "avatarUrl": user.ProfilePicURL})
			ig.emit(map[string]any{"type": "participant", "threadKey": key, "contactId": id})
		}
	}
	lastActivity := thread.LastActivityTimestampMS.UnixMilli()
	ig.emit(map[string]any{
		"type": "thread", "key": key, "name": thread.ThreadTitle, "isGroup": thread.IsGroup, "isEncrypted": false,
		"lastActivityMs": lastActivity, "lastReadMs": lastReadFromUnread(boolToUnread(thread.MarkedAsUnread), lastActivity),
	})
	if thread.SlideMessages != nil {
		for _, edge := range thread.SlideMessages.Edges {
			ig.emitMessage(key, edge.Node)
		}
	}
}

func boolToUnread(unread bool) uint32 {
	if unread {
		return 1
	}
	return 0
}

func (ig *Instagram) emitMessage(threadKey string, msg *slidetypes.Message) {
	if msg == nil || msg.MessageID == "" {
		return
	}
	kind, url, preview, width, height := "text", "", "", 0, 0
	switch content := msg.Content.Content.(type) {
	case *slidetypes.MessageContentImage:
		if len(content.Attachments) > 0 && content.Attachments[0] != nil {
			a := content.Attachments[0]
			kind, url, preview, width, height = "image", a.AttachmentCDNURL, a.PreviewCDNURL, a.PreviewWidth, a.PreviewHeight
		}
	case *slidetypes.MessageContentVideo:
		if len(content.Videos) > 0 && content.Videos[0] != nil {
			a := content.Videos[0]
			kind, url, preview, width, height = "video", a.AttachmentCDNURL, a.PreviewCDNURL, a.PreviewWidth, a.PreviewHeight
		}
	case *slidetypes.MessageContentAudio:
		kind = "audio"
	}
	if kind == "text" && msg.TextBody == "" && msg.IGDSnippet == "" {
		kind = "other"
	}
	ig.emit(map[string]any{
		"type": "message", "threadKey": threadKey, "id": msg.MessageID, "otid": msg.OfflineThreadingID,
		"senderId": strconv.FormatInt(msg.SenderFBID, 10), "text": firstNonEmpty(msg.TextBody, msg.IGDSnippet),
		"kind": kind, "timestampMs": msg.TimestampMS.UnixMilli(),
	})
	if url != "" || preview != "" {
		ig.emit(map[string]any{
			"type": "attachment", "threadKey": threadKey, "messageId": msg.MessageID, "kind": kind,
			"url": firstNonEmpty(url, preview), "previewUrl": firstNonEmpty(preview, url), "width": width, "height": height,
		})
	}
}

func (ig *Instagram) emitState(state, err string) {
	ig.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (ig *Instagram) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && ig.listener != nil {
		ig.listener.OnEvent(string(data))
	}
}

// RequestOlder loads a page of a thread's messages: the newest ones when beforeMessageID is empty (the inbox sync
// carries none), otherwise those older than it. They arrive as message events, then a range event telling whether
// there's more.
func (ig *Instagram) RequestOlder(threadKey string, beforeMessageID string) error {
	rawID, ok := ig.igThreadIDs.Load(threadKey)
	if !ok {
		return errors.New("unknown thread")
	}
	igID := rawID.(string)
	ctx := context.Background()
	var page *slidetypes.SlideMessages
	if beforeMessageID == "" {
		resp, err := ig.client.GetThread(ctx, slidetypes.MakeGetThreadInfoRequest(igID))
		if err != nil {
			return fmt.Errorf("failed to load the thread: %w", err)
		}
		if thread := resp.ThreadInfo.AsIGDirectThread; thread != nil {
			page = thread.SlideMessages
		}
	} else {
		resp, err := ig.client.PaginateMessages(ctx, &slidetypes.PaginateMessagesRequest{
			OlderThanMessageID:      &beforeMessageID,
			ThreadID:                igID,
			FirstN:                  instagramPageSize,
			InitialMessagePageCount: instagramPageSize,
		})
		if err != nil {
			return fmt.Errorf("failed to load older messages: %w", err)
		}
		page = resp.ThreadInfo.AsIGDirectThread.Messages
	}
	hasMore := false
	if page != nil {
		for _, edge := range page.Edges {
			ig.emitMessage(threadKey, edge.Node)
		}
		hasMore = page.PageInfo.HasNextPage
	}
	ig.emit(map[string]any{"type": "range", "threadKey": threadKey, "hasMoreBefore": hasMore})
	return nil
}

const instagramPageSize = 20

// loadNewThread fetches a thread the inbox sync didn't have and emits it with its people and messages.
func (ig *Instagram) loadNewThread(threadKey string) {
	if _, loading := ig.threadsLoading.LoadOrStore(threadKey, true); loading {
		return
	}
	defer ig.threadsLoading.Delete(threadKey)
	fbid, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	ids, err := ig.client.FetchThreadID(ctx, fbid)
	if err != nil {
		ig.log.Err(err).Msg("Failed to look up a new Instagram thread")
		return
	}
	resp, err := ig.client.GetThread(ctx, slidetypes.MakeGetThreadInfoRequest(ids.ShortID))
	if err != nil || resp.ThreadInfo.AsIGDirectThread == nil {
		ig.log.Err(err).Msg("Failed to load a new Instagram thread")
		return
	}
	ig.emitThread(resp.ThreadInfo.AsIGDirectThread)
}
