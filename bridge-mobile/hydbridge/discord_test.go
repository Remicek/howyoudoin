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
