package hydbridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/util/dbutil"
	"google.golang.org/protobuf/proto"

	"go.mau.fi/mautrix-signal/pkg/libsignalgo"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/events"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/store"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/types"
)

// Signal is a Signal account linked as a secondary device (mautrix-signal's signalmeow) running in the app. It
// emits the same JSON events as the other networks. Thread keys are the other person's ACI (a UUID) or a group's
// ID (base64), contact IDs are ACIs, and message IDs are "<author ACI>:<sent timestamp>" (how Signal refers to
// messages in reactions, edits and deletions).
//
// Linking emits {"type":"pairing","qr":...} events with the code to scan in Signal on the phone; recent history
// can be transferred from the phone during linking.
type Signal struct {
	// Timestamp of a reply we sent → its quote, repeated in edits
	sentQuotes sync.Map
	// PNI of a chat started by phone number (its key is "PNI:<pni>") → true; once the person answers from their
	// account, the chat moves to its ACI key and the PNI maps to that
	pniChats sync.Map
	dataDir  string
	listener Listener
	log      zerolog.Logger

	db        *dbutil.Database
	container *store.Container

	lock       sync.Mutex
	client     *signalmeow.Client
	cancel     context.CancelFunc
	linkCancel context.CancelFunc

	// Sent message timestamp → thread key, so read receipts (which only name the reader and timestamps) find
	// their chat
	sentThreads sync.Map
	// Contacts and groups whose names were looked up
	named sync.Map
	// Message ID → our own reaction on it (removing a reaction has to name the emoji)
	ownReactions sync.Map
}

// NewSignal opens (or creates) the Signal store in dataDir.
func NewSignal(dataDir string, listener Listener) (*Signal, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "signal").Logger().Level(zerolog.InfoLevel)
	address := sqliteAddress(filepath.Join(dataDir, "signal.db"), log)
	db, err := dbutil.NewWithDialect(address, "sqlite3")
	if err != nil {
		return nil, fmt.Errorf("failed to open the Signal store: %w", err)
	}
	container := store.NewStore(db, dbutil.ZeroLogger(log))
	if err = container.Upgrade(context.Background()); err != nil {
		return nil, fmt.Errorf("failed to prepare the Signal store: %w", err)
	}
	return &Signal{dataDir: dataDir, listener: listener, log: log, db: db, container: container}, nil
}

func (s *Signal) device(ctx context.Context) *store.Device {
	devices, err := s.container.GetAllDevices(ctx)
	if err != nil || len(devices) == 0 {
		return nil
	}
	return devices[0]
}

// IsLinked tells whether this phone was linked to a Signal account.
func (s *Signal) IsLinked() bool {
	return s.device(context.Background()) != nil
}

// Link shows QR codes (pairing events) until Signal on the phone scans one, then connects and loads the chats
// and the history the phone transfers. Codes are refreshed for about ten minutes.
func (s *Signal) Link() error {
	s.lock.Lock()
	if s.linkCancel != nil {
		s.linkCancel()
	}
	ctx, cancel := context.WithTimeout(s.log.WithContext(context.Background()), 10*time.Minute)
	s.linkCancel = cancel
	s.lock.Unlock()
	go s.link(ctx, cancel)
	return nil
}

func (s *Signal) link(ctx context.Context, cancel context.CancelFunc) {
	defer cancel()
	for ctx.Err() == nil {
		for resp := range signalmeow.PerformProvisioning(ctx, s.container, "HowYouDoin", true) {
			switch resp.State {
			case signalmeow.StateProvisioningURLReceived:
				s.emit(map[string]any{"type": "pairing", "qr": resp.ProvisioningURL})
			case signalmeow.StateProvisioningDataReceived:
				s.emitState("linked", "")
				if err := s.Connect(); err != nil {
					s.emitState("pairing_failed", err.Error())
				}
				return
			case signalmeow.StateProvisioningError:
				var linkErr signalmeow.DeviceLinkError
				if errors.As(resp.Err, &linkErr) {
					s.emitState("pairing_failed", linkErrorMessage(linkErr))
					return
				}
				// The code expired unscanned (or the connection dropped): show a fresh one
			}
		}
	}
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		s.emitState("pairing_failed", "timeout")
	}
}

func linkErrorMessage(err signalmeow.DeviceLinkError) string {
	switch err.StatusCode {
	case 409:
		return "Signal needs a newer version of this app to link"
	case 411:
		return "your Signal account already has the most linked devices; remove one in Signal and try again"
	case 403:
		return "the code was invalid or already used"
	case 429:
		return "too many attempts; wait a few minutes"
	}
	return err.Error()
}

// CancelLink stops showing linking codes.
func (s *Signal) CancelLink() {
	s.lock.Lock()
	defer s.lock.Unlock()
	if s.linkCancel != nil {
		s.linkCancel()
		s.linkCancel = nil
	}
}

// Connect starts receiving messages with the linked device.
func (s *Signal) Connect() error {
	s.lock.Lock()
	defer s.lock.Unlock()
	if s.cancel != nil {
		return nil
	}
	ctx, cancel := context.WithCancel(s.log.WithContext(context.Background()))
	device := s.device(ctx)
	if device == nil {
		cancel()
		return errors.New("not linked")
	}
	s.client = signalmeow.NewClient(device, s.log, s.handleEvent)
	s.cancel = cancel
	selfID := device.ACI.String()
	s.emit(map[string]any{"type": "self", "id": selfID, "name": ""})
	go s.lookUpContact(ctx, device.ACI)
	go s.run(ctx)
	return nil
}

func (s *Signal) run(ctx context.Context) {
	for retry := 0; ctx.Err() == nil; retry++ {
		statuses, err := s.client.StartReceiveLoops(ctx)
		if err != nil {
			s.emitState("reconnecting", err.Error())
			select {
			case <-time.After(time.Duration(min(2<<retry, 120)) * time.Second):
				continue
			case <-ctx.Done():
				return
			}
		}
		retry = 0
		go s.syncTransferredChats(ctx)
		for status := range statuses {
			switch status.Event {
			case signalmeow.SignalConnectionEventConnected:
				s.emitState("connected", "")
			case signalmeow.SignalConnectionEventDisconnected:
				s.emitState("reconnecting", errString(status.Err))
			case signalmeow.SignalConnectionEventLoggedOut:
				s.loggedOut()
				return
			}
		}
		return
	}
}

// Disconnect stops receiving; Connect can be called again.
func (s *Signal) Disconnect() {
	s.lock.Lock()
	cancel, client := s.cancel, s.client
	s.cancel = nil
	s.lock.Unlock()
	if cancel != nil {
		cancel()
	}
	if client != nil {
		_ = client.StopReceiveLoops()
	}
	s.emitState("disconnected", "")
}

// Logout unlinks this device from the Signal account and deletes its keys.
func (s *Signal) Logout() error {
	s.CancelLink()
	s.lock.Lock()
	client := s.client
	s.lock.Unlock()
	if client != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		_ = client.Unlink(ctx)
		_ = client.ClearKeysAndDisconnect(ctx)
		cancel()
	}
	s.Disconnect()
	return nil
}

func (s *Signal) loggedOut() {
	s.lock.Lock()
	client := s.client
	s.cancel = nil
	s.lock.Unlock()
	if client != nil {
		_ = client.ClearKeysAndDisconnect(context.Background())
	}
	s.emitState("logged_out", "")
}

// --- chats ---

// syncTransferredChats loads the chats and recent messages the phone sent while linking (once).
func (s *Signal) syncTransferredChats(ctx context.Context) {
	if s.client.Store.EphemeralBackupKey == nil {
		return
	}
	meta, err := s.client.WaitForTransfer(ctx)
	if err == nil && meta.Error == "" {
		err = s.client.FetchAndProcessTransfer(ctx, meta)
	}
	if err != nil {
		s.log.Warn().Err(err).Msg("Couldn't load the history transferred from the phone")
	}
	chats, err := s.client.Store.BackupStore.GetBackupChats(ctx)
	if err != nil {
		return
	}
	for _, chat := range chats {
		key, isGroup := s.backupChatKey(ctx, chat)
		if key == "" || chat.TotalMessages == 0 {
			continue
		}
		lastActivity := int64(chat.LatestMessageID)
		s.emit(map[string]any{"type": "thread", "key": key, "name": s.chatName(ctx, key, isGroup), "isGroup": isGroup,
			"isEncrypted": true, "lastActivityMs": lastActivity, "lastReadMs": lastActivity})
		if !isGroup {
			s.emit(map[string]any{"type": "participant", "threadKey": key, "contactId": key})
		}
		s.emitBackupItems(ctx, chat, key, time.Now(), signalHistoryPage)
	}
}

const signalHistoryPage = 30

func (s *Signal) backupChatKey(ctx context.Context, chat *store.BackupChat) (string, bool) {
	recipient, err := s.client.Store.BackupStore.GetBackupRecipient(ctx, chat.RecipientId)
	if err != nil || recipient == nil {
		return "", false
	}
	if contact := recipient.GetContact(); contact != nil && len(contact.Aci) == 16 {
		aci := uuid.UUID(contact.Aci)
		if name := strings.TrimSpace(contact.GetProfileGivenName() + " " + contact.GetProfileFamilyName()); name != "" {
			s.emit(map[string]any{"type": "contact", "id": aci.String(), "name": name, "firstName": contact.GetProfileGivenName()})
		}
		return aci.String(), false
	}
	if recipient.GetSelf() != nil {
		return s.client.Store.ACI.String(), false
	}
	if group := recipient.GetGroup(); group != nil && len(group.MasterKey) == libsignalgo.GroupMasterKeyLength {
		rawID, err := libsignalgo.GroupMasterKey(group.MasterKey).GroupIdentifier()
		if err != nil {
			return "", false
		}
		return base64.StdEncoding.EncodeToString(rawID[:]), true
	}
	return "", false
}

// emitBackupItems emits up to limit transferred messages older than before, then a range event.
func (s *Signal) emitBackupItems(ctx context.Context, chat *store.BackupChat, key string, before time.Time, limit int) {
	items, err := s.client.Store.BackupStore.GetBackupChatItems(ctx, chat.Id, before, false, limit)
	if err != nil {
		return
	}
	for _, item := range items {
		author := ""
		if recipient, err := s.client.Store.BackupStore.GetBackupRecipient(ctx, item.AuthorId); err == nil && recipient != nil {
			if contact := recipient.GetContact(); contact != nil && len(contact.Aci) == 16 {
				author = uuid.UUID(contact.Aci).String()
			} else if recipient.GetSelf() != nil {
				author = s.client.Store.ACI.String()
			}
		}
		if author == "" {
			continue
		}
		text, kind := "", "text"
		switch {
		case item.GetStandardMessage() != nil:
			msg := item.GetStandardMessage()
			text = msg.GetText().GetBody()
			if len(msg.Attachments) > 0 {
				// Transferred attachments can't be downloaded from here; say what was there
				kind = "other"
				text = strings.TrimSpace("📎 Attachment " + text)
			}
		case item.GetStickerMessage() != nil:
			text = firstNonEmpty(item.GetStickerMessage().GetSticker().GetEmoji(), "Sticker")
		case item.GetRemoteDeletedMessage() != nil:
			s.emit(map[string]any{"type": "message", "threadKey": key, "id": author + ":" + strconv.FormatUint(item.DateSent, 10),
				"senderId": author, "text": "", "kind": "text", "timestampMs": int64(item.DateSent), "isUnsent": true, "isEncrypted": true})
			continue
		default:
			continue
		}
		s.emit(map[string]any{"type": "message", "threadKey": key, "id": author + ":" + strconv.FormatUint(item.DateSent, 10),
			"senderId": author, "text": text, "kind": kind, "timestampMs": int64(item.DateSent), "isEncrypted": true})
	}
	s.emit(map[string]any{"type": "range", "threadKey": key, "hasMoreBefore": len(items) == limit})
}

// RequestOlder emits transferred messages older than beforeTimestampMs (Signal keeps no history on its servers,
// so there's nothing beyond what the phone transferred while linking).
func (s *Signal) RequestOlder(threadKey string, beforeTimestampMs int64) error {
	client := s.currentClient()
	if client == nil {
		return errors.New("not connected")
	}
	ctx := context.Background()
	var chat *store.BackupChat
	var err error
	if isSignalGroup(threadKey) {
		chat, err = client.Store.BackupStore.GetBackupChatByGroupID(ctx, types.GroupIdentifier(threadKey))
	} else if aci, parseErr := uuid.Parse(threadKey); parseErr == nil {
		chat, err = client.Store.BackupStore.GetBackupChatByUserID(ctx, libsignalgo.NewACIServiceID(aci))
	}
	if err != nil || chat == nil {
		s.emit(map[string]any{"type": "range", "threadKey": threadKey, "hasMoreBefore": false})
		return nil
	}
	before := time.Now()
	if beforeTimestampMs > 0 && beforeTimestampMs < before.UnixMilli() {
		before = time.UnixMilli(beforeTimestampMs)
	}
	s.emitBackupItems(ctx, chat, threadKey, before, signalHistoryPage)
	return nil
}

func isSignalGroup(threadKey string) bool {
	_, err := uuid.Parse(threadKey)
	return err != nil
}

func (s *Signal) currentClient() *signalmeow.Client {
	s.lock.Lock()
	defer s.lock.Unlock()
	return s.client
}

// chatName looks up a chat's name (cached per run): the contact's or the group's.
func (s *Signal) chatName(ctx context.Context, key string, isGroup bool) string {
	if name, ok := s.named.Load(key); ok {
		return name.(string)
	}
	name := ""
	if isGroup {
		if group, _, err := s.client.RetrieveGroupByID(ctx, types.GroupIdentifier(key), 0); err == nil && group != nil {
			name = group.Title
		}
	} else if aci, err := uuid.Parse(key); err == nil {
		name = s.lookUpContact(ctx, aci)
	}
	if name != "" {
		s.named.Store(key, name)
	}
	return name
}

// lookUpContact emits a contact's name (address book name, nickname or profile name) and returns it.
func (s *Signal) lookUpContact(ctx context.Context, aci uuid.UUID) string {
	client := s.currentClient()
	if client == nil {
		return ""
	}
	recipient, err := client.ContactByACI(ctx, aci)
	if err != nil || recipient == nil {
		return ""
	}
	name := firstNonEmpty(recipient.ContactName, recipient.Nickname, recipient.Profile.Name, recipient.E164)
	if name != "" {
		first := strings.Fields(name)[0]
		s.emit(map[string]any{"type": "contact", "id": aci.String(), "name": name, "firstName": first})
		if aci == client.Store.ACI {
			s.emit(map[string]any{"type": "self", "id": aci.String(), "name": name})
		}
	}
	return name
}

// ensureNamed looks a chat's and a sender's names up the first time they're seen.
func (s *Signal) ensureNamed(key string, isGroup bool, sender uuid.UUID, lastActivity int64, unread bool) {
	ctx := context.Background()
	if _, known := s.named.Load(sender.String()); !known && sender != uuid.Nil {
		if name := s.lookUpContact(ctx, sender); name != "" {
			s.named.Store(sender.String(), name)
		}
	}
	lastRead := lastActivity
	if unread {
		lastRead = 0
	}
	s.emit(map[string]any{"type": "thread", "key": key, "name": s.chatName(ctx, key, isGroup), "isGroup": isGroup,
		"isEncrypted": true, "lastActivityMs": lastActivity, "lastReadMs": lastRead})
	if !isGroup {
		s.emit(map[string]any{"type": "participant", "threadKey": key, "contactId": key})
	}
}

// --- incoming ---

func (s *Signal) handleEvent(rawEvt events.SignalEvent) bool {
	switch evt := rawEvt.(type) {
	case *events.ChatEvent:
		s.handleChatEvent(evt)
	case *events.Receipt:
		if evt.Content.GetType() != signalpb.ReceiptMessage_READ {
			break
		}
		var newest uint64
		key := ""
		for _, ts := range evt.Content.GetTimestamp() {
			if thread, ok := s.sentThreads.Load(ts); ok && ts > newest {
				newest, key = ts, thread.(string)
			}
		}
		if key == "" {
			key = evt.Sender.String()
			for _, ts := range evt.Content.GetTimestamp() {
				newest = max(newest, ts)
			}
		}
		if newest > 0 {
			s.emit(map[string]any{"type": "read", "threadKey": key, "contactId": evt.Sender.String(), "watermarkMs": int64(newest)})
		}
	case *events.ContactList:
		for _, contact := range evt.Contacts {
			if name := firstNonEmpty(contact.ContactName, contact.Nickname, contact.Profile.Name); name != "" && contact.ACI != uuid.Nil {
				s.named.Store(contact.ACI.String(), name)
				s.emit(map[string]any{"type": "contact", "id": contact.ACI.String(), "name": name, "firstName": strings.Fields(name)[0]})
			}
		}
	case *events.LoggedOut:
		go s.loggedOut()
	case *events.DecryptionError:
		key := evt.Sender.String()
		s.emit(map[string]any{"type": "message", "threadKey": key, "id": "decrypterr|" + key + ":" + strconv.FormatUint(evt.Timestamp, 10),
			"senderId": key, "text": "A message couldn't be decrypted. Check Signal on your phone.", "kind": "other",
			"timestampMs": int64(evt.Timestamp), "isEncrypted": true})
	}
	return true
}

func (s *Signal) handleChatEvent(evt *events.ChatEvent) {
	key := evt.Info.ChatID
	isGroup := isSignalGroup(key)
	if !isGroup {
		s.movePNIChat(evt.Info.Sender)
	}
	sender := evt.Info.Sender.String()
	switch content := evt.Event.(type) {
	case *signalpb.DataMessage:
		ts := content.GetTimestamp()
		switch {
		case content.Reaction != nil:
			target, _ := signalmeow.ParseStringOrBinaryUUID(content.Reaction.GetTargetAuthorAci(), content.Reaction.GetTargetAuthorAciBinary())
			emoji := content.Reaction.GetEmoji()
			if content.Reaction.GetRemove() {
				emoji = ""
			}
			targetID := target.String() + ":" + strconv.FormatUint(content.Reaction.GetTargetSentTimestamp(), 10)
			if evt.Info.Sender == s.client.Store.ACI {
				s.rememberOwnReaction(targetID, emoji)
			}
			s.emit(map[string]any{"type": "reaction", "threadKey": key, "senderId": sender, "emoji": emoji, "targetId": targetID})
		case content.Delete != nil:
			s.emit(map[string]any{"type": "unsend", "threadKey": key,
				"messageId": sender + ":" + strconv.FormatUint(content.Delete.GetTargetSentTimestamp(), 10)})
		case content.Body != nil || len(content.Attachments) > 0 || content.Sticker != nil || content.Contact != nil:
			isOwn := evt.Info.Sender == s.client.Store.ACI
			s.ensureNamed(key, isGroup, evt.Info.Sender, int64(ts), !isOwn)
			if isOwn {
				s.sentThreads.Store(ts, key)
			}
			s.emitDataMessage(key, sender, content)
		}
	case *signalpb.EditMessage:
		s.emit(map[string]any{"type": "edit", "threadKey": key, "text": content.GetDataMessage().GetBody(),
			"messageId": sender + ":" + strconv.FormatUint(content.GetTargetSentTimestamp(), 10)})
	case *signalpb.TypingMessage:
		if evt.Info.Sender != s.client.Store.ACI {
			s.emit(map[string]any{"type": "typing", "threadKey": key, "senderId": sender,
				"isTyping": content.GetAction() == signalpb.TypingMessage_STARTED})
		}
	}
}

func (s *Signal) emitDataMessage(key, sender string, msg *signalpb.DataMessage) {
	ts := msg.GetTimestamp()
	id := sender + ":" + strconv.FormatUint(ts, 10)
	event := map[string]any{"type": "message", "threadKey": key, "id": id, "senderId": sender, "text": msg.GetBody(),
		"kind": "text", "timestampMs": int64(ts), "isEncrypted": true}
	if msg.Contact != nil {
		event["text"] = "👤 Contact"
	}
	if replyTo := signalReplyTo(msg); replyTo != "" {
		event["replyToId"] = replyTo
	}
	if msg.Sticker != nil && msg.Sticker.Data != nil {
		event["kind"] = "sticker"
		go s.downloadAttachment(key, id, "sticker", msg.Sticker.Data)
	} else if len(msg.Attachments) > 0 {
		attachment := msg.Attachments[0]
		kind, _ := mediaKind(attachment.GetContentType())
		event["kind"] = kind
		if kind == "image" {
			go s.downloadAttachment(key, id, "image", attachment)
		} else if ref, err := proto.Marshal(attachment); err == nil {
			event["mediaRef"] = base64.StdEncoding.EncodeToString(ref)
			if kind == "file" && msg.GetBody() == "" {
				event["text"] = attachment.GetFileName()
			}
		}
	}
	s.emit(event)
}

func (s *Signal) mediaPath(id string) (string, error) {
	dir := filepath.Join(s.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return filepath.Join(dir, strings.NewReplacer(":", "_", "/", "_", "|", "_").Replace(id)), nil
}

func (s *Signal) downloadToFile(pointer *signalpb.AttachmentPointer, path string) error {
	if _, err := os.Stat(path); err == nil {
		return nil
	}
	// Written next to it and renamed when complete, so path never holds half a file (see partPath)
	tmp := partPath(path)
	file, err := os.Create(tmp)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	_, err = signalmeow.DownloadAttachmentWithPointer(ctx, pointer, nil, file)
	closeErr := file.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(tmp, path)
	}
	if err != nil {
		_ = os.Remove(tmp)
	}
	return err
}

func (s *Signal) downloadAttachment(key, id, kind string, pointer *signalpb.AttachmentPointer) {
	path, err := s.mediaPath(id)
	if err != nil {
		return
	}
	if err = s.downloadToFile(pointer, path); err != nil {
		s.log.Warn().Err(err).Msg("Failed to download a Signal attachment")
		return
	}
	s.emit(map[string]any{
		"type": "attachment", "threadKey": key, "messageId": id, "kind": kind, "url": "file://" + path,
		"previewUrl": "file://" + path, "mimeType": pointer.GetContentType(), "fileName": pointer.GetFileName(),
		"width": pointer.GetWidth(), "height": pointer.GetHeight(),
	})
}

// DownloadMedia downloads a message's attachment from its mediaRef and returns the file's path.
func (s *Signal) DownloadMedia(threadKey, messageID, mediaRef string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(mediaRef)
	if err != nil {
		return "", err
	}
	var pointer signalpb.AttachmentPointer
	if err = proto.Unmarshal(raw, &pointer); err != nil {
		return "", err
	}
	path, err := s.mediaPath(messageID)
	if err != nil {
		return "", err
	}
	return path, s.downloadToFile(&pointer, path)
}

// --- outgoing ---

func (s *Signal) send(ctx context.Context, threadKey string, content *signalpb.Content) error {
	client := s.currentClient()
	if client == nil {
		return errors.New("not connected")
	}
	if isSignalGroup(threadKey) {
		result, err := client.SendGroupMessage(ctx, types.GroupIdentifier(threadKey), content)
		if err != nil {
			return err
		}
		if len(result.SuccessfullySentTo) == 0 && len(result.FailedToSendTo) > 0 {
			return errors.New("couldn't send to anyone in the group")
		}
		return nil
	}
	recipient := libsignalgo.NewACIServiceID(uuid.Nil)
	if pniKey, ok := strings.CutPrefix(threadKey, signalPNIPrefix); ok {
		pni, err := uuid.Parse(pniKey)
		if err != nil {
			return err
		}
		if moved, ok := s.pniChats.Load(pni); ok && moved != true {
			recipient = libsignalgo.NewACIServiceID(moved.(uuid.UUID))
		} else {
			recipient = libsignalgo.NewPNIServiceID(pni)
		}
	} else {
		aci, err := uuid.Parse(threadKey)
		if err != nil {
			return err
		}
		recipient = libsignalgo.NewACIServiceID(aci)
	}
	result := client.SendMessage(ctx, recipient, content)
	if !result.WasSuccessful {
		if result.Error != nil {
			return result.Error
		}
		return errors.New("failed to send")
	}
	return nil
}

func (s *Signal) selfID() string {
	if client := s.currentClient(); client != nil {
		return client.Store.ACI.String()
	}
	return ""
}

// SendText sends a message and returns its timestamp, which the echo carries as otid.
func (s *Signal) SendText(threadKey, text string) (string, error) {
	ts := uint64(time.Now().UnixMilli())
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := s.send(ctx, threadKey, signalmeow.WrapDataMessage(&signalpb.DataMessage{Body: &text, Timestamp: &ts})); err != nil {
		return "", err
	}
	s.echo(threadKey, ts, text, "text")
	return strconv.FormatUint(ts, 10), nil
}

func (s *Signal) echo(threadKey string, ts uint64, text, kind string) {
	self := s.selfID()
	s.sentThreads.Store(ts, threadKey)
	s.emit(map[string]any{"type": "message", "threadKey": threadKey, "id": self + ":" + strconv.FormatUint(ts, 10),
		"otid": strconv.FormatUint(ts, 10), "senderId": self, "text": text, "kind": kind, "timestampMs": int64(ts), "isEncrypted": true})
}

// SendMediaFile sends the file at path with an optional caption and returns its timestamp (the echo's otid).
func (s *Signal) SendMediaFile(threadKey, path, mimeType, fileName, caption string, width, height int) (string, error) {
	client := s.currentClient()
	if client == nil {
		return "", errors.New("not connected")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	pointer, err := client.UploadAttachment(ctx, data)
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	pointer.ContentType = &mimeType
	if fileName != "" {
		pointer.FileName = &fileName
	}
	if width > 0 && height > 0 {
		w, h := uint32(width), uint32(height)
		pointer.Width, pointer.Height = &w, &h
	}
	ts := uint64(time.Now().UnixMilli())
	msg := &signalpb.DataMessage{Timestamp: &ts, Attachments: []*signalpb.AttachmentPointer{pointer}}
	if caption != "" {
		msg.Body = &caption
	}
	if err = s.send(ctx, threadKey, signalmeow.WrapDataMessage(msg)); err != nil {
		return "", err
	}
	kind, _ := mediaKind(mimeType)
	s.echo(threadKey, ts, caption, kind)
	// Keep our copy as the echo's media
	id := s.selfID() + ":" + strconv.FormatUint(ts, 10)
	if cached, err := s.mediaPath(id); err == nil && os.Rename(path, cached) == nil {
		s.emit(map[string]any{"type": "attachment", "threadKey": threadKey, "messageId": id, "kind": kind,
			"url": "file://" + cached, "previewUrl": "file://" + cached, "mimeType": mimeType, "fileName": fileName,
			"width": width, "height": height})
	} else {
		_ = os.Remove(path)
	}
	return strconv.FormatUint(ts, 10), nil
}

func (s *Signal) rememberOwnReaction(messageID, emoji string) {
	if emoji == "" {
		s.ownReactions.Delete(messageID)
	} else {
		s.ownReactions.Store(messageID, emoji)
	}
}

// SendVoice sends the recording at path (AAC) as a voice message, which Signal shows with a play button.
func (s *Signal) SendVoice(threadKey, path, mimeType string, durationMs int, _ string) (string, error) {
	client := s.currentClient()
	if client == nil {
		return "", errors.New("not connected")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	pointer, err := client.UploadAttachment(ctx, data)
	if err != nil {
		return "", fmt.Errorf("upload failed: %w", err)
	}
	pointer.ContentType = &mimeType
	pointer.Flags = proto.Uint32(uint32(signalpb.AttachmentPointer_VOICE_MESSAGE))
	ts := uint64(time.Now().UnixMilli())
	msg := &signalpb.DataMessage{Timestamp: &ts, Attachments: []*signalpb.AttachmentPointer{pointer}}
	if err = s.send(ctx, threadKey, signalmeow.WrapDataMessage(msg)); err != nil {
		return "", err
	}
	s.echo(threadKey, ts, "", "audio")
	id := s.selfID() + ":" + strconv.FormatUint(ts, 10)
	if cached, err := s.mediaPath(id); err == nil && os.Rename(path, cached) == nil {
		s.emit(map[string]any{"type": "attachment", "threadKey": threadKey, "messageId": id, "kind": "audio",
			"url": "file://" + cached, "previewUrl": "file://" + cached, "mimeType": mimeType, "fileName": "Voice message.aac",
			"durationMs": durationMs})
	} else {
		_ = os.Remove(path)
	}
	return strconv.FormatUint(ts, 10), nil
}

// SendReaction reacts to a message (by its "<author>:<timestamp>" ID); an empty emoji removes our reaction.
func (s *Signal) SendReaction(threadKey, messageID, emoji string) error {
	author, targetTS, err := splitSignalMessageID(messageID)
	if err != nil {
		return err
	}
	remove := emoji == ""
	if remove {
		previous, ok := s.ownReactions.Load(messageID)
		if !ok {
			return nil
		}
		emoji = previous.(string)
	}
	authorStr := author.String()
	ts := uint64(time.Now().UnixMilli())
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	err = s.send(ctx, threadKey, signalmeow.WrapDataMessage(&signalpb.DataMessage{
		Timestamp: &ts,
		Reaction: &signalpb.DataMessage_Reaction{
			Emoji: &emoji, Remove: &remove, TargetAuthorAci: &authorStr, TargetSentTimestamp: &targetTS,
		},
	}))
	if err != nil {
		return err
	}
	// Signal doesn't echo our own reactions back to this device
	if remove {
		emoji = ""
	}
	s.rememberOwnReaction(messageID, emoji)
	s.emit(map[string]any{"type": "reaction", "threadKey": threadKey, "targetId": messageID, "senderId": s.selfID(), "emoji": emoji})
	return nil
}

func splitSignalMessageID(messageID string) (uuid.UUID, uint64, error) {
	author, ts, ok := strings.Cut(messageID, ":")
	if !ok {
		return uuid.Nil, 0, errors.New("invalid message ID")
	}
	aci, err := uuid.Parse(author)
	if err != nil {
		return uuid.Nil, 0, err
	}
	n, err := strconv.ParseUint(ts, 10, 64)
	return aci, n, err
}

// MarkRead sends a read receipt for a message to its author.
func (s *Signal) MarkRead(threadKey, messageID string) error {
	author, ts, err := splitSignalMessageID(messageID)
	if err != nil {
		return err
	}
	client := s.currentClient()
	if client == nil || author == client.Store.ACI {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	result := client.SendMessage(ctx, libsignalgo.NewACIServiceID(author), signalmeow.ReadReceptMessageForTimestamps([]uint64{ts}))
	if !result.WasSuccessful {
		return result.Error
	}
	return nil
}

// SetTyping shows or stops the typing indicator in a chat.
func (s *Signal) SetTyping(threadKey string, isTyping bool) error {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return s.send(ctx, threadKey, signalmeow.TypingMessage(isTyping))
}

func (s *Signal) emitState(state, err string) {
	s.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (s *Signal) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && s.listener != nil {
		s.listener.OnEvent(string(data))
	}
}

// SearchPeople looks up a phone number or a Signal username (name.12) on Signal's servers.
func (s *Signal) SearchPeople(query string) (string, error) {
	s.lock.Lock()
	client := s.client
	s.lock.Unlock()
	if client == nil {
		return "", errors.New("Signal isn't connected")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	query = strings.TrimSpace(query)
	var aci, pni uuid.UUID
	name, handle := query, query
	if digits := phoneDigits(query); digits != "" {
		e164 := "+" + digits
		name, handle = e164, e164
		if recipient, err := client.ContactByE164(ctx, e164); err == nil && recipient != nil && recipient.ACI != uuid.Nil {
			aci = recipient.ACI
			name = firstNonEmpty(recipient.ContactName, recipient.Profile.Name, e164)
		} else {
			number, _ := strconv.ParseUint(digits, 10, 64)
			resp, err := client.LookupPhone(ctx, number)
			if err != nil {
				return "", err
			}
			aci, pni = resp[number].ACI, resp[number].PNI
			if aci != uuid.Nil {
				_, _ = client.Store.RecipientStore.UpdateRecipientE164(ctx, aci, resp[number].PNI, e164)
			}
		}
	} else if signalmeow.SignalUsernameRegex.MatchString(query) {
		serviceID, err := client.ResolveUsername(ctx, strings.TrimPrefix(query, "@"))
		if err != nil {
			return peopleJSON(nil)
		}
		aci, _ = serviceID.ToACIAndPNI()
	}
	if aci == uuid.Nil && pni != uuid.Nil {
		// The number's owner hides their account behind it: Signal takes a first message to the number itself
		key := signalPNIPrefix + pni.String()
		s.pniChats.LoadOrStore(pni, true)
		s.emit(map[string]any{"type": "contact", "id": key, "name": name, "firstName": strings.Fields(name)[0]})
		return peopleJSON([]foundPerson{{ID: key, Name: name, Handle: handle}})
	}
	if aci == uuid.Nil {
		return peopleJSON(nil)
	}
	s.emit(map[string]any{"type": "contact", "id": aci.String(), "name": name, "firstName": strings.Fields(name)[0]})
	return peopleJSON([]foundPerson{{ID: aci.String(), Name: name, Handle: handle}})
}

// signalPNIPrefix starts the key of a chat started by phone number, before the person's account is known.
const signalPNIPrefix = "PNI:"

// movePNIChat: someone a chat was started with by phone number now writes from their account (ACI): the chat moves
// to it, and later messages to the number go to the account.
func (s *Signal) movePNIChat(aci uuid.UUID) {
	client := s.currentClient()
	if client == nil || aci == uuid.Nil || aci == client.Store.ACI {
		return
	}
	s.pniChats.Range(func(key, value any) bool {
		pni := key.(uuid.UUID)
		if value != true {
			return true
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		recipient, err := client.Store.RecipientStore.LoadAndUpdateRecipient(ctx, aci, uuid.Nil, nil)
		if err == nil && recipient != nil && recipient.PNI == pni {
			s.pniChats.Store(pni, aci)
			s.emit(map[string]any{"type": "moved", "from": signalPNIPrefix + pni.String(), "to": aci.String()})
			return false
		}
		return true
	})
}
