package hydbridge

import (
	"context"
	"strings"

	"go.mau.fi/mautrix-twitter/pkg/twittermeow"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow/data/payload"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow/data/response"
	"go.mau.fi/mautrix-twitter/pkg/twittermeow/data/types"
)

// Old-style X direct messages: chats with accounts that haven't set up X Chat (and message requests from them) are
// still plain DMs on X's REST API. They're loaded after the X Chat inbox, kept up to date by polling, and sent to
// through the REST API. Their keys are written like X Chat's ("a:b"), so a chat both have shows once.

// twitterChatKey writes a REST conversation ID ("a-b", or "g…" for a group) as an X Chat key.
func twitterChatKey(restID string) string {
	if strings.HasPrefix(restID, "g") {
		return restID
	}
	return strings.ReplaceAll(restID, "-", ":")
}

// twitterRESTID is the REST conversation ID of a chat key.
func twitterRESTID(key string) string {
	if strings.HasPrefix(key, "g") {
		return key
	}
	return strings.ReplaceAll(key, ":", "-")
}

// syncLegacyInbox loads the old-style chats X Chat didn't return, then follows them by polling.
func (t *Twitter) syncLegacyInbox(ctx context.Context, client *twittermeow.Client) {
	query := payload.DMRequestQuery{}.Default()
	// Message requests too, as the X app lists them
	query.FilterLowQuality = false
	resp, err := client.GetInitialInboxState(ctx, &query)
	if err != nil {
		t.log.Error().Err(err).Msg("Failed to load the old-style X inbox")
	} else if inbox := resp.InboxInitialState; inbox != nil {
		if inbox.Cursor != "" && client.GetSession().PollingCursor == "" {
			client.GetSession().PollingCursor = inbox.Cursor
		}
		t.emitLegacyUsers(inbox)
		messages := inbox.SortedMessages(ctx)
		for _, conv := range inbox.SortedConversations() {
			key := twitterChatKey(conv.ConversationID)
			if _, isXChat := t.xchatChats.Load(key); isXChat {
				continue
			}
			t.legacyChats.Store(key, true)
			t.emitLegacyConversation(inbox, conv, key)
			for _, m := range messages[conv.ConversationID] {
				m.ConversationID = key
				t.emitMessage(m)
			}
			t.emit(map[string]any{"type": "range", "threadKey": key, "hasMoreBefore": false})
		}
	}
	if ctx.Err() == nil {
		client.StartPolling(ctx)
	}
}

func (t *Twitter) emitLegacyUsers(inbox *response.TwitterInboxData) {
	for _, u := range inbox.Users {
		if u == nil || u.IDStr == "" || u.IDStr == t.selfID {
			continue
		}
		name := firstNonEmpty(u.Name, "@"+u.ScreenName)
		if _, sent := t.contacts.LoadOrStore(u.IDStr, name); sent {
			continue
		}
		t.emit(map[string]any{"type": "contact", "id": u.IDStr, "name": name,
			"firstName": strings.SplitN(name, " ", 2)[0], "avatarUrl": u.ProfileImageURLHTTPS})
	}
}

func (t *Twitter) emitLegacyConversation(inbox *response.TwitterInboxData, conv *types.Conversation, key string) {
	var names []string
	var avatar string
	for _, p := range conv.Participants {
		if p.UserID == "" || p.UserID == t.selfID {
			continue
		}
		t.emit(map[string]any{"type": "participant", "threadKey": key, "contactId": p.UserID})
		if u := inbox.GetUserByID(p.UserID); u != nil {
			names = append(names, firstNonEmpty(u.Name, "@"+u.ScreenName))
			avatar = u.ProfileImageURLHTTPS
		}
	}
	isGroup := conv.Type == types.ConversationTypeGroupDM
	if !isGroup && avatar != "" {
		t.emit(map[string]any{"type": "avatar", "threadKey": key, "url": avatar})
	}
	t.emit(map[string]any{"type": "thread", "key": key, "name": firstNonEmpty(conv.Name, strings.Join(names, ", ")),
		"isGroup": isGroup, "isEncrypted": false})
}

// handleLegacyEvent takes a polled REST event: its chat key is rewritten like X Chat's, and events of chats X Chat
// already delivers are left to it.
func (t *Twitter) handleLegacyEvent(evt types.TwitterEvent, inbox *response.TwitterInboxData) bool {
	if inbox != nil {
		t.emitLegacyUsers(inbox)
		for _, conv := range inbox.Conversations {
			key := twitterChatKey(conv.ConversationID)
			if _, isXChat := t.xchatChats.Load(key); isXChat {
				continue
			}
			if _, known := t.legacyChats.LoadOrStore(key, true); !known {
				t.emitLegacyConversation(inbox, conv, key)
			}
		}
	}
	switch e := evt.(type) {
	case *types.Message:
		e.ConversationID = twitterChatKey(e.ConversationID)
		if !t.isLegacyChat(e.ConversationID) {
			return true
		}
	case *types.MessageDelete:
		e.ConversationID = twitterChatKey(e.ConversationID)
		if !t.isLegacyChat(e.ConversationID) {
			return true
		}
	case *types.MessageReactionCreate:
		e.ConversationID = twitterChatKey(e.ConversationID)
		if !t.isLegacyChat(e.ConversationID) {
			return true
		}
	case *types.MessageReactionDelete:
		e.ConversationID = twitterChatKey(e.ConversationID)
		if !t.isLegacyChat(e.ConversationID) {
			return true
		}
	case *types.ConversationRead:
		e.ConversationID = twitterChatKey(e.ConversationID)
		if !t.isLegacyChat(e.ConversationID) {
			return true
		}
	default:
		return true
	}
	return t.handleEvent(context.Background(), evt)
}

// isLegacyChat: an old-style chat (not one X Chat has).
func (t *Twitter) isLegacyChat(key string) bool {
	if _, isXChat := t.xchatChats.Load(key); isXChat {
		return false
	}
	_, ok := t.legacyChats.Load(key)
	return ok
}

// sendLegacy sends text to an old-style chat through the REST API.
func (t *Twitter) sendLegacy(ctx context.Context, client *twittermeow.Client, key, text, replyToID string) (string, error) {
	requestID := newTwitterMessageID()
	_, err := client.SendDirectMessage(ctx, &payload.SendDirectMessagePayload{
		ConversationID: twitterRESTID(key), RequestID: requestID, Text: text, ReplyToDMID: replyToID,
		CardsPlatform: "Web-12", IncludeCards: 1,
	})
	if err != nil {
		return "", err
	}
	return requestID, nil
}
