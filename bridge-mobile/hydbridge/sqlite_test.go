package hydbridge

import (
	"bytes"
	"database/sql"
	"os"
	"path/filepath"
	"testing"

	"github.com/rs/zerolog"
)

// A plain database (with rows still in its write-ahead log) is encrypted in place the first time it's opened with a
// key, keeps its data, and no longer opens without the key.
func TestSQLiteIsEncryptedInPlace(t *testing.T) {
	path := filepath.Join(t.TempDir(), "whatsapp.db")
	plain, err := sql.Open("sqlite3", "file:"+path+"?_journal_mode=WAL")
	if err != nil {
		t.Fatal(err)
	}
	for _, q := range []string{"CREATE TABLE device (jid TEXT)", "INSERT INTO device VALUES ('1@s.whatsapp.net')"} {
		if _, err := plain.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	// Left open, so the row is still in the WAL file

	if err := SetStorageKey(bytes.Repeat([]byte{3}, 32)); err != nil {
		t.Fatal(err)
	}
	defer storageKey.Store(nil)
	defer storageCipher.Store(nil)
	_ = plain.Close()

	address := sqliteAddress(path, zerolog.Nop())
	if isPlainSQLite(path) {
		t.Fatal("the database is still plain")
	}
	db, err := sql.Open("sqlite3", address)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var jid string
	if err := db.QueryRow("SELECT jid FROM device").Scan(&jid); err != nil || jid != "1@s.whatsapp.net" {
		t.Fatalf("after encrypting: %q %v", jid, err)
	}
	raw, _ := os.ReadFile(path)
	if bytes.Contains(raw, []byte("whatsapp.net")) {
		t.Fatal("the data is readable in the file")
	}
	unkeyed, _ := sql.Open("sqlite3", "file:"+path)
	defer unkeyed.Close()
	if err := unkeyed.QueryRow("SELECT jid FROM device").Scan(&jid); err == nil {
		t.Fatal("the database opened without the key")
	}
	if _, err := os.Stat(path + ".encrypting"); !os.IsNotExist(err) {
		t.Fatal("the temporary copy was left behind")
	}
}

// Without a storage key (tests, iOS so far), databases stay plain SQLite.
func TestSQLiteWithoutKeyStaysPlain(t *testing.T) {
	path := filepath.Join(t.TempDir(), "signal.db")
	db, err := sql.Open("sqlite3", sqliteAddress(path, zerolog.Nop()))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("CREATE TABLE x (y INTEGER)"); err != nil {
		t.Fatal(err)
	}
	_ = db.Close()
	if !isPlainSQLite(path) {
		t.Fatal("expected a plain database")
	}
}
