package hydbridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/rs/zerolog"

	_ "github.com/mattn/go-sqlite3" // SQLCipher (go.mod replaces the driver with thinkgos/go-sqlcipher)
)

// The WhatsApp, Signal and Messenger stores are SQLite databases encrypted with SQLCipher, keyed from the storage
// key (files.go). A plain database from before is encrypted the first time it's opened with a key.

var sqlitePlainHeader = []byte("SQLite format 3\x00")

// sqliteKeyHex is the databases' SQLCipher key (hex), derived from the storage key; "" without one.
func sqliteKeyHex() string {
	key := storageKey.Load()
	if key == nil {
		return ""
	}
	sum := sha256.Sum256(append([]byte("hydbridge sqlite\x00"), *key...))
	return hex.EncodeToString(sum[:])
}

// sqliteAddress is the address to open the database at path with: encrypted when there's a storage key, converting
// a plain database first. If converting fails the plain one keeps being used, so nothing stops working.
func sqliteAddress(path string, log zerolog.Logger) string {
	address := "file:" + path + "?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=5000"
	key := sqliteKeyHex()
	if key == "" {
		return address
	}
	if isPlainSQLite(path) {
		if err := encryptSQLite(path, key); err != nil {
			log.Error().Err(err).Str("db", path).Msg("Failed to encrypt the database; it stays as it was")
			return address
		}
	}
	return address + "&_pragma_key=x'" + key + "'"
}

func isPlainSQLite(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	header := make([]byte, len(sqlitePlainHeader))
	_, err = io.ReadFull(f, header)
	return err == nil && bytes.Equal(header, sqlitePlainHeader)
}

// encryptSQLite copies the plain database at path into an encrypted one, checks it opens with the key, and puts it
// in place of the plain one (which is deleted).
func encryptSQLite(path, key string) error {
	ctx := context.Background()
	tmp := path + ".encrypting"
	_ = os.Remove(tmp)
	if err := exportEncrypted(ctx, path, tmp, key); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := checkEncrypted(ctx, tmp, key); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	// The plain database's write-ahead log was folded in before the export; its files mustn't pair with the new one
	for _, suffix := range []string{"-wal", "-shm"} {
		if err := os.Remove(path + suffix); err != nil && !errors.Is(err, os.ErrNotExist) {
			_ = os.Remove(tmp)
			return err
		}
	}
	return os.Rename(tmp, path)
}

func exportEncrypted(ctx context.Context, path, target, key string) error {
	db, err := sql.Open("sqlite3", "file:"+path+"?_busy_timeout=5000")
	if err != nil {
		return err
	}
	defer db.Close()
	// ATTACH is per connection: one connection for all of it
	conn, err := db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	steps := []string{
		"PRAGMA wal_checkpoint(TRUNCATE)",
		fmt.Sprintf("ATTACH DATABASE '%s' AS encrypted KEY \"x'%s'\"", target, key),
		"SELECT sqlcipher_export('encrypted')",
		"DETACH DATABASE encrypted",
	}
	for _, step := range steps {
		if _, err := conn.ExecContext(ctx, step); err != nil {
			return fmt.Errorf("%s: %w", step[:min(len(step), 24)], err)
		}
	}
	return nil
}

func checkEncrypted(ctx context.Context, path, key string) error {
	if isPlainSQLite(path) {
		return errors.New("the copy isn't encrypted")
	}
	db, err := sql.Open("sqlite3", "file:"+path+"?_pragma_key=x'"+key+"'")
	if err != nil {
		return err
	}
	defer db.Close()
	var tables int
	return db.QueryRowContext(ctx, "SELECT count(*) FROM sqlite_master").Scan(&tables)
}
