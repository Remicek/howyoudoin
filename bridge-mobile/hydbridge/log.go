package hydbridge

import "os"

// logWriter sends messagix logs to stderr, which Android forwards to logcat (tag "GoLog").
type logWriter struct{}

func (logWriter) Write(p []byte) (int, error) {
	return os.Stderr.Write(p)
}
