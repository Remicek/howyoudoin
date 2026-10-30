package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"

	"github.com/rs/zerolog"
	"go.mau.fi/util/dbutil"
	"maunium.net/go/mautrix"
	"maunium.net/go/mautrix/crypto"
	"maunium.net/go/mautrix/crypto/backup"
	"maunium.net/go/mautrix/crypto/cryptohelper"
	"maunium.net/go/mautrix/crypto/ssss"
	"maunium.net/go/mautrix/event"
	mxid "maunium.net/go/mautrix/id"
)

// MatrixCrypto is end-to-end encryption for a Matrix account whose /sync runs in the app (Kotlin): the app hands
// every sync response to ProcessSync, decrypts room events with Decrypt and encrypts what it sends with Encrypt.
// Keys live in a SQLite database of their own, one per Matrix device.
type MatrixCrypto struct {
	client *mautrix.Client
	syncer *mautrix.DefaultSyncer
	helper *cryptohelper.CryptoHelper
	ctx    context.Context
	cancel context.CancelFunc

	// Rooms whose full member list was loaded (the app's sync loads members lazily)
	membersLoaded sync.Map
}

// NewMatrixCrypto opens (or creates) the keys of this device and uploads its keys if they aren't on the server yet.
func NewMatrixCrypto(homeserver, userID, deviceID, accessToken, dbPath, pickleKey string) (*MatrixCrypto, error) {
	client, err := mautrix.NewClient(homeserver, mxid.UserID(userID), accessToken)
	if err != nil {
		return nil, err
	}
	client.DeviceID = mxid.DeviceID(deviceID)
	client.Log = zerolog.New(logWriter{}).With().Str("component", "matrix-crypto").Logger()
	syncer := mautrix.NewDefaultSyncer()
	client.Syncer = syncer

	db, err := dbutil.NewWithDialect(sqliteAddress(dbPath, client.Log)+"&_txlock=immediate", "sqlite3")
	if err != nil {
		return nil, err
	}
	helper, err := cryptohelper.NewCryptoHelper(client, []byte(pickleKey), db)
	if err != nil {
		_ = db.Close()
		return nil, err
	}
	// The app decrypts events itself; the helper only waits for missing keys and asks other devices for them
	helper.CustomPostDecrypt = func(context.Context, *event.Event) {}
	helper.DecryptErrorCallback = func(*event.Event, error) {}

	ctx, cancel := context.WithCancel(context.Background())
	if err = helper.Init(ctx); err != nil {
		cancel()
		_ = helper.Close()
		return nil, err
	}
	return &MatrixCrypto{client: client, syncer: syncer, helper: helper, ctx: ctx, cancel: cancel}, nil
}

// ProcessSync takes a /sync response (raw JSON): keys sent to this device, device list changes, one-time key
// counts and room state (encryption settings, members).
func (c *MatrixCrypto) ProcessSync(syncJSON, since string) error {
	var resp mautrix.RespSync
	if err := json.Unmarshal([]byte(syncJSON), &resp); err != nil {
		return err
	}
	ctx := context.WithValue(c.ctx, mautrix.SyncTokenContextKey, since)
	return c.syncer.ProcessResponse(ctx, &resp, since)
}

// Decrypt decrypts an m.room.encrypted event (raw JSON); returns {"type": …, "content": …} of the original event.
func (c *MatrixCrypto) Decrypt(roomID, eventJSON string) (string, error) {
	var evt event.Event
	if err := json.Unmarshal([]byte(eventJSON), &evt); err != nil {
		return "", err
	}
	evt.RoomID = mxid.RoomID(roomID)
	if err := evt.Content.ParseRaw(evt.Type); err != nil {
		return "", err
	}
	decrypted, err := c.helper.Decrypt(c.ctx, &evt)
	if errors.Is(err, crypto.ErrNoSessionFound) {
		return "", fmt.Errorf("%s: %w", MissingKeysPrefix, err)
	} else if err != nil {
		return "", err
	}
	out, err := json.Marshal(map[string]any{
		"type":    decrypted.Type.Type,
		"content": json.RawMessage(decrypted.Content.VeryRaw),
	})
	return string(out), err
}

// MissingKeysPrefix starts a Decrypt error that only means the room key hasn't arrived (yet).
const MissingKeysPrefix = "missing keys"

// Encrypt encrypts an event's content for everyone in the room; returns the m.room.encrypted content (raw JSON).
func (c *MatrixCrypto) Encrypt(roomID, eventType, contentJSON string) (string, error) {
	room := mxid.RoomID(roomID)
	if _, loaded := c.membersLoaded.Load(room); !loaded {
		// Fills the state store with the full member list, which the room key is shared with
		if _, err := c.client.Members(c.ctx, room); err != nil {
			return "", fmt.Errorf("failed to load room members: %w", err)
		}
		c.membersLoaded.Store(room, true)
	}
	var content event.Content
	if err := json.Unmarshal([]byte(contentJSON), &content); err != nil {
		return "", err
	}
	evtType := event.Type{Type: eventType, Class: event.MessageEventType}
	if err := content.ParseRaw(evtType); err != nil && !errors.Is(err, event.ErrUnsupportedContentType) {
		return "", err
	}
	encrypted, err := c.helper.Encrypt(c.ctx, room, evtType, &content)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(encrypted)
	return string(out), err
}

// MembersChanged forgets the loaded member list of a room, so the next Encrypt loads it again.
func (c *MatrixCrypto) MembersChanged(roomID string) {
	c.membersLoaded.Delete(mxid.RoomID(roomID))
}

// IsVerified tells whether this device is signed by the account's cross-signing key (verified), which other
// people's apps may require before they share room keys with it.
func (c *MatrixCrypto) IsVerified() bool {
	mach := c.helper.Machine()
	// The account's cross-signing keys and the signatures on this device, as the server has them now
	if _, err := mach.FetchKeys(c.ctx, []mxid.UserID{c.client.UserID}, true); err != nil {
		return false
	}
	// OwnIdentity is always marked trusted locally; what counts is the cross-signing signature
	own := *mach.OwnIdentity()
	own.Trust = mxid.TrustStateUnset
	trust, err := mach.ResolveTrustContext(c.ctx, &own)
	return err == nil && trust >= mxid.TrustStateCrossSignedTOFU
}

// VerifyWithRecoveryKey verifies this device with the account's recovery key (from Element: "Recovery key") and
// restores the room keys from the server's key backup, so older encrypted messages can be read too. Returns how
// many room keys were restored.
func (c *MatrixCrypto) VerifyWithRecoveryKey(recoveryKey string) (int, error) {
	mach := c.helper.Machine()
	keyID, keyData, err := mach.SSSS.GetDefaultKeyData(c.ctx)
	if err != nil {
		return 0, fmt.Errorf("this account has no recovery key set up: %w", err)
	}
	key, err := keyData.VerifyRecoveryKey(keyID, recoveryKey)
	if err != nil && !errors.Is(err, ssss.ErrUnverifiableKey) {
		return 0, fmt.Errorf("wrong recovery key: %w", err)
	}
	if err = mach.VerifyWithRecoveryKey(c.ctx, recoveryKey); err != nil {
		return 0, err
	}
	return c.restoreKeyBackup(mach, key)
}

func (c *MatrixCrypto) restoreKeyBackup(mach *crypto.OlmMachine, key *ssss.Key) (int, error) {
	secret, err := mach.SSSS.GetDecryptedAccountData(c.ctx, event.AccountDataMegolmBackupKey, key)
	if err != nil {
		// Verified, but there's no key backup (or it isn't in secret storage): only new messages can be read
		return 0, nil
	}
	backupKey, err := backup.MegolmBackupKeyFromBytes(secret)
	if err != nil {
		return 0, fmt.Errorf("invalid key backup key: %w", err)
	}
	before := c.countSessions()
	if _, err = mach.DownloadAndStoreLatestKeyBackup(c.ctx, backupKey); err != nil {
		return 0, fmt.Errorf("failed to restore key backup: %w", err)
	}
	return c.countSessions() - before, nil
}

func (c *MatrixCrypto) countSessions() int {
	store, ok := c.helper.Machine().CryptoStore.(*crypto.SQLCryptoStore)
	if !ok {
		return 0
	}
	var count int
	_ = store.DB.QueryRow(c.ctx, "SELECT COUNT(*) FROM crypto_megolm_inbound_session WHERE account_id=$1", store.AccountID).Scan(&count)
	return count
}

// Close stops background work and closes the key database.
func (c *MatrixCrypto) Close() {
	c.cancel()
	_ = c.helper.Close()
}
