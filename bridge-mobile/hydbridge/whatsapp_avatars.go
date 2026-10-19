package hydbridge

import (
	"context"
	"errors"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/types"
)

// Profile pictures are fetched one at a time (WhatsApp rate-limits these queries), once per chat per run.
// Their URLs are signed CDN links that expire after a while, so each run fetches them again.
const avatarFetchInterval = 400 * time.Millisecond

// queueAvatar asks for a chat's profile picture; it arrives later as an avatar event.
func (w *WhatsApp) queueAvatar(chatJID string) {
	if _, queued := w.avatarsQueued.LoadOrStore(chatJID, true); queued {
		return
	}
	select {
	case w.avatarQueue <- chatJID:
	default:
		// Queue full (a big history sync): try again when the chat shows up next time
		w.avatarsQueued.Delete(chatJID)
	}
}

// FetchAvatars queues the profile pictures of the given chats (comma-separated JIDs), e.g. the chats the app
// already knows when it connects.
func (w *WhatsApp) FetchAvatars(chatJIDs string) {
	for _, jid := range strings.Split(chatJIDs, ",") {
		if jid = strings.TrimSpace(jid); jid != "" {
			w.queueAvatar(jid)
		}
	}
}

func (w *WhatsApp) fetchAvatars() {
	for chatJID := range w.avatarQueue {
		client := w.client
		jid, err := types.ParseJID(chatJID)
		if client == nil || err != nil {
			w.avatarsQueued.Delete(chatJID)
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		info, err := client.GetProfilePictureInfo(ctx, jid, &whatsmeow.GetProfilePictureParams{Preview: true})
		cancel()
		switch {
		case err == nil && info != nil && info.URL != "":
			w.emit(map[string]any{"type": "avatar", "threadKey": chatJID, "url": info.URL})
		case err == nil, errors.Is(err, whatsmeow.ErrProfilePictureNotSet), errors.Is(err, whatsmeow.ErrProfilePictureUnauthorized):
			// No picture, or hidden from us: nothing to show
		default:
			// Offline or rate-limited: allow another attempt later
			w.avatarsQueued.Delete(chatJID)
		}
		time.Sleep(avatarFetchInterval)
	}
}
