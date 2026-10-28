package hydbridge

import (
	"os"
	"strings"
	"testing"
)

// Against the real discord.com with a token that can't work: the login must fail as an expired session.
// Runs only with HYD_LIVE=1.
func TestDiscordLiveBadToken(t *testing.T) {
	if os.Getenv("HYD_LIVE") != "1" {
		t.Skip("set HYD_LIVE=1 to call the real Discord server")
	}
	d, err := NewDiscord(`{"token":"MTAwMDAwMDAwMDAwMDAwMDAwMA.GaBcDe.ThisIsNotARealDiscordTokenAtAll0000000"}`,
		t.TempDir(), &recordingListener{})
	if err != nil {
		t.Fatal(err)
	}
	err = d.Connect()
	if err == nil || !strings.Contains(err.Error(), discordAuthFailedText) {
		t.Fatalf("got %v", err)
	}
}

func TestDiscordTimesFromIDs(t *testing.T) {
	// Discord's documented example: 175928847299117063 was made at 2016-04-30 11:18:25.796 UTC
	if got := discordMillis("175928847299117063"); got != 1462015105796 {
		t.Fatalf("got %d", got)
	}
}

func TestDiscordRefusesBotTokens(t *testing.T) {
	if _, err := NewDiscord(`{"token":"Bot abc"}`, t.TempDir(), nil); err == nil {
		t.Fatal("a bot token was accepted")
	}
}
