package hydbridge

import (
	"encoding/binary"
	"io"
	"os"
	"regexp"
	"strconv"

	"google.golang.org/protobuf/proto"
)

// optionalSeconds is a length for a message field, left out when unknown.
func optionalSeconds(seconds uint32) *uint32 {
	if seconds == 0 {
		return nil
	}
	return proto.Uint32(seconds)
}

// videoMeta is what the networks' apps need to show a sent video: its size as displayed (rotation applied) and its
// length. Messenger shows nothing for a video without them.
type videoMeta struct {
	width, height int
	seconds       uint32
}

// mp4Meta reads a video's display size and length from its MP4 / MOV boxes (moov → mvhd, trak → tkhd). Zero values
// where the file doesn't say or isn't MP4.
func mp4Meta(data []byte) videoMeta {
	var meta videoMeta
	var walk func(b []byte, depth int)
	walk = func(b []byte, depth int) {
		for len(b) >= 8 && depth < 8 {
			size := uint64(binary.BigEndian.Uint32(b))
			kind := string(b[4:8])
			header := uint64(8)
			if size == 1 && len(b) >= 16 {
				size, header = binary.BigEndian.Uint64(b[8:16]), 16
			} else if size == 0 {
				size = uint64(len(b))
			}
			if size < header || size > uint64(len(b)) {
				return
			}
			body := b[header:size]
			switch kind {
			case "moov", "trak":
				walk(body, depth+1)
			case "mvhd":
				meta.seconds = mvhdSeconds(body)
			case "tkhd":
				if w, h := tkhdSize(body); w > 0 && h > 0 && meta.width == 0 {
					meta.width, meta.height = w, h
				}
			}
			b = b[size:]
		}
	}
	walk(data, 0)
	return meta
}

// mvhdSeconds is the movie's length in whole seconds (at least 1 for a non-empty movie).
func mvhdSeconds(b []byte) uint32 {
	var timescale, duration uint64
	switch {
	case len(b) >= 32 && b[0] == 1:
		timescale, duration = uint64(binary.BigEndian.Uint32(b[20:24])), binary.BigEndian.Uint64(b[24:32])
	case len(b) >= 20 && b[0] == 0:
		timescale, duration = uint64(binary.BigEndian.Uint32(b[12:16])), uint64(binary.BigEndian.Uint32(b[16:20]))
	}
	if timescale == 0 || duration == 0 {
		return 0
	}
	return uint32(max(1, (duration+timescale/2)/timescale))
}

// tkhdSize is a track's display size, swapped for a track rotated by 90° or 270° (phones record portrait video
// as landscape with a rotation). Audio tracks are 0×0.
func tkhdSize(b []byte) (int, int) {
	// version/flags, times, track ID, reserved, duration, reserved, layer, group, volume, reserved, then the matrix
	matrixAt := 40
	if len(b) > 0 && b[0] == 1 {
		matrixAt = 52
	}
	if len(b) < matrixAt+36+8 {
		return 0, 0
	}
	w := int(binary.BigEndian.Uint32(b[matrixAt+36:]) >> 16)
	h := int(binary.BigEndian.Uint32(b[matrixAt+40:]) >> 16)
	a := int32(binary.BigEndian.Uint32(b[matrixAt:]))
	c := int32(binary.BigEndian.Uint32(b[matrixAt+4:]))
	if a == 0 && c != 0 {
		w, h = h, w
	}
	return w, h
}

// maxMetaBytes bounds how much of a video is read for its metadata: the moov box of a phone's recording is at the
// start, and a video at the download cap mustn't become a same-size allocation.
const maxMetaBytes = 4 << 20

// readVideoHead reads at most maxMetaBytes from the start of the file at path.
func readVideoHead(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	data := make([]byte, maxMetaBytes)
	n, err := io.ReadFull(f, data)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return nil, err
	}
	return data[:n], nil
}

// withVideoMeta fills an attachment event's missing size and length from the video file at path.
func withVideoMeta(event map[string]any, path string) {
	data, err := readVideoHead(path)
	if err != nil {
		return
	}
	video := mp4Meta(data)
	if w, _ := event["width"].(int); w <= 0 && video.width > 0 {
		event["width"], event["height"] = video.width, video.height
	}
	if _, ok := event["durationMs"]; !ok && video.seconds > 0 {
		event["durationMs"] = int64(video.seconds) * 1000
	}
}

var dashDuration = regexp.MustCompile(`mediaPresentationDuration="PT(?:([0-9.]+)H)?(?:([0-9.]+)M)?(?:([0-9.]+)S)?"`)

// dashDurationMs is a video's length from its DASH manifest (Instagram sends one instead of a length), or 0.
func dashDurationMs(manifest string) int {
	match := dashDuration.FindStringSubmatch(manifest)
	if match == nil {
		return 0
	}
	seconds := 0.0
	for i, scale := range []float64{3600, 60, 1} {
		if v, err := strconv.ParseFloat(match[i+1], 64); err == nil {
			seconds += v * scale
		}
	}
	return int(seconds * 1000)
}
