package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"regexp"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waArmadilloBackupMessage"
	"go.mau.fi/whatsmeow/proto/waMsgApplication"
	"google.golang.org/protobuf/proto"

	"go.mau.fi/mautrix-meta/pkg/messagix/securehistory"
)

var pinPattern = regexp.MustCompile(`^[0-9]{6}$`)

// HistoryStatus reports {"restored": bool, "backupAvailable": bool} for Messenger's PIN-protected backup of
// encrypted chats.
func (m *Messenger) HistoryStatus() (string, error) {
	restored := m.historyDevice() != nil
	available := restored
	if !restored {
		_, err := m.client.GetSecureHistoryClient().GetBackup(context.Background())
		if err != nil && !errors.Is(err, securehistory.ErrNoPINBackup) {
			return "", err
		}
		available = err == nil
	}
	data, _ := json.Marshal(map[string]bool{"restored": restored, "backupAvailable": available})
	return string(data), nil
}

// RestoreHistory unlocks the backup with the user's 6-digit PIN and enrolls this phone as a history device.
// Errors: "wrong_pin:<attempts left>", "pin_limit", "no_backup", or a description.
func (m *Messenger) RestoreHistory(pin string) error {
	if !pinPattern.MatchString(pin) {
		return errors.New("invalid_pin")
	}
	if m.historyDevice() != nil {
		return nil
	} else if m.device == nil || m.device.IdentityKey == nil {
		return errors.New("encrypted chats aren't connected yet")
	}
	ctx := context.Background()
	history := m.client.GetSecureHistoryClient()
	backup, err := history.GetBackup(ctx)
	if errors.Is(err, securehistory.ErrNoPINBackup) {
		return errors.New("no_backup")
	} else if err != nil {
		return err
	}
	recovery, err := history.RecoverPIN(ctx, backup, []byte(pin))
	if err != nil {
		var recoveryErr *securehistory.RecoveryError
		if errors.As(err, &recoveryErr) && recoveryErr.AttemptsRemaining != nil {
			if *recoveryErr.AttemptsRemaining == 0 {
				return errors.New("pin_limit")
			}
			return fmt.Errorf("wrong_pin:%d", *recoveryErr.AttemptsRemaining)
		}
		return err
	}
	virtual, err := history.GetVirtualDevice(ctx, backup, m.selfID, recovery.Code)
	if err != nil {
		return err
	}
	epochs, err := virtual.DeriveEpochs()
	if err != nil {
		return err
	}
	enrollment, err := virtual.PrepareDevice(backup.ID, history.RegistrationID, m.device.IdentityKey, epochs)
	if err != nil {
		return err
	}
	device, err := history.AddDevice(ctx, enrollment)
	if err != nil {
		return err
	}
	if err = m.saveHistoryDevice(device); err != nil {
		return err
	}
	m.emit(map[string]any{"type": "history", "restored": true})
	return nil
}

// FetchEncryptedHistory emits up to count decrypted messages of an encrypted chat older than beforeTimestampMs
// (0 = newest), then a range event with the next cursor and whether there's more.
func (m *Messenger) FetchEncryptedHistory(threadKey string, isGroup bool, beforeTimestampMs int64, count int) error {
	device := m.historyDevice()
	if device == nil {
		return errors.New("history isn't restored")
	}
	if beforeTimestampMs <= 0 {
		beforeTimestampMs = time.Now().Add(time.Minute).UnixMilli()
	}
	page, err := m.client.GetSecureHistoryClient().FetchMessages(context.Background(), device, threadKey, true, beforeTimestampMs, count)
	if err != nil {
		return err
	}
	if page.Epochs != nil && len(page.Epochs.Edges) > 0 {
		if device, err = device.WithEpochs(page.Epochs); err != nil {
			return err
		} else if err = m.saveHistoryDevice(device); err != nil {
			return err
		}
	}
	messages, err := page.Decrypt(device, threadKey, m.selfID, isGroup)
	if err != nil {
		return fmt.Errorf("failed to decrypt history: %w", err)
	}
	for _, message := range messages {
		m.emitArchiveMessage(threadKey, message)
	}
	hasMore := page.Range != nil && page.Range.HasMoreBefore != nil && *page.Range.HasMoreBefore
	next := int64(0)
	if page.Range != nil {
		next = page.Range.Before
	}
	m.emit(map[string]any{"type": "range", "threadKey": threadKey, "minTimestampMs": next, "hasMoreBefore": hasMore, "isEncrypted": true})
	return nil
}

func (m *Messenger) emitArchiveMessage(threadKey string, message *securehistory.DecryptedMessage) {
	if message == nil || message.Message == nil || message.Message.Message == nil {
		return
	}
	backup := message.Message.Message
	transport, ok := backup.GetPayload().(*waArmadilloBackupMessage.BackupMessage_EncryptedTransportMessage)
	if !ok {
		return
	}
	var application waMsgApplication.MessageApplication
	if proto.Unmarshal(transport.EncryptedTransportMessage, &application) != nil {
		return
	}
	decoded, err := whatsmeow.DecodeFBApplication(&application)
	if err != nil {
		return
	}
	metadata := backup.GetMetadata()
	paths := make(map[string]string, len(message.Attachments))
	for _, attachment := range message.Attachments {
		if attachment.ObjectID != "" && attachment.URL != "" {
			paths[attachment.ObjectID] = attachment.URL
		}
	}
	m.emitFBMessage(threadKey, metadata.GetMessageID(), metadata.GetSenderID(), message.Message.TimestampMS, decoded.GetConsumerApplication(), paths)
}

func (m *Messenger) historyDevicePath() string {
	return filepath.Join(m.dataDir, "history-device.json")
}

func (m *Messenger) historyDevice() *securehistory.Device {
	if m.dataDir == "" {
		return nil
	}
	data, err := readSecretFile(m.historyDevicePath())
	if err != nil {
		return nil
	}
	var device securehistory.Device
	if json.Unmarshal(data, &device) != nil || device.ID == "" {
		return nil
	}
	return &device
}

func (m *Messenger) saveHistoryDevice(device *securehistory.Device) error {
	data, err := json.Marshal(device)
	if err != nil {
		return err
	}
	return writeSecretFile(m.historyDevicePath(), data)
}
