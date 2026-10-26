package main

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/jpeg"
	"testing"
)

// jpegWithOrientation encodes a 40x20 image with a red top-left quarter,
// tagged with the given EXIF orientation
func jpegWithOrientation(t *testing.T, orient int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 40, 20))
	for y := range 20 {
		for x := range 40 {
			c := color.RGBA{0, 0, 255, 255}
			if x < 20 && y < 10 {
				c = color.RGBA{255, 0, 0, 255}
			}
			img.Set(x, y, c)
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 95}); err != nil {
		t.Fatal(err)
	}

	// APP1: "Exif\0\0", big-endian TIFF header, IFD0 with one Orientation entry
	tiff := []byte("MM\x00\x2a\x00\x00\x00\x08\x00\x01\x01\x12\x00\x03\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\x00\x00")
	binary.BigEndian.PutUint16(tiff[18:], uint16(orient))
	seg := append([]byte("Exif\x00\x00"), tiff...)
	app1 := []byte{0xff, 0xe1, 0, 0}
	binary.BigEndian.PutUint16(app1[2:], uint16(len(seg)+2))
	data := buf.Bytes()
	return append(append(append([]byte{}, data[:2]...), append(app1, seg...)...), data[2:]...)
}

func isRed(img *image.RGBA, x, y int) bool {
	c := img.RGBAAt(x, y)
	return c.R > 200 && c.B < 60
}

func TestExifOrientation(t *testing.T) {
	for orient := 1; orient <= 8; orient++ {
		if got := exifOrientation(jpegWithOrientation(t, orient)); got != orient {
			t.Errorf("orientation %d: read %d", orient, got)
		}
	}
	if got := exifOrientation([]byte("not a jpeg")); got != 1 {
		t.Errorf("non-JPEG: read %d, want 1", got)
	}
}

func TestRenderOrientation(t *testing.T) {
	// Where the red quarter should end up on an upright 40x40 frame. Upright
	// size is 40x20 (orientations 1-4) or 20x40 (5-8); with contain on a
	// square frame that's centred, so sample well inside each quadrant.
	tests := []struct {
		orient int
		x, y   int
	}{
		{1, 5, 15},  // top-left
		{2, 35, 15}, // mirrored: top-right
		{3, 35, 25}, // 180°: bottom-right
		{4, 5, 25},  // flipped: bottom-left
		{5, 15, 5},  // transposed: top-left
		{6, 25, 5},  // 90° clockwise: top-right
		{7, 25, 35}, // transverse: bottom-right
		{8, 15, 35}, // 90° anticlockwise: bottom-left
	}
	for _, tt := range tests {
		frame, err := Render(jpegWithOrientation(t, tt.orient), 40, 40, 0, false)
		if err != nil {
			t.Fatal(err)
		}
		if !isRed(frame, tt.x, tt.y) {
			t.Errorf("orientation %d: red quarter not at (%d, %d)", tt.orient, tt.x, tt.y)
		}
	}
}

func TestRenderRotate(t *testing.T) {
	// A 40x20 panel turned 90° shows a 20x40 logical screen, which the photo
	// covers; its top-left lands at the panel's top-right
	frame, err := Render(jpegWithOrientation(t, 1), 40, 20, 90, true)
	if err != nil {
		t.Fatal(err)
	}
	if !isRed(frame, 30, 5) || isRed(frame, 10, 5) {
		t.Error("rotate 90: red quarter not at the top-right")
	}
}

func TestPickDerivative(t *testing.T) {
	p := Photo{Derivatives: []Derivative{
		{Checksum: "thumb", Width: 342, Height: 256}, {Checksum: "mid", Width: 1600, Height: 1200},
		{Checksum: "full", Width: 4032, Height: 3024},
	}}
	if got := PickDerivative(p, 1280, 800, true).Checksum; got != "mid" {
		t.Errorf("cover 1280x800: got %s, want mid", got)
	}
	if got := PickDerivative(p, 3840, 2160, true).Checksum; got != "full" {
		t.Errorf("cover 3840x2160: got %s, want full", got)
	}
	if got := PickDerivative(p, 8000, 8000, true).Checksum; got != "full" {
		t.Errorf("too big for all: got %s, want largest", got)
	}
}

func TestParseAlbumID(t *testing.T) {
	for raw, want := range map[string]string{
		"B0abc123": "B0abc123",
		"https://www.icloud.com/sharedalbum/#B0abc123": "B0abc123",
		" B0abc123\n": "B0abc123",
		"https://www.icloud.com/sharedalbum/#B0abc123;ABCDEF12": "B0abc123",
		"B2a_long-Token99": "B2a_long-Token99",
		"https://photos.icloud.com/shared/album/0AbCdEfGhIjKlMnOpQrStUvWx/": "0AbCdEfGhIjKlMnOpQrStUvWx",
		"https://photos.icloud.com/shared/album/0AbCdEfGhIjKlMnOpQrStUvWx":  "0AbCdEfGhIjKlMnOpQrStUvWx",
	} {
		if got, err := ParseAlbumID(raw); err != nil || got != want {
			t.Errorf("ParseAlbumID(%q) = %q, %v", raw, got, err)
		}
	}
	for _, raw := range []string{"", "short", "../etc/passwd", "B0abc123?x=1"} {
		if _, err := ParseAlbumID(raw); err == nil {
			t.Errorf("ParseAlbumID(%q) accepted", raw)
		}
	}
}
