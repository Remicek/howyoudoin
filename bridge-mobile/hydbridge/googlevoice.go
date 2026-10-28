package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/rs/zerolog"

	"go.mau.fi/mautrix-gvoice/pkg/libgv"
	"go.mau.fi/mautrix-gvoice/pkg/libgv/gvproto"
)

// GoogleVoice is the user's Google Voice texts (and calls and voicemails, shown as messages), logged in with the
// Google account cookies of voice.google.com (mautrix-gvoice's libgv client). Google pushes "something changed"
// over its real-time channel and the inbox is fetched again then. Thread keys are Google Voice thread IDs,
// contact IDs phone numbers.
//
// Google signs texts sent from its own web page with a browser-generated token, which this client doesn't make
// (mautrix-gvoice runs Electron for it); Google accepts texts without it, but may limit an account that sends
// many.
type GoogleVoice struct {
	dataDir  string
	listener Listener
	log      zerolog.Logger
	client   *libgv.Client

	lock   sync.Mutex
	cancel context.CancelFunc
	wakeup chan struct{}

	// Thread ID → timestamp of its newest message sent to the app
	lastSeen map[string]int64
	// Thread ID → its phone numbers, for sending
	recipients map[string][]string
	// Thread ID → pagination token for older messages
	olderTokens map[string]string
	contacts    sync.Map
}

const (
	googleVoiceSelfID          = "me"
	googleVoiceRefreshInterval = 15 * time.Minute
	googleVoiceInitialThreads  = 50
	googleVoicePageSize        = 30
	googleVoiceAuthFailedText  = "Google Voice session expired, log in again"
)

// NewGoogleVoice takes the login as a JSON object of Google cookies (SID, HSID, SSID, APISID, SAPISID, and
// OSID, __Secure-1PSIDTS, COMPASS where present). Cookies Google renewed since are kept in dataDir and win.
func NewGoogleVoice(cookiesJSON string, dataDir string, listener Listener) (*GoogleVoice, error) {
	var cookies map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &cookies); err != nil {
		return nil, fmt.Errorf("invalid login: %w", err)
	}
	for _, name := range []string{"SID", "HSID", "SSID", "APISID", "SAPISID"} {
		if cookies[name] == "" {
			return nil, errors.New("missing the Google cookies")
		}
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	g := &GoogleVoice{
		dataDir: dataDir, listener: listener, wakeup: make(chan struct{}, 1),
		lastSeen: map[string]int64{}, recipients: map[string][]string{}, olderTokens: map[string]string{},
	}
	if saved := g.savedCookies(); saved != nil && saved["SID"] == cookies["SID"] {
		maps.Copy(cookies, saved)
	}
	g.log = zerolog.New(logWriter{}).With().Timestamp().Str("component", "googlevoice").Logger().Level(zerolog.InfoLevel)
	g.client = libgv.NewClient(cookies)
	g.client.EventHandler = g.handleEvent
	return g, nil
}

func (g *GoogleVoice) cookiesPath() string { return filepath.Join(g.dataDir, "cookies.json") }

func (g *GoogleVoice) savedCookies() map[string]string {
	data, err := os.ReadFile(g.cookiesPath())
	if err != nil {
		return nil
	}
	var cookies map[string]string
	if json.Unmarshal(data, &cookies) != nil {
		return nil
	}
	return cookies
}

// Connect checks the login, then follows Google's real-time channel and loads the inbox.
func (g *GoogleVoice) Connect() error {
	g.lock.Lock()
	if g.cancel != nil {
		g.lock.Unlock()
		return errors.New("already connected")
	}
	ctx, cancel := context.WithCancel(g.log.WithContext(context.Background()))
	g.cancel = cancel
	g.lock.Unlock()

	account, err := g.client.GetAccount(ctx)
	if err != nil {
		g.resetCancel()
		if libgv.IsAuthError(err) {
			return errors.New(googleVoiceAuthFailedText)
		}
		return fmt.Errorf("failed to load Google Voice: %w", err)
	}
	name := "Google Voice"
	for _, phone := range account.GetAccount().GetPhones() {
		if number := phone.GetAccountPhone().GetPhoneNumber().GetE164(); number != "" {
			name = number
			break
		}
	}
	g.emit(map[string]any{"type": "self", "id": googleVoiceSelfID, "name": name})
	g.emitState("connected", "")
	go g.runRealtime(ctx)
	go g.fetchLoop(ctx)
	return nil
}

// Disconnect stops following changes; Connect can be called again.
func (g *GoogleVoice) Disconnect() {
	g.resetCancel()
	g.emitState("disconnected", "")
}

func (g *GoogleVoice) resetCancel() {
	g.lock.Lock()
	defer g.lock.Unlock()
	if g.cancel != nil {
		g.cancel()
		g.cancel = nil
	}
}

func (g *GoogleVoice) runRealtime(ctx context.Context) {
	failures := 0
	for ctx.Err() == nil {
		err := g.client.RunRealtimeChannel(ctx)
		switch {
		case ctx.Err() != nil:
			return
		case libgv.IsAuthError(err):
			g.emitState("error", googleVoiceAuthFailedText)
			return
		case err != nil:
			failures++
			if failures == 3 {
				g.emitState("reconnecting", err.Error())
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(min(time.Duration(failures+1)*5*time.Second, time.Minute)):
		}
	}
}

func (g *GoogleVoice) handleEvent(ctx context.Context, raw any) {
	switch evt := raw.(type) {
	case *libgv.CookieChanged:
		if data, err := json.Marshal(evt.Cookies); err == nil {
			_ = writeFileAtomic(g.cookiesPath(), data)
		}
	case *libgv.RealtimeConnected:
		g.emitState("connected", "")
		g.wake()
	case *libgv.RealtimeEvent:
		g.wake()
	}
}

func (g *GoogleVoice) wake() {
	select {
	case g.wakeup <- struct{}{}:
	default:
	}
}

// fetchLoop loads the inbox now, whenever Google says something changed, and every few minutes regardless.
func (g *GoogleVoice) fetchLoop(ctx context.Context) {
	ticker := time.NewTicker(googleVoiceRefreshInterval)
	defer ticker.Stop()
	first := true
	for {
		if err := g.fetchThreads(ctx, first); err != nil && ctx.Err() == nil {
			g.log.Warn().Err(err).Msg("Failed to load Google Voice threads")
			if libgv.IsAuthError(err) {
				g.emitState("error", googleVoiceAuthFailedText)
			}
		}
		first = false
		select {
		case <-ctx.Done():
			return
		case <-g.wakeup:
			// Google's push can come before the change shows up in its API
			time.Sleep(500 * time.Millisecond)
		case <-ticker.C:
		}
	}
}

func (g *GoogleVoice) fetchThreads(ctx context.Context, initial bool) error {
	resp, err := g.client.ListThreads(ctx, gvproto.ThreadFolder_ALL_THREADS, "", "")
	if err != nil {
		return err
	}
	threads := resp.Threads
	token := resp.PaginationToken
	for initial && len(threads) < googleVoiceInitialThreads && token != "" {
		page, err := g.client.ListThreads(ctx, gvproto.ThreadFolder_ALL_THREADS, token, "")
		if err != nil || len(page.Threads) == 0 {
			break
		}
		threads = append(threads, page.Threads...)
		token = page.PaginationToken
	}
	for _, thread := range threads {
		g.handleThread(thread)
	}
	return nil
}

func (g *GoogleVoice) handleThread(thread *gvproto.Thread) {
	if len(thread.Messages) == 0 {
		return
	}
	g.lock.Lock()
	previous, known := g.lastSeen[thread.ID]
	g.lock.Unlock()
	if !known {
		g.emitThread(thread)
	}
	// Newest first; the app wants them in order
	for _, msg := range slices.Backward(thread.Messages) {
		if msg.Timestamp > previous {
			g.emitMessage(thread.ID, msg)
		}
	}
	newest := thread.Messages[0].Timestamp
	g.lock.Lock()
	if newest > g.lastSeen[thread.ID] {
		g.lastSeen[thread.ID] = newest
	}
	if !known {
		g.olderTokens[thread.ID] = thread.PaginationToken
	}
	g.lock.Unlock()
	event := map[string]any{"type": "thread", "key": thread.ID, "lastActivityMs": newest}
	if thread.Read {
		event["lastReadMs"] = newest
	}
	g.emit(event)
}

func (g *GoogleVoice) emitThread(thread *gvproto.Thread) {
	var names, numbers []string
	for _, c := range thread.Contacts {
		number := c.GetPhoneNumber()
		if number == "" {
			continue
		}
		numbers = append(numbers, number)
		g.emitContact(c)
		names = append(names, firstNonEmpty(c.GetName(), number))
		g.emit(map[string]any{"type": "participant", "threadKey": thread.ID, "contactId": number})
	}
	if len(numbers) == 0 {
		numbers = thread.PhoneNumbers
	}
	g.lock.Lock()
	g.recipients[thread.ID] = numbers
	g.lock.Unlock()
	newest := thread.Messages[0].Timestamp
	lastRead := newest
	if !thread.Read {
		lastRead = newest - 1
	}
	g.emit(map[string]any{
		"type": "thread", "key": thread.ID, "name": strings.Join(names, ", "), "isGroup": len(numbers) > 1,
		"isEncrypted": false, "lastActivityMs": newest, "lastReadMs": lastRead,
	})
}

func (g *GoogleVoice) emitContact(c *gvproto.Contact) {
	number := c.GetPhoneNumber()
	if number == "" {
		return
	}
	if previous, sent := g.contacts.Load(number); sent && (previous == c.GetName() || c.GetName() == "") {
		return
	}
	g.contacts.Store(number, c.GetName())
	name := firstNonEmpty(c.GetName(), number)
	g.emit(map[string]any{"type": "contact", "id": number, "name": name, "firstName": strings.SplitN(name, " ", 2)[0]})
}

// googleVoiceCallText describes calls and voicemails, which Google Voice keeps in the same threads as texts.
func googleVoiceCallText(msg *gvproto.Message) string {
	duration := ""
	if seconds := int(msg.DurationSeconds); seconds > 0 {
		duration = fmt.Sprintf(" (%d:%02d)", seconds/60, seconds%60)
	}
	switch msg.GetType() {
	case gvproto.Message_MISSED_CALL:
		return "📞 Missed call"
	case gvproto.Message_INCOMING_CALL:
		return "📞 Incoming call" + duration
	case gvproto.Message_OUTGOING_CALL:
		return "📞 Outgoing call" + duration
	case gvproto.Message_OUTGOING_CALL_CANCELLED:
		return "📞 Cancelled call"
	case gvproto.Message_VOICEMAIL:
		text := "🎙 Voicemail" + duration
		if transcript := googleVoiceTranscript(msg); transcript != "" {
			text += ": " + transcript
		}
		return text
	}
	return ""
}

func googleVoiceTranscript(msg *gvproto.Message) string {
	var words []string
	for _, token := range msg.GetTranscript().GetTokens() {
		words = append(words, string(token.GetText()))
	}
	return strings.TrimSpace(strings.Join(words, " "))
}

func (g *GoogleVoice) emitMessage(threadID string, msg *gvproto.Message) {
	if msg.IsArtificialErrorMessage {
		return
	}
	sender := googleVoiceSelfID
	switch msg.GetType() {
	case gvproto.Message_SMS_OUT, gvproto.Message_OUTGOING_CALL, gvproto.Message_OUTGOING_CALL_CANCELLED:
	default:
		sender = firstNonEmpty(msg.GetMMS().GetSenderPhoneNumber(), msg.GetContact().GetPhoneNumber())
		if msg.GetContact() != nil {
			g.emitContact(msg.GetContact())
		}
	}
	text := firstNonEmpty(msg.Text, msg.GetMMS().GetText(), googleVoiceCallText(msg))
	kind := "text"
	var attachment *gvproto.Attachment
	if attachments := msg.GetMMS().GetAttachments(); len(attachments) > 0 {
		attachment = attachments[0]
		kind, _ = mediaKind(attachment.MimeType)
	}
	if text == "" && attachment == nil {
		kind = "other"
	}
	otid := msg.ID
	if msg.TransactionID != 0 {
		otid = fmt.Sprint(msg.TransactionID)
	}
	g.emit(map[string]any{
		"type": "message", "threadKey": threadID, "id": msg.ID, "otid": otid, "senderId": sender, "text": text,
		"kind": kind, "timestampMs": msg.Timestamp,
	})
	if attachment != nil && attachment.ID != "" {
		go g.downloadAttachment(threadID, msg.ID, kind, attachment)
	}
}

func (g *GoogleVoice) downloadAttachment(threadID, messageID, kind string, a *gvproto.Attachment) {
	dir := filepath.Join(g.dataDir, "media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return
	}
	path := filepath.Join(dir, strings.NewReplacer("/", "_", ":", "_", ".", "_").Replace(a.ID))
	mimeType := a.MimeType
	if _, err := os.Stat(path); err != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		data, downloadedType, err := g.client.DownloadAttachment(ctx, a.ID)
		cancel()
		if err != nil {
			g.log.Warn().Err(err).Msg("Failed to download a Google Voice attachment")
			return
		}
		mimeType = firstNonEmpty(downloadedType, mimeType)
		if err := writeFileAtomic(path, data); err != nil {
			return
		}
	}
	g.emit(map[string]any{
		"type": "attachment", "threadKey": threadID, "messageId": messageID, "kind": kind, "url": "file://" + path,
		"previewUrl": "file://" + path, "mimeType": mimeType,
	})
}

// --- sending ---

func googleVoiceContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), time.Minute)
}

func (g *GoogleVoice) send(threadID string, req *gvproto.ReqSendSMS) (string, error) {
	g.lock.Lock()
	req.ThreadID = threadID
	req.Recipients = g.recipients[threadID]
	g.lock.Unlock()
	req.TransactionID = &gvproto.ReqSendSMS_WrappedTxnID{ID: libgv.GenerateTransactionID()}
	ctx, cancel := googleVoiceContext()
	defer cancel()
	if _, err := g.client.SendMessage(ctx, req); err != nil {
		return "", err
	}
	g.wake()
	return fmt.Sprint(req.TransactionID.ID), nil
}

// SendText sends a text and returns its transaction ID (the echo's otid).
func (g *GoogleVoice) SendText(threadID, text string) (string, error) {
	return g.send(threadID, &gvproto.ReqSendSMS{Text: text})
}

// SendMediaFile sends a photo (Google Voice only takes images) with an optional caption.
func (g *GoogleVoice) SendMediaFile(threadID, path, mimeType, caption string) (string, error) {
	defer os.Remove(path)
	mediaType, ok := map[string]gvproto.ReqSendSMS_Media_Type{
		"image/png": gvproto.ReqSendSMS_Media_PNG, "image/jpeg": gvproto.ReqSendSMS_Media_JPEG,
		"image/gif": gvproto.ReqSendSMS_Media_GIF, "image/bmp": gvproto.ReqSendSMS_Media_BMP,
		"image/tiff": gvproto.ReqSendSMS_Media_TIFF,
	}[mimeType]
	if !ok {
		return "", errors.New("Google Voice can only send photos")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return g.send(threadID, &gvproto.ReqSendSMS{Text: caption, Media: &gvproto.ReqSendSMS_Media{Type: mediaType, Data: data}})
}

// MarkRead marks a thread read.
func (g *GoogleVoice) MarkRead(threadID string) error {
	ctx, cancel := googleVoiceContext()
	defer cancel()
	_, err := g.client.UpdateThreadAttributes(ctx, &gvproto.ReqUpdateAttributes{
		Attributes:      &gvproto.ThreadAttributes{ThreadID: threadID, Read: true},
		OtherAttributes: &gvproto.ThreadAttributes{Read: true},
		UnknownInt:      1,
	})
	return err
}

// RequestOlder loads the thread's next older page (the newest when nothing loaded yet); the messages arrive as
// message events, then a range event.
func (g *GoogleVoice) RequestOlder(threadID, beforeMessageID string) error {
	g.lock.Lock()
	token := ""
	if beforeMessageID != "" {
		token = g.olderTokens[threadID]
	}
	g.lock.Unlock()
	if beforeMessageID != "" && token == "" {
		g.emit(map[string]any{"type": "range", "threadKey": threadID, "hasMoreBefore": false})
		return nil
	}
	ctx, cancel := googleVoiceContext()
	defer cancel()
	resp, err := g.client.GetThread(ctx, threadID, googleVoicePageSize, token)
	if err != nil {
		return err
	}
	thread := resp.GetThread()
	if thread == nil {
		return nil
	}
	for _, msg := range slices.Backward(thread.Messages) {
		g.emitMessage(threadID, msg)
	}
	g.lock.Lock()
	g.olderTokens[threadID] = thread.PaginationToken
	g.lock.Unlock()
	g.emit(map[string]any{"type": "range", "threadKey": threadID, "hasMoreBefore": thread.PaginationToken != ""})
	return nil
}

func (g *GoogleVoice) emitState(state, err string) {
	g.emit(map[string]any{"type": "state", "state": state, "error": err})
}

func (g *GoogleVoice) emit(event map[string]any) {
	data, err := json.Marshal(event)
	if err == nil && g.listener != nil {
		g.listener.OnEvent(string(data))
	}
}
