package hydbridge

import (
	"bytes"
	"image"
	_ "image/jpeg" // registers the JPEG decoder for image.DecodeConfig
	"os"
)

// videoThumbnail is a video's preview picture, which the app writes beside the video it sends.
type videoThumbnail struct {
	jpeg          []byte
	width, height int
}

// maxVideoThumbnailBytes bounds the preview, which travels inside the message itself.
const maxVideoThumbnailBytes = 256 << 10

// takeVideoThumbnail reads the JPEG the app wrote at mediaPath + ".thumb.jpg" (see the app's thumbnailPathOf) and
// its size. The app removes the file once the send returns. Empty when there is none or it isn't a usable JPEG.
func takeVideoThumbnail(mediaPath string) videoThumbnail {
	data, err := os.ReadFile(mediaPath + ".thumb.jpg")
	if err != nil || len(data) == 0 || len(data) > maxVideoThumbnailBytes {
		return videoThumbnail{}
	}
	config, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil || format != "jpeg" || config.Width <= 0 || config.Height <= 0 {
		return videoThumbnail{}
	}
	return videoThumbnail{jpeg: data, width: config.Width, height: config.Height}
}
