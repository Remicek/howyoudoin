package hydbridge

import (
	"bytes"
	"image"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"testing"
)

func TestTakeVideoThumbnailReadsTheJPEGBesideTheVideo(t *testing.T) {
	video := filepath.Join(t.TempDir(), "outgoing.tmp")
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, image.NewRGBA(image.Rect(0, 0, 225, 400)), nil); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(video+".thumb.jpg", buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	thumb := takeVideoThumbnail(video)
	if !bytes.Equal(thumb.jpeg, buf.Bytes()) || thumb.width != 225 || thumb.height != 400 {
		t.Fatalf("got %d bytes, %dx%d", len(thumb.jpeg), thumb.width, thumb.height)
	}
}

func TestTakeVideoThumbnailIgnoresAMissingOrNonJPEGFile(t *testing.T) {
	video := filepath.Join(t.TempDir(), "outgoing.tmp")
	if thumb := takeVideoThumbnail(video); thumb.jpeg != nil {
		t.Fatal("expected no thumbnail without the file")
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewRGBA(image.Rect(0, 0, 4, 4))); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(video+".thumb.jpg", buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	if thumb := takeVideoThumbnail(video); thumb.jpeg != nil {
		t.Fatal("expected a PNG to be ignored")
	}
}
