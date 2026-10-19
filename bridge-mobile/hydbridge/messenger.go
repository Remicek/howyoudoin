// Package hydbridge runs Messenger (via mautrix-meta's messagix client) inside the app, without a server bridge.
// It is built with gomobile, so the API only uses types gomobile can bind: strings, int64, bool, errors and
// interfaces. Everything the app needs to know arrives as small JSON events on the Listener.
package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"

	"go.mau.fi/mautrix-meta/pkg/messagix"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/mautrix-meta/pkg/messagix/socket"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
)

// Listener receives JSON events: self, thread, contact, participant, message, state.
type Listener interface {
	OnEvent(json string)
}

// Messenger is one logged-in Messenger account.
type Messenger struct {
	client   *messagix.Client
	listener Listener
	log      zerolog.Logger

	lock   sync.Mutex
	cancel context.CancelFunc
	selfID int64

	// Contacts already sent to the app; others are looked up when their encrypted messages arrive
	knownContacts sync.Map
	lookups       sync.Map

	// End-to-end encrypted chats (WhatsApp protocol); see e2ee.go
	dataDir     string
	deviceStore *sqlstore.Container
	device      *store.Device
	e2ee        *whatsmeow.Client
}

// NewMessenger takes the messenger.com session cookies as a JSON object (name → value; xs, c_user and datr are
// required) and a private directory for the encrypted-chat keys (empty disables encrypted chats).
func NewMessenger(cookiesJSON string, dataDir string, listener Listener) (*Messenger, error) {
	values := map[cookies.MetaCookieName]string{}
	if err := json.Unmarshal([]byte(cookiesJSON), &values); err != nil {
		return nil, fmt.Errorf("invalid cookies: %w", err)
	}
	jar := &cookies.Cookies{Platform: types.Messenger}
	jar.UpdateValues(values)
	if missing := jar.GetMissingCookieNames(); len(missing) > 0 {
		return nil, fmt.Errorf("missing cookies: %v", missing)
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "messagix").Logger().Level(zerolog.InfoLevel)
	m := &Messenger{listener: listener, log: log, dataDir: dataDir}
	m.client = messagix.NewClient(jar, log, &messagix.Config{})
	m.client.SetEventHandler(m.handleEvent)
	return m, nil
}

// Connect loads the inbox (emitting its threads, contacts and recent messages) and opens the realtime socket.
// It returns once the inbox is loaded; live updates keep arriving on the Listener until Disconnect.
func (m *Messenger) Connect() error {
	m.lock.Lock()
	if m.cancel != nil {
		m.lock.Unlock()
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(context.Background())
	ctx = m.log.WithContext(ctx)
	m.cancel = cancel
	m.lock.Unlock()

	user, initial, err := m.client.LoadMessagesPage(ctx)
	if err != nil {
		m.resetCancel()
		return fmt.Errorf("failed to load inbox: %w", err)
	}
	m.selfID = user.GetFBID()
	m.emit(map[string]any{"type": "self", "id": strconv.FormatInt(m.selfID, 10), "name": user.GetName()})
	m.emitTable(initial)
	if err = m.client.Connect(ctx); err != nil {
		m.resetCancel()
		return fmt.Errorf("failed to connect: %w", err)
	}
	go func() {
		if err := m.connectE2EE(ctx); err != nil {
			m.log.Err(err).Msg("Encrypted chats unavailable")
			m.emitState("encrypted_error", err.Error())
		}
	}()
	return nil
}

func (m *Messenger) resetCancel() {
	m.lock.Lock()
	defer m.lock.Unlock()
	if m.cancel != nil {
		m.cancel()
		m.cancel = nil
	}
}

// Disconnect closes the socket; Connect can be called again afterwards.
func (m *Messenger) Disconnect() {
	if m.e2ee != nil {
		m.e2ee.Disconnect()
		m.e2ee = nil
	}
	m.client.Disconnect()
	m.resetCancel()
	m.emitState("disconnected", "")
}

// SelfID is the logged-in user's Facebook ID (after Connect).
func (m *Messenger) SelfID() string {
	return strconv.FormatInt(m.selfID, 10)
}

// SendText sends a text message to a (non-encrypted) thread and returns its offline threading ID, which the
// echoed message event carries as "otid" so the app can match its local echo.
func (m *Messenger) SendText(threadKey string, text string) (string, error) {
	key, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return "", fmt.Errorf("invalid thread key: %w", err)
	}
	otid := methods.GenerateEpochID()
	task := &socket.SendMessageTask{
		ThreadId:         key,
		Otid:             otid,
		Source:           table.MESSENGER_INBOX_IN_THREAD,
		InitiatingSource: table.FACEBOOK_INBOX,
		SendType:         table.TEXT,
		SyncGroup:        1,
		Text:             text,
	}
	resp, err := m.client.ExecuteTasks(context.Background(), task, &socket.ThreadMarkReadTask{
		ThreadId:            key,
		LastReadWatermarkTs: time.Now().UnixMilli(),
		SyncGroup:           1,
	})
	if err != nil {
		return "", err
	}
	m.emitTable(resp)
	return strconv.FormatInt(otid, 10), nil
}

// RequestOlder asks for messages before the given one (the oldest the app has). They arrive as message events,
// followed by a range event telling whether even older history exists.
func (m *Messenger) RequestOlder(threadKey string, beforeTimestampMs int64, beforeMessageID string) error {
	key, err := strconv.ParseInt(threadKey, 10, 64)
	if err != nil {
		return fmt.Errorf("invalid thread key: %w", err)
	}
	resp, err := m.client.ExecuteTasks(context.Background(), &socket.FetchMessagesTask{
		ThreadKey:            key,
		Direction:            0,
		ReferenceTimestampMs: beforeTimestampMs,
		ReferenceMessageId:   beforeMessageID,
		SyncGroup:            1,
		Cursor:               m.client.GetCursor(1),
	})
	if err != nil {
		return err
	}
	m.emitTable(resp)
	return nil
}

func (m *Messenger) handleEvent(_ context.Context, evt any) {
	switch evt := evt.(type) {
	case *table.LSTable:
		m.emitTable(evt)
	case *messagix.ConnectedEvent, *messagix.ReconnectedEvent:
		m.emitState("connected", "")
	case *messagix.TransientDisconnectEvent:
		m.emitState("reconnecting", errString(evt.Err))
	case *messagix.PermanentErrorEvent:
		m.emitState("error", errString(evt.Err))
	}
}

// emitTable turns the parts of a lightspeed table the app understands into events.
func (m *Messenger) emitTable(tbl *table.LSTable) {
	if tbl == nil {
		return
	}
	for _, c := range tbl.LSVerifyContactRowExists {
		m.emitContact(c.ContactId, c.Name, c.FirstName, c.ProfilePictureUrl)
	}
	for _, c := range tbl.LSDeleteThenInsertContact {
		m.emitContact(c.Id, c.Name, c.FirstName, c.ProfilePictureUrl)
	}
	for _, t := range tbl.LSDeleteThenInsertThread {
		m.emitThread(t.ThreadKey, t.ThreadName, t.ThreadType, t.LastActivityTimestampMs, t.LastReadWatermarkTimestampMs, t.Snippet, t.SnippetSenderContactId, t.FolderName, t.ThreadPictureUrl)
	}
	for _, t := range tbl.LSUpdateOrInsertThread {
		m.emitThread(t.ThreadKey, t.ThreadName, t.ThreadType, t.LastActivityTimestampMs, t.LastReadWatermarkTimestampMs, t.Snippet, t.SnippetSenderContactId, t.FolderName, t.ThreadPictureUrl)
	}
	// Facebook thread ↔ encrypted chat: the app shows the encrypted one and hides the old Facebook thread
	for _, link := range tbl.LSVerifyHybridThreadExists {
		if !link.IsGroupThread {
			// A 1:1 encrypted chat is keyed by the other person's ID; look them up so it isn't nameless
			m.ensureContact(id(link.ThreadJID))
		}
		m.emit(map[string]any{"type": "link", "fbThreadKey": id(link.ThreadKey), "threadKey": id(link.ThreadJID),
			"isGroup": link.IsGroupThread, "lastActivityMs": link.LastActivityTimestampMS})
	}
	for _, link := range tbl.LSCreateOpenToE2EEThreadLink {
		m.ensureContact(id(link.ThreadJID))
		m.emit(map[string]any{"type": "link", "fbThreadKey": id(link.ThreadKey), "threadKey": id(link.ThreadJID),
			"isGroup": false, "lastActivityMs": link.LastActivityTimestampMS})
	}
	for _, p := range tbl.LSAddParticipantIdToGroupThread {
		m.emit(map[string]any{"type": "participant", "threadKey": id(p.ThreadKey), "contactId": id(p.ContactId)})
	}
	for _, msg := range tbl.LSInsertMessage {
		m.emitMessage(msg.ThreadKey, msg.MessageId, msg.OfflineThreadingId, msg.SenderId, msg.Text, msg.TimestampMs, msg.IsUnsent)
	}
	for _, msg := range tbl.LSUpsertMessage {
		m.emitMessage(msg.ThreadKey, msg.MessageId, msg.OfflineThreadingId, msg.SenderId, msg.Text, msg.TimestampMs, msg.IsUnsent)
	}
	for _, e := range tbl.LSEditMessage {
		// No thread key here: the app finds the message by its ID
		m.emit(map[string]any{"type": "edit", "messageId": e.MessageID, "text": e.Text})
	}
	m.emitInteractions(tbl)
	for _, r := range tbl.LSInsertNewMessageRange {
		m.emit(map[string]any{
			"type": "range", "threadKey": id(r.ThreadKey), "minTimestampMs": r.MinTimestampMs,
			"minMessageId": r.MinMessageId, "hasMoreBefore": r.HasMoreBefore,
		})
	}
	for _, r := range tbl.LSUpdateExistingMessageRange {
		// Same rule as mautrix-meta: (true, false) clears "has more after", anything else "has more before"
		if !(r.UnknownBool2 && !r.UnknownBool3) {
			m.emit(map[string]any{"type": "range", "threadKey": id(r.ThreadKey), "minTimestampMs": r.TimestampMS, "hasMoreBefore": false})
		}
	}
}

func (m *Messenger) emitContact(contactID int64, name, firstName, avatar string) {
	if contactID == 0 {
		return
	}
	m.knownContacts.Store(contactID, true)
	m.emit(map[string]any{"type": "contact", "id": id(contactID), "name": name, "firstName": firstName, "avatarUrl": avatar})
}

func (m *Messenger) emitThread(key int64, name string, threadType table.ThreadType, lastActivity, lastRead int64, snippet string, snippetSender int64, folder, pictureURL string) {
	if key == 0 {
		return
	}
	m.emit(map[string]any{
		"type":           "thread",
		"key":            id(key),
		"name":           name,
		"isGroup":        !threadType.IsOneToOne(),
		"isEncrypted":    threadType.IsWhatsApp(),
		"lastActivityMs": lastActivity,
		"lastReadMs":     lastRead,
		"snippet":        snippet,
		"snippetSender":  id(snippetSender),
		"folder":         folder,
		"pictureUrl":     pictureURL,
	})
}

func (m *Messenger) emitMessage(threadKey int64, messageID, otid string, sender int64, text string, timestamp int64, unsent bool) {
	if messageID == "" {
		return
	}
	m.emit(map[string]any{
		"type":        "message",
		"threadKey":   id(threadKey),
		"id":          messageID,
		"otid":        otid,
		"senderId":    id(sender),
		"text":        text,
		"timestampMs": timestamp,
		"isUnsent":    unsent,
	})
}

func (m *Messenger) emitState(state, err string) {
	m.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (m *Messenger) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && m.listener != nil {
		m.listener.OnEvent(string(data))
	}
}

func id(v int64) string {
	if v == 0 {
		return ""
	}
	return strconv.FormatInt(v, 10)
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

// ensureContact looks up a contact the app doesn't know yet (e.g. the other person of an encrypted chat that
// started after connecting), so the chat gets a name.
const (
	contactLookupAttempts   = 5
	contactLookupRetryDelay = 3 * time.Second
)

func (m *Messenger) ensureContact(idStr string) {
	contactID, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil || contactID == 0 || contactID == m.selfID {
		return
	} else if _, known := m.knownContacts.Load(contactID); known {
		return
	} else if _, pending := m.lookups.LoadOrStore(contactID, true); pending {
		return
	}
	go func() {
		defer m.lookups.Delete(contactID)
		// The initial sync can ask before the socket is open; try again a few times
		for attempt := 1; ; attempt++ {
			resp, err := m.client.ExecuteTasks(context.Background(), &socket.GetContactsFullTask{ContactID: contactID})
			if err == nil {
				m.emitTable(resp)
				return
			} else if attempt == contactLookupAttempts {
				m.log.Err(err).Int64("contact_id", contactID).Msg("Failed to look up contact")
				return
			}
			time.Sleep(time.Duration(attempt) * contactLookupRetryDelay)
		}
	}()
}

// FetchAvatars looks up these contacts again (comma-separated IDs), one at a time, for fresh profile picture
// links; Messenger's CDN links expire after a few days.
func (m *Messenger) FetchAvatars(contactIDs string) {
	ids := strings.Split(contactIDs, ",")
	go func() {
		for _, idStr := range ids {
			contactID, err := strconv.ParseInt(strings.TrimSpace(idStr), 10, 64)
			if err != nil || contactID == 0 || contactID == m.selfID {
				continue
			}
			m.knownContacts.Delete(contactID)
			m.ensureContact(idStr)
			time.Sleep(avatarRefreshInterval)
		}
	}()
}

const avatarRefreshInterval = 300 * time.Millisecond
