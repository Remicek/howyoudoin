package hydbridge

import (
	"strconv"
	"strings"
)

// parseWaveform reads comma-separated loudness values (0–255) as the app records them.
func parseWaveform(csv string) []int {
	var values []int
	for _, part := range strings.Split(csv, ",") {
		if v, err := strconv.Atoi(strings.TrimSpace(part)); err == nil {
			values = append(values, min(max(v, 0), 255))
		}
	}
	return values
}

// scaleWaveform resamples a waveform to n values scaled to 0–top, as a network's voice notes store it.
func scaleWaveform(csv string, n, top int) []byte {
	values := parseWaveform(csv)
	if len(values) == 0 {
		return nil
	}
	out := make([]byte, n)
	for i := range out {
		v := values[i*len(values)/n]
		out[i] = byte(v * top / 255)
	}
	return out
}
