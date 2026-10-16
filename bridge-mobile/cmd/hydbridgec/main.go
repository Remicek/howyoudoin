// Command hydbridgec exposes hydbridge as a plain C library (go build -buildmode=c-archive) for Kotlin/Native on
// iOS, whose cinterop can't read gomobile's Objective-C headers with current Xcode SDKs. Clients are handles;
// methods are called by name with JSON arguments, and events arrive through a C callback.
package main

/*
#include <stdint.h>
#include <stdlib.h>

typedef void (*hyd_event_cb)(void *ctx, const char *json);

static inline void hyd_call_event_cb(hyd_event_cb cb, void *ctx, const char *json) {
	cb(ctx, json);
}
*/
import "C"

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"unsafe"

	"com.devvalor/howyoudoin/bridgemobile/hydbridge"
)

type callbackListener struct {
	cb  C.hyd_event_cb
	ctx unsafe.Pointer
}

func (l *callbackListener) OnEvent(data string) {
	cs := C.CString(data)
	defer C.free(unsafe.Pointer(cs))
	C.hyd_call_event_cb(l.cb, l.ctx, cs)
}

var (
	clientsLock sync.Mutex
	clients     = map[int64]*hydbridge.Messenger{}
	nextHandle  int64
)

func setError(out **C.char, err error) {
	if out != nil && err != nil {
		*out = C.CString(err.Error())
	}
}

//export hyd_new
func hyd_new(cookiesJSON, dataDir *C.char, cb C.hyd_event_cb, ctx unsafe.Pointer, errOut **C.char) C.int64_t {
	m, err := hydbridge.NewMessenger(C.GoString(cookiesJSON), C.GoString(dataDir), &callbackListener{cb: cb, ctx: ctx})
	if err != nil {
		setError(errOut, err)
		return 0
	}
	clientsLock.Lock()
	defer clientsLock.Unlock()
	nextHandle++
	clients[nextHandle] = m
	return C.int64_t(nextHandle)
}

type callArgs struct {
	ThreadKey         string `json:"threadKey"`
	Text              string `json:"text"`
	IsGroup           bool   `json:"isGroup"`
	IsEncrypted       bool   `json:"isEncrypted"`
	IsTyping          bool   `json:"isTyping"`
	BeforeTimestampMs int64  `json:"beforeTimestampMs"`
	BeforeMessageID   string `json:"beforeMessageId"`
	MessageID         string `json:"messageId"`
	Emoji             string `json:"emoji"`
	SenderID          string `json:"senderId"`
	Pin               string `json:"pin"`
	Count             int    `json:"count"`
}

// hyd_call runs method with JSON args and returns a string result (caller frees it with hyd_free), or NULL with
// *errOut set on failure.
//
//export hyd_call
func hyd_call(handle C.int64_t, method, argsJSON *C.char, errOut **C.char) *C.char {
	clientsLock.Lock()
	m := clients[int64(handle)]
	clientsLock.Unlock()
	if m == nil {
		setError(errOut, errors.New("unknown client"))
		return nil
	}
	var a callArgs
	if raw := C.GoString(argsJSON); raw != "" {
		if err := json.Unmarshal([]byte(raw), &a); err != nil {
			setError(errOut, fmt.Errorf("invalid arguments: %w", err))
			return nil
		}
	}
	var result string
	var err error
	switch name := C.GoString(method); name {
	case "connect":
		err = m.Connect()
	case "disconnect":
		m.Disconnect()
	case "sendText":
		result, err = m.SendText(a.ThreadKey, a.Text)
	case "sendEncryptedText":
		result, err = m.SendEncryptedText(a.ThreadKey, a.IsGroup, a.Text)
	case "requestOlder":
		err = m.RequestOlder(a.ThreadKey, a.BeforeTimestampMs, a.BeforeMessageID)
	case "historyStatus":
		result, err = m.HistoryStatus()
	case "restoreHistory":
		err = m.RestoreHistory(a.Pin)
	case "fetchEncryptedHistory":
		err = m.FetchEncryptedHistory(a.ThreadKey, a.IsGroup, a.BeforeTimestampMs, a.Count)
	case "sendReaction":
		err = m.SendReaction(a.ThreadKey, a.MessageID, a.Emoji)
	case "markRead":
		err = m.MarkRead(a.ThreadKey, a.IsEncrypted, a.IsGroup, a.MessageID, a.SenderID)
	case "setTyping":
		err = m.SetTyping(a.ThreadKey, a.IsEncrypted, a.IsGroup, a.IsTyping)
	default:
		err = fmt.Errorf("unknown method %q", name)
	}
	if err != nil {
		setError(errOut, err)
		return nil
	}
	return C.CString(result)
}

//export hyd_release
func hyd_release(handle C.int64_t) {
	clientsLock.Lock()
	defer clientsLock.Unlock()
	delete(clients, int64(handle))
}

//export hyd_free
func hyd_free(s *C.char) {
	C.free(unsafe.Pointer(s))
}

func main() {}
