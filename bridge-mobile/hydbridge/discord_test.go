package hydbridge

import (
	"errors"
	"strings"
	"testing"

	"github.com/bwmarrin/discordgo"
)

func TestDiscordCaptcha(t *testing.T) {
	d := &Discord{}
	body := `{"captcha_key":["captcha-required"],"captcha_sitekey":"site","captcha_service":"hcaptcha",` +
		`"captcha_session_id":"session","captcha_rqdata":"data","captcha_rqtoken":"token"}`
	err := d.checkCaptcha(&discordgo.RESTError{ResponseBody: []byte(body)})
	if !errors.Is(err, errCaptchaRequired) {
		t.Fatalf("checkCaptcha = %v, want a captcha_required error", err)
	}
	if want := `captcha_required:{"pageUrl":"https://discord.com/","rqData":"data","service":"hcaptcha","siteKey":"site"}`; err.Error() != want {
		t.Errorf("error = %s, want %s", err, want)
	}
	if options := d.captchaOptions(); options != nil {
		t.Errorf("headers before the answer: %d", len(options))
	}
	if err := d.AnswerCaptcha("answer"); err != nil {
		t.Fatal(err)
	}
	if options := d.captchaOptions(); len(options) != 3 {
		t.Errorf("headers with the answer: %d, want key, rqtoken and session", len(options))
	}
	// The answer goes with one request only
	if options := d.captchaOptions(); options != nil {
		t.Errorf("headers sent twice")
	}

	other := &discordgo.RESTError{ResponseBody: []byte(`{"message":"Cannot send messages to this user","code":50007}`)}
	if err := d.checkCaptcha(other); err != other {
		t.Errorf("other errors change: %v", err)
	}
	if err := d.AnswerCaptcha("answer"); err == nil || !strings.Contains(err.Error(), "didn't ask") {
		t.Errorf("an answer nobody asked for: %v", err)
	}
}

func TestDiscordVerification(t *testing.T) {
	listener := &recordingListener{}
	d := &Discord{listener: listener}
	unverified := &discordgo.RESTError{Message: &discordgo.APIErrorMessage{Code: discordUnverifiedCode}}

	d.noteVerification(false, unverified)
	d.noteVerification(false, unverified)
	if !d.needsVerification.Load() || len(listener.events) != 1 {
		t.Fatalf("after refusals: %v, events %v", d.needsVerification.Load(), listener.events)
	}
	if err := d.SetTyping("channel", true); err != nil {
		t.Errorf("typing while unverified: %v", err)
	}
	// Other errors leave it as it is; a sent message clears it
	d.noteVerification(false, errors.New("offline"))
	d.noteVerification(true, nil)
	if d.needsVerification.Load() || len(listener.events) != 2 {
		t.Errorf("after sending: %v, events %v", d.needsVerification.Load(), listener.events)
	}
	if event := listener.events[1]; event["type"] != "account" || event["needsVerification"] != false {
		t.Errorf("event = %v", event)
	}
}
