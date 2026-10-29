package hydbridge

import "testing"

func TestPhoneDigits(t *testing.T) {
	for query, want := range map[string]string{
		"+48 693 246 650":  "48693246650",
		"(555) 010-2030":   "5550102030",
		"+1-202-555-0123":  "12025550123",
		"Anna":             "",
		"12345":            "",
		"+48 693 246 65a0": "",
	} {
		if got := phoneDigits(query); got != want {
			t.Errorf("phoneDigits(%q) = %q, want %q", query, got, want)
		}
	}
}
