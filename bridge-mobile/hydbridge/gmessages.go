package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// GMessages is Google Messages (SMS, MMS and RCS from the user's Android phone) paired like Messages for Web
// (mautrix-gmessages' libgm) and running in the app. Thread keys are conversation IDs, contact IDs are
// participant IDs and message IDs are Google Messages' own. Our own messages have sender "me".
//
// Pairing either signs in with the Google account (cookies from a web login, then the phone asks to confirm an
// emoji, which works on one phone) or shows QR codes for Google Messages' "Device pairing" scanner.
type GMessages struct {
	dataDir  string
	listener Listener
	log      zerolog.Logger

	lock       sync.Mutex
	client     *libgm.Client
	cancel     context.CancelFunc
	linkCancel context.CancelFunc

	// Conversation ID → *gmproto.Conversation
	convs sync.Map
	// Participant ID → *gmproto.SIMCard; our own participant IDs are the SIMs'
	sims sync.Map
	// Message ID → timestamp (µs), for paging
	msgTimes sync.Map
	// Message ID → participant → emoji, as last emitted (to emit removed reactions)
	reactions sync.Map
	// Message ID → our own reaction on it
	ownReactions sync.Map
	// Whether the inbox was loaded since connecting
	synced bool
}

const (
	gmessagesSelfID      = "me"
	gmessagesSessionFile = "gmessages-session.json"
	gmessagesPageSize    = 30
	// Conversations whose recent messages are loaded on connect
	gmessagesPrefetchChats = 20
	gmessagesQRExpiry      = 30 * time.Second
	gmessagesQRAttempts    = 10
)

// NewGMessages prepares the client; a previous pairing is read from dataDir.
func NewGMessages(dataDir string, listener Listener) (*GMessages, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	log := zerolog.New(logWriter{}).With().Timestamp().Str("component", "gmessages").Logger().Level(zerolog.InfoLevel)
	return &GMessages{dataDir: dataDir, listener: listener, log: log}, nil
}

func (g *GMessages) sessionPath() string {
	return filepath.Join(g.dataDir, gmessagesSessionFile)
}

func (g *GMessages) loadSession() *libgm.AuthData {
	data, err := os.ReadFile(g.sessionPath())
	if err != nil {
		return nil
	}
	auth := &libgm.AuthData{}
	if err = json.Unmarshal(data, auth); err != nil || auth.Browser == nil {
		return nil
	}
	return auth
}

func (g *GMessages) saveSession(auth *libgm.AuthData) {
	auth.CookiesLock.RLock()
	data, err := json.Marshal(auth)
	auth.CookiesLock.RUnlock()
	if err != nil {
		g.log.Err(err).Msg("Failed to encode the Google Messages session")
		return
	}
	tmp := g.sessionPath() + ".tmp"
	if err = os.WriteFile(tmp, data, 0o600); err == nil {
		err = os.Rename(tmp, g.sessionPath())
	}
	if err != nil {
		g.log.Err(err).Msg("Failed to save the Google Messages session")
	}
}

// IsLinked tells whether this app was paired with Google Messages.
func (g *GMessages) IsLinked() bool {
	return g.loadSession() != nil
}

func newGMClient(auth *libgm.AuthData, log zerolog.Logger) *libgm.Client {
	return libgm.NewClient(auth, nil, log, exhttp.SensibleClientSettings)
}

// --- pairing ---

// LinkWithCookies pairs using a Google account signed in on the web: cookiesJSON holds the google.com cookies
// (SID, HSID, SSID, APISID, SAPISID and messages.google.com's OSID). A pairing event's "code" is the emoji the
// user then taps in Google Messages on the phone; once they do, it connects.
func (g *GMessages) LinkWithCookies(cookiesJSON string) error {
	cookies := map[string]string{}
	if err := json.Unmarshal([]byte(cookiesJSON), &cookies); err != nil {
		return fmt.Errorf("invalid cookies: %w", err)
	}
	for _, name := range []string{"SID", "HSID", "SSID", "APISID", "SAPISID", "OSID"} {
		if cookies[name] == "" {
			return fmt.Errorf("missing cookie %s", name)
		}
	}
	ctx := g.startLinking(5 * time.Minute)
	go g.linkWithCookies(ctx, cookies)
	return nil
}

func (g *GMessages) startLinking(timeout time.Duration) context.Context {
	g.lock.Lock()
	defer g.lock.Unlock()
	if g.linkCancel != nil {
		g.linkCancel()
	}
	ctx, cancel := context.WithTimeout(g.log.WithContext(context.Background()), timeout)
	g.linkCancel = cancel
	return ctx
}

func (g *GMessages) linkWithCookies(ctx context.Context, cookies map[string]string) {
	auth := libgm.NewAuthData()
	auth.SetCookies(cookies)
	client := newGMClient(auth, g.log)
	client.SetEventHandler(func(any) {})
	defer client.Disconnect()
	if err := client.FetchConfig(ctx); err != nil {
		g.emitState("pairing_failed", "couldn't reach Google Messages: "+err.Error())
		return
	}
	emoji, session, err := client.StartGaiaPairing(ctx, ctx)
	if err != nil {
		g.emitState("pairing_failed", gaiaErrorMessage(err))
		return
	}
	g.emit(map[string]any{"type": "pairing", "code": emoji})
	if _, err = client.FinishGaiaPairing(ctx, session); err != nil {
		g.emitState("pairing_failed", gaiaErrorMessage(err))
		return
	}
	g.saveSession(auth)
	g.finishLinking()
}

func gaiaErrorMessage(err error) string {
	switch {
	case errors.Is(err, libgm.ErrNoDevicesFound):
		return "no phone found; in Google Messages on your phone, open Device pairing and sign in with this Google account"
	case errors.Is(err, libgm.ErrPhoneNotFound):
		return "the paired phone wasn't found"
	case errors.Is(err, libgm.ErrPairingInitTimeout):
		return "your phone didn't answer; open Google Messages on it and try again"
	case errors.Is(err, libgm.ErrIncorrectEmoji):
		return "a different emoji was tapped on the phone"
	case errors.Is(err, libgm.ErrPairingCancelled):
		return "pairing was cancelled on the phone"
	case errors.Is(err, libgm.ErrPairingTimeout), errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case errors.Is(err, events.ErrCallerNoPermission):
		return "Google refused this account (is it a Workspace account with Messages for Web turned off?)"
	}
	return err.Error()
}

// LinkWithQR shows QR codes (pairing events) to scan in Google Messages → Device pairing → QR code scanner, until
// one is scanned, then connects. Codes refresh every 30 seconds for about five minutes.
func (g *GMessages) LinkWithQR() error {
	ctx := g.startLinking(gmessagesQRExpiry * (gmessagesQRAttempts + 1))
	go g.linkWithQR(ctx)
	return nil
}

func (g *GMessages) linkWithQR(ctx context.Context) {
	auth := libgm.NewAuthData()
	client := newGMClient(auth, g.log)
	client.SetEventHandler(func(any) {})
	defer client.Disconnect()
	paired := make(chan *gmproto.PairedData, 1)
	callback := func(data *gmproto.PairedData) {
		select {
		case paired <- data:
		default:
		}
	}
	client.PairCallback.Store(&callback)
	if err := client.FetchConfig(ctx); err != nil {
		g.emitState("pairing_failed", "couldn't reach Google Messages: "+err.Error())
		return
	}
	qr, err := client.StartLogin(ctx)
	if err != nil {
		g.emitState("pairing_failed", err.Error())
		return
	}
	g.emit(map[string]any{"type": "pairing", "qr": qr})
	for attempt := 0; ; attempt++ {
		select {
		case <-paired:
			// Give the phone a moment to save the pairing; reconnecting too quickly gets the session unpaired
			time.Sleep(2 * time.Second)
			g.saveSession(auth)
			g.finishLinking()
			return
		case <-time.After(gmessagesQRExpiry):
			if attempt >= gmessagesQRAttempts {
				g.emitState("pairing_failed", "timeout")
				return
			}
			if qr, err = client.RefreshPhoneRelay(); err != nil {
				g.emitState("pairing_failed", err.Error())
				return
			}
			g.emit(map[string]any{"type": "pairing", "qr": qr})
		case <-ctx.Done():
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				g.emitState("pairing_failed", "timeout")
			}
			return
		}
	}
}

func (g *GMessages) finishLinking() {
	g.lock.Lock()
	if g.linkCancel != nil {
		g.linkCancel()
		g.linkCancel = nil
	}
	g.lock.Unlock()
	g.emitState("linked", "")
	if err := g.Connect(); err != nil {
		g.emitState("pairing_failed", err.Error())
	}
}

// CancelLink stops pairing.
func (g *GMessages) CancelLink() {
	g.lock.Lock()
	defer g.lock.Unlock()
	if g.linkCancel != nil {
		g.linkCancel()
		g.linkCancel = nil
	}
}

// --- connection ---

// Connect starts receiving with the saved pairing; chats arrive once the phone answers.
func (g *GMessages) Connect() error {
	g.lock.Lock()
	defer g.lock.Unlock()
	if g.cancel != nil {
		return nil
	}
	auth := g.loadSession()
	if auth == nil {
		return errors.New("not paired")
	}
	ctx, cancel := context.WithCancel(g.log.WithContext(context.Background()))
	client := newGMClient(auth, g.log)
	client.SetEventHandler(g.handleEvent)
	if err := client.FetchConfig(ctx); err != nil {
		g.log.Warn().Err(err).Msg("Failed to fetch the Google Messages config")
	}
	if err := client.Connect(ctx); err != nil {
		cancel()
		if errors.Is(err, events.ErrRequestedEntityNotFound) || errors.Is(err, events.ErrInvalidCredentials) {
			g.loggedOut()
			return errors.New("Google Messages was unpaired; pair again")
		}
		return fmt.Errorf("failed to connect: %w", err)
	}
	g.client, g.cancel, g.synced = client, cancel, false
	g.emit(map[string]any{"type": "self", "id": gmessagesSelfID, "name": client.Config.GetDeviceInfo().GetEmail()})
	return nil
}

func (g *GMessages) currentClient() *libgm.Client {
	g.lock.Lock()
	defer g.lock.Unlock()
	return g.client
}

// Disconnect stops receiving; Connect can be called again.
func (g *GMessages) Disconnect() {
	g.lock.Lock()
	client, cancel := g.client, g.cancel
	g.client, g.cancel = nil, nil
	g.lock.Unlock()
	if client != nil {
		client.Disconnect()
		g.saveSession(client.AuthData)
	}
	if cancel != nil {
		cancel()
	}
	g.emitState("disconnected", "")
}

// Logout unpairs this app from Google Messages and forgets the session.
func (g *GMessages) Logout() error {
	g.CancelLink()
	client := g.currentClient()
	var err error
	if client != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		err = client.Unpair(ctx)
		cancel()
	}
	g.Disconnect()
	_ = os.Remove(g.sessionPath())
	return err
}

func (g *GMessages) loggedOut() {
	_ = os.Remove(g.sessionPath())
	g.emitState("logged_out", "")
}

func (g *GMessages) handleEvent(rawEvt any) {
	// Called synchronously by libgm: anything asking the phone goes to a goroutine
	switch evt := rawEvt.(type) {
	case *events.ListenFatalError:
		if errors.Is(evt.Error, events.ErrInvalidCredentials) || evt.Error.Error() == "http 401 while polling" {
			go g.unpaired()
		} else {
			g.emitState("reconnecting", evt.Error.Error())
		}
	case *events.ListenTemporaryError:
		g.emitState("reconnecting", evt.Error.Error())
	case *events.ListenRecovered, *events.PhoneRespondingAgain:
		g.emitState("connected", "")
	case *events.PhoneNotResponding:
		g.emitState("reconnecting", "your phone isn't responding")
	case *events.PingFailed:
		if errors.Is(evt.Error, events.ErrRequestedEntityNotFound) {
			go g.unpaired()
		}
	case *gmproto.RevokePairData, *events.GaiaLoggedOut:
		go g.unpaired()
	case *events.AuthTokenRefreshed:
		if client := g.currentClient(); client != nil {
			go g.saveSession(client.AuthData)
		}
	case *gmproto.UserAlertEvent:
		switch evt.GetAlertType() {
		case gmproto.AlertType_BROWSER_ACTIVE:
			g.emitState("connected", "")
			go g.syncConversations()
		case gmproto.AlertType_MOBILE_DATABASE_SYNC_COMPLETE:
			go g.syncConversations()
		case gmproto.AlertType_BROWSER_INACTIVE, gmproto.AlertType_BROWSER_INACTIVE_FROM_TIMEOUT,
			gmproto.AlertType_BROWSER_INACTIVE_FROM_INACTIVITY:
			// Only one Messages for Web session is active at a time; reconnecting takes it back
			g.emitState("reconnecting", "Google Messages is open in another browser")
		}
	case *gmproto.Settings:
		for _, sim := range evt.GetSIMCards() {
			if id := sim.GetSIMParticipant().GetID(); id != "" {
				g.sims.Store(id, sim)
			}
		}
	case *gmproto.Conversation:
		g.emitConversation(evt)
	case *libgm.WrappedMessage:
		g.emitMessage(evt.Message)
	case *gmproto.TypingData:
		conv := g.conversation(evt.GetConversationID())
		if sender := participantByNumber(conv, evt.GetUser().GetNumber()); sender != "" {
			g.emit(map[string]any{"type": "typing", "threadKey": evt.GetConversationID(), "senderId": sender,
				"isTyping": evt.GetType() == gmproto.TypingTypes_STARTED_TYPING})
		}
	}
}

func (g *GMessages) unpaired() {
	g.lock.Lock()
	client, cancel := g.client, g.cancel
	g.client, g.cancel = nil, nil
	g.lock.Unlock()
	if client != nil {
		client.Disconnect()
	}
	if cancel != nil {
		cancel()
	}
	g.loggedOut()
}

// syncConversations loads the inbox and, for the most recent chats, their latest messages.
func (g *GMessages) syncConversations() {
	client := g.currentClient()
	if client == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	resp, err := client.ListConversations(ctx, &gmproto.ListConversationsRequest{
		Count: 100, Folder: gmproto.ListConversationsRequest_INBOX,
	})
	if err != nil {
		g.log.Err(err).Msg("Failed to load Google Messages conversations")
		return
	}
	for _, conv := range resp.GetConversations() {
		g.emitConversation(conv)
	}
	g.lock.Lock()
	first := !g.synced
	g.synced = true
	g.lock.Unlock()
	if !first {
		return
	}
	for i, conv := range resp.GetConversations() {
		if i >= gmessagesPrefetchChats || ctx.Err() != nil {
			break
		}
		if err = g.fetchMessages(ctx, client, conv.GetConversationID(), nil); err != nil {
			g.log.Warn().Err(err).Msg("Failed to load recent Google Messages messages")
		}
	}
}

func (g *GMessages) conversation(id string) *gmproto.Conversation {
	if conv, ok := g.convs.Load(id); ok {
		return conv.(*gmproto.Conversation)
	}
	return nil
}

func isHiddenConversation(status gmproto.ConversationStatus) bool {
	switch status {
	case gmproto.ConversationStatus_DELETED, gmproto.ConversationStatus_SPAM_FOLDER,
		gmproto.ConversationStatus_BLOCKED_FOLDER, gmproto.ConversationStatus_TRASH_FOLDER:
		return true
	}
	return false
}

func (g *GMessages) emitConversation(conv *gmproto.Conversation) {
	if conv.GetConversationID() == "" {
		return
	}
	g.convs.Store(conv.ConversationID, conv)
	if isHiddenConversation(conv.GetStatus()) {
		return
	}
	key := conv.ConversationID
	for _, p := range conv.GetParticipants() {
		id := p.GetID().GetParticipantID()
		if p.GetIsMe() || id == "" || !p.GetIsVisible() {
			continue
		}
		name := firstNonEmpty(p.GetFullName(), p.GetFormattedNumber(), p.GetID().GetNumber())
		g.emit(map[string]any{"type": "contact", "id": id, "name": name, "firstName": firstNonEmpty(p.GetFirstName(), name)})
		g.emit(map[string]any{"type": "participant", "threadKey": key, "contactId": id})
	}
	lastActivity := conv.GetLastMessageTimestamp() / 1000
	snippetSender := ""
	if conv.GetLatestMessage().GetFromMe() != 0 {
		snippetSender = gmessagesSelfID
	}
	g.emit(map[string]any{
		"type": "thread", "key": key, "name": conv.GetName(), "isGroup": conv.GetIsGroupChat(), "isEncrypted": false,
		"lastActivityMs": lastActivity, "lastReadMs": lastReadFromUnread(boolToUnread(conv.GetUnread()), lastActivity),
		"snippet": conv.GetLatestMessage().GetDisplayContent(), "snippetSender": snippetSender,
	})
}

// participantByNumber finds who in a conversation has a phone number (typing events only name the number).
func participantByNumber(conv *gmproto.Conversation, number string) string {
	if conv == nil || number == "" {
		return ""
	}
	var hidden string
	for _, p := range conv.GetParticipants() {
		if p.GetID().GetNumber() == number && !p.GetIsMe() {
			if p.GetIsVisible() {
				return p.GetID().GetParticipantID()
			}
			hidden = p.GetID().GetParticipantID()
		}
	}
	return hidden
}

// visibleParticipant maps a hidden participant ID (Google Messages sometimes sends those) to the visible
// participant with the same number, so a person has one contact ID.
func visibleParticipant(conv *gmproto.Conversation, id string) string {
	if conv == nil {
		return id
	}
	var number string
	for _, p := range conv.GetParticipants() {
		if p.GetID().GetParticipantID() == id {
			if p.GetIsVisible() {
				return id
			}
			number = p.GetID().GetNumber()
		}
	}
	if number == "" {
		return id
	}
	for _, p := range conv.GetParticipants() {
		if p.GetIsVisible() && p.GetID().GetNumber() == number {
			return p.GetID().GetParticipantID()
		}
	}
	return id
}

func (g *GMessages) isSelf(conv *gmproto.Conversation, participantID string) bool {
	if participantID == "1" {
		return true
	}
	if _, ok := g.sims.Load(participantID); ok {
		return true
	}
	for _, p := range conv.GetParticipants() {
		if p.GetIsMe() && p.GetID().GetParticipantID() == participantID {
			return true
		}
	}
	return false
}

func isTombstone(status gmproto.MessageStatusType) bool {
	return status >= 200 && status < 300
}

func (g *GMessages) emitMessage(msg *gmproto.Message) {
	if msg == nil || msg.GetMessageID() == "" {
		return
	}
	status := msg.GetMessageStatus().GetStatus()
	key := msg.GetConversationID()
	if isTombstone(status) {
		return
	}
	if status == gmproto.MessageStatusType_MESSAGE_DELETED {
		g.emit(map[string]any{"type": "unsend", "threadKey": key, "messageId": msg.MessageID})
		return
	}
	g.msgTimes.Store(msg.MessageID, msg.GetTimestamp())
	conv := g.conversation(key)
	// Statuses 1–99 are outgoing, 100–199 incoming
	outgoing := status >= 1 && status < 100 || (status == 0 && g.isSelf(conv, msg.GetParticipantID()))
	sender := gmessagesSelfID
	if !outgoing {
		sender = visibleParticipant(conv, msg.GetParticipantID())
	}
	var parts []string
	if subject := msg.GetSubject(); subject != "" {
		parts = append(parts, subject)
	}
	var media *gmproto.MediaContent
	for _, info := range msg.GetMessageInfo() {
		switch data := info.GetData().(type) {
		case *gmproto.MessageInfo_MessageContent:
			parts = append(parts, data.MessageContent.GetContent())
		case *gmproto.MessageInfo_LocationContent:
			parts = append(parts, data.LocationContent.GetMessageContent().GetContent())
		case *gmproto.MessageInfo_MediaContent:
			if media == nil {
				media = data.MediaContent
			}
		}
	}
	text := strings.TrimSpace(strings.Join(parts, "\n"))
	kind := "text"
	if media != nil {
		kind, _ = mediaKind(media.GetMimeType())
		if kind == "file" && text == "" {
			text = media.GetMediaName()
		}
	} else if text == "" {
		kind = "other"
		if status >= gmproto.MessageStatusType_INCOMING_YET_TO_MANUAL_DOWNLOAD && status < 200 {
			text = "Media not downloaded on the phone yet"
		}
	}
	event := map[string]any{
		"type": "message", "threadKey": key, "id": msg.MessageID, "otid": msg.GetTmpID(), "senderId": sender,
		"text": text, "kind": kind, "timestampMs": msg.GetTimestamp() / 1000,
	}
	if replyTo := msg.GetReplyMessage().GetMessageID(); replyTo != "" {
		event["replyToId"] = replyTo
	}
	if media != nil && media.GetMediaID() != "" && kind != "image" {
		if ref, err := json.Marshal(gmMediaRef{ID: media.GetMediaID(), Key: media.GetDecryptionKey(), MimeType: media.GetMimeType()}); err == nil {
			event["mediaRef"] = string(ref)
		}
	}
	g.emit(event)
	if media != nil && media.GetMediaID() != "" && kind == "image" {
		go g.downloadImage(key, msg.MessageID, media)
	}
	g.emitReactions(conv, key, msg)
	if status == gmproto.MessageStatusType_OUTGOING_DISPLAYED && conv != nil && !conv.GetIsGroupChat() {
		for _, other := range conv.GetOtherParticipants() {
			g.emit(map[string]any{"type": "read", "threadKey": key, "contactId": visibleParticipant(conv, other),
				"watermarkMs": msg.GetTimestamp() / 1000})
		}
	}
}

// emitReactions sends a message's reactions, and removals of ones it no longer has.
func (g *GMessages) emitReactions(conv *gmproto.Conversation, key string, msg *gmproto.Message) {
	current := map[string]string{}
	for _, entry := range msg.GetReactions() {
		emoji := entry.GetData().GetUnicode()
		for _, id := range entry.GetParticipantIDs() {
			sender := visibleParticipant(conv, id)
			if g.isSelf(conv, id) {
				sender = gmessagesSelfID
			}
			current[sender] = emoji
		}
	}
	var previous map[string]string
	if prev, ok := g.reactions.Load(msg.MessageID); ok {
		previous = prev.(map[string]string)
	}
	if len(current) == 0 && len(previous) == 0 {
		return
	}
	g.reactions.Store(msg.MessageID, current)
	if own, ok := current[gmessagesSelfID]; ok {
		g.ownReactions.Store(msg.MessageID, own)
	} else {
		g.ownReactions.Delete(msg.MessageID)
	}
	for sender, emoji := range current {
		if previous[sender] != emoji {
			g.emit(map[string]any{"type": "reaction", "threadKey": key, "targetId": msg.MessageID, "senderId": sender, "emoji": emoji})
		}
	}
	for sender := range previous {
		if _, still := current[sender]; !still {
			g.emit(map[string]any{"type": "reaction", "threadKey": key, "targetId": msg.MessageID, "senderId": sender, "emoji": ""})
		}
	}
}

func (g *GMessages) emitState(state, err string) {
	g.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (g *GMessages) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && g.listener != nil {
		g.listener.OnEvent(string(data))
	}
}
