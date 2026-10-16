// Command smoke connects hydbridge on the desktop and summarizes the events, for testing without a device.
//
//	go run ./cmd/smoke <cookies.json>
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"sync"
	"time"

	"com.devvalor/howyoudoin/bridgemobile/hydbridge"
)

type collector struct {
	lock    sync.Mutex
	counts  map[string]int
	threads map[string]map[string]any
	states  []string
}

func (c *collector) OnEvent(data string) {
	var evt map[string]any
	_ = json.Unmarshal([]byte(data), &evt)
	c.lock.Lock()
	defer c.lock.Unlock()
	kind, _ := evt["type"].(string)
	c.counts[kind]++
	switch kind {
	case "thread":
		c.threads[evt["key"].(string)] = evt
	case "state":
		c.states = append(c.states, fmt.Sprint(evt["state"], " ", evt["error"]))
	}
}

func main() {
	raw, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	c := &collector{counts: map[string]int{}, threads: map[string]map[string]any{}}
	m, err := hydbridge.NewMessenger(string(raw), c)
	if err != nil {
		panic(err)
	}
	if err = m.Connect(); err != nil {
		panic(err)
	}
	time.Sleep(15 * time.Second)
	m.Disconnect()

	c.lock.Lock()
	defer c.lock.Unlock()
	fmt.Println("events:", c.counts)
	fmt.Println("states:", c.states)
	threads := make([]map[string]any, 0, len(c.threads))
	for _, t := range c.threads {
		threads = append(threads, t)
	}
	sort.Slice(threads, func(i, j int) bool {
		return threads[i]["lastActivityMs"].(float64) > threads[j]["lastActivityMs"].(float64)
	})
	for i, t := range threads {
		if i == 8 {
			break
		}
		fmt.Printf("thread name=%q group=%v encrypted=%v\n", t["name"], t["isGroup"], t["isEncrypted"])
	}
}
