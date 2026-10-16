package hydbridge

import (
	"os"

	"github.com/rs/zerolog"
)

func init() {
	// messagix logs raw lightspeed data (including message text) in its decoder warnings; on a phone that
	// would end up in logcat, so only errors are logged.
	zerolog.SetGlobalLevel(zerolog.ErrorLevel)
}

// logWriter sends messagix logs to stderr, which Android forwards to logcat (tag "GoLog").
type logWriter struct{}

func (logWriter) Write(p []byte) (int, error) {
	return os.Stderr.Write(p)
}
