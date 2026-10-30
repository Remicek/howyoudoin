package hydbridge

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strconv"
	"sync"

	"go.mau.fi/mautrix-telegram/pkg/gotd/session"
	"go.mau.fi/mautrix-telegram/pkg/gotd/telegram/updates"
)

// telegramStore keeps what Telegram needs across launches in one JSON file: the account, the update sequence
// (so messages that arrived while the app was closed are fetched on connect) and how to address chats (access
// hashes, which Telegram only hands out with chat lists and messages).
type telegramStore struct {
	path string
	lock sync.Mutex
	data telegramStoreData
}

type telegramStoreData struct {
	SelfID     int64                   `json:"selfId"`
	State      *updates.State          `json:"state,omitempty"`
	ChannelPts map[int64]int           `json:"channelPts"`
	Peers      map[string]telegramPeer `json:"peers"`
}

type telegramPeer struct {
	AccessHash int64  `json:"accessHash,omitempty"`
	Name       string `json:"name,omitempty"`
}

var errNoTelegramState = errors.New("no update state")

func loadTelegramStore(path string) *telegramStore {
	s := &telegramStore{path: path}
	if raw, err := readSecretFile(path); err == nil {
		_ = json.Unmarshal(raw, &s.data)
	}
	if s.data.ChannelPts == nil {
		s.data.ChannelPts = map[int64]int{}
	}
	if s.data.Peers == nil {
		s.data.Peers = map[string]telegramPeer{}
	}
	return s
}

// save writes the file; callers hold the lock.
func (s *telegramStore) save() {
	raw, err := json.Marshal(&s.data)
	if err != nil {
		return
	}
	_ = writeSecretFile(s.path, raw)
}

// telegramSessionStorage keeps Telegram's session (its auth key) as an encrypted login file; a session saved by
// gotd's FileStorage before is read as it is and encrypted on the next save.
type telegramSessionStorage struct{ path string }

func (s telegramSessionStorage) LoadSession(context.Context) ([]byte, error) {
	data, err := readSecretFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, session.ErrNotFound
	}
	return data, err
}

func (s telegramSessionStorage) StoreSession(_ context.Context, data []byte) error {
	return writeSecretFile(s.path, data)
}

func (s *telegramStore) update(f func(d *telegramStoreData)) {
	s.lock.Lock()
	defer s.lock.Unlock()
	f(&s.data)
	s.save()
}

func (s *telegramStore) selfID() int64 {
	s.lock.Lock()
	defer s.lock.Unlock()
	return s.data.SelfID
}

func (s *telegramStore) setSelfID(id int64) {
	s.update(func(d *telegramStoreData) { d.SelfID = id })
}

func (s *telegramStore) clear() {
	s.lock.Lock()
	defer s.lock.Unlock()
	s.data = telegramStoreData{ChannelPts: map[int64]int{}, Peers: map[string]telegramPeer{}}
	_ = os.Remove(s.path)
}

// setPeer records a chat's access hash (0 keeps a known one) and name; the file is only written when they change.
func (s *telegramStore) setPeer(key string, accessHash int64, name string) {
	s.lock.Lock()
	defer s.lock.Unlock()
	old := s.data.Peers[key]
	peer := old
	if accessHash != 0 {
		peer.AccessHash = accessHash
	}
	if name != "" {
		peer.Name = name
	}
	if peer != old {
		s.data.Peers[key] = peer
		s.save()
	}
}

func (s *telegramStore) accessHash(key string) (int64, bool) {
	s.lock.Lock()
	defer s.lock.Unlock()
	peer, ok := s.data.Peers[key]
	return peer.AccessHash, ok && peer.AccessHash != 0
}

func (s *telegramStore) peerName(key string) string {
	s.lock.Lock()
	defer s.lock.Unlock()
	return s.data.Peers[key].Name
}

// --- updates.StateStorage (only this account's state is kept) ---

func (s *telegramStore) GetState(_ context.Context, _ int64) (updates.State, bool, error) {
	s.lock.Lock()
	defer s.lock.Unlock()
	if s.data.State == nil {
		return updates.State{}, false, nil
	}
	return *s.data.State, true, nil
}

func (s *telegramStore) SetState(_ context.Context, _ int64, state updates.State) error {
	s.update(func(d *telegramStoreData) {
		d.State = &state
		d.ChannelPts = map[int64]int{}
	})
	return nil
}

func (s *telegramStore) setField(f func(st *updates.State)) error {
	s.lock.Lock()
	defer s.lock.Unlock()
	if s.data.State == nil {
		return errNoTelegramState
	}
	f(s.data.State)
	s.save()
	return nil
}

func (s *telegramStore) SetPts(_ context.Context, _ int64, pts int) error {
	return s.setField(func(st *updates.State) { st.Pts = pts })
}

func (s *telegramStore) SetQts(_ context.Context, _ int64, qts int) error {
	return s.setField(func(st *updates.State) { st.Qts = qts })
}

func (s *telegramStore) SetDate(_ context.Context, _ int64, date int) error {
	return s.setField(func(st *updates.State) { st.Date = date })
}

func (s *telegramStore) SetSeq(_ context.Context, _ int64, seq int) error {
	return s.setField(func(st *updates.State) { st.Seq = seq })
}

func (s *telegramStore) SetDateSeq(_ context.Context, _ int64, date, seq int) error {
	return s.setField(func(st *updates.State) { st.Date, st.Seq = date, seq })
}

func (s *telegramStore) GetChannelPts(_ context.Context, _, channelID int64) (int, bool, error) {
	s.lock.Lock()
	defer s.lock.Unlock()
	pts, ok := s.data.ChannelPts[channelID]
	return pts, ok, nil
}

func (s *telegramStore) SetChannelPts(_ context.Context, _, channelID int64, pts int) error {
	s.update(func(d *telegramStoreData) { d.ChannelPts[channelID] = pts })
	return nil
}

func (s *telegramStore) ForEachChannels(ctx context.Context, _ int64, f func(ctx context.Context, channelID int64, pts int) error) error {
	s.lock.Lock()
	channels := make(map[int64]int, len(s.data.ChannelPts))
	for id, pts := range s.data.ChannelPts {
		channels[id] = pts
	}
	s.lock.Unlock()
	for id, pts := range channels {
		if err := f(ctx, id, pts); err != nil {
			return err
		}
	}
	return nil
}

// --- updates.AccessHasher ---

func (s *telegramStore) SetChannelAccessHash(_ context.Context, _, channelID, accessHash int64) error {
	s.setPeer("c"+strconv.FormatInt(channelID, 10), accessHash, "")
	return nil
}

func (s *telegramStore) GetChannelAccessHash(_ context.Context, _, channelID int64) (int64, bool, error) {
	hash, ok := s.accessHash("c" + strconv.FormatInt(channelID, 10))
	return hash, ok, nil
}

func (s *telegramStore) SetUserAccessHash(_ context.Context, _, userID, accessHash int64) error {
	s.setPeer("u"+strconv.FormatInt(userID, 10), accessHash, "")
	return nil
}

func (s *telegramStore) GetUserAccessHash(_ context.Context, _, userID int64) (int64, bool, error) {
	hash, ok := s.accessHash("u" + strconv.FormatInt(userID, 10))
	return hash, ok, nil
}
