package main

import (
	"bytes"
	"encoding/binary"
	"image"
	_ "image/jpeg"
	_ "image/png"

	"golang.org/x/image/draw"
	"golang.org/x/image/math/f64"
)

// Render decodes a photo and draws it onto a w x h frame, applying EXIF
// orientation, the fit, and the screen rotation in one resampling pass.
// cover crops to fill the frame; otherwise the photo is letterboxed in black.
func Render(data []byte, w, h, rotate int, cover bool) (*image.RGBA, error) {
	src, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	b := src.Bounds()
	sw, sh := float64(b.Dx()), float64(b.Dy())

	// Source pixel -> upright photo (u, v)
	orient := exifOrientation(data)
	m := orientationMatrix(orient, sw, sh)
	uw, uh := sw, sh
	if orient >= 5 {
		uw, uh = sh, sw
	}

	// Upright photo -> logical screen, which is the panel turned by rotate
	lw, lh := float64(w), float64(h)
	if rotate == 90 || rotate == 270 {
		lw, lh = lh, lw
	}
	scale := min(lw/uw, lh/uh)
	if cover {
		scale = max(lw/uw, lh/uh)
	}
	fit := f64.Aff3{scale, 0, (lw - uw*scale) / 2, 0, scale, (lh - uh*scale) / 2}
	m = mul(fit, m)

	// Logical screen -> physical panel pixels
	m = mul(rotationMatrix(rotate, lw, lh), m)

	// Transform takes the matrix relative to the source's origin
	m = mul(m, f64.Aff3{1, 0, -float64(b.Min.X), 0, 1, -float64(b.Min.Y)})

	frame := blackFrame(w, h)
	draw.ApproxBiLinear.Transform(frame, m, src, b, draw.Src, nil)
	return frame, nil
}

// mul returns a*b, i.e. applying b first and then a
func mul(a, b f64.Aff3) f64.Aff3 {
	return f64.Aff3{
		a[0]*b[0] + a[1]*b[3], a[0]*b[1] + a[1]*b[4], a[0]*b[2] + a[1]*b[5] + a[2],
		a[3]*b[0] + a[4]*b[3], a[3]*b[1] + a[4]*b[4], a[3]*b[2] + a[4]*b[5] + a[5],
	}
}

// orientationMatrix maps a w x h source to its upright orientation, per the
// EXIF Orientation tag (1 = as stored, 6 = needs 90° clockwise, etc.)
func orientationMatrix(orient int, w, h float64) f64.Aff3 {
	switch orient {
	case 2: // mirrored
		return f64.Aff3{-1, 0, w, 0, 1, 0}
	case 3: // 180°
		return f64.Aff3{-1, 0, w, 0, -1, h}
	case 4: // flipped vertically
		return f64.Aff3{1, 0, 0, 0, -1, h}
	case 5: // transposed
		return f64.Aff3{0, 1, 0, 1, 0, 0}
	case 6: // 90° clockwise
		return f64.Aff3{0, -1, h, 1, 0, 0}
	case 7: // transverse
		return f64.Aff3{0, -1, h, -1, 0, w}
	case 8: // 90° anticlockwise
		return f64.Aff3{0, 1, 0, -1, 0, w}
	}
	return f64.Aff3{1, 0, 0, 0, 1, 0}
}

// rotationMatrix maps a lw x lh logical screen onto the panel, for a panel
// mounted turned clockwise by deg
func rotationMatrix(deg int, lw, lh float64) f64.Aff3 {
	switch deg {
	case 90:
		return f64.Aff3{0, -1, lh, 1, 0, 0}
	case 180:
		return f64.Aff3{-1, 0, lw, 0, -1, lh}
	case 270:
		return f64.Aff3{0, 1, 0, -1, 0, lw}
	}
	return f64.Aff3{1, 0, 0, 0, 1, 0}
}

// exifOrientation reads the Orientation tag from a JPEG's EXIF block,
// returning 1 (as stored) when there isn't one
func exifOrientation(data []byte) int {
	if len(data) < 4 || data[0] != 0xff || data[1] != 0xd8 {
		return 1
	}
	for i := 2; i+4 <= len(data); {
		if data[i] != 0xff {
			return 1
		}
		marker := data[i+1]
		size := int(binary.BigEndian.Uint16(data[i+2:]))
		if marker == 0xda || i+2+size > len(data) { // image data starts
			return 1
		}
		seg := data[i+4 : i+2+size]
		if marker == 0xe1 && len(seg) > 14 && string(seg[:6]) == "Exif\x00\x00" {
			return tiffOrientation(seg[6:])
		}
		i += 2 + size
	}
	return 1
}

func tiffOrientation(t []byte) int {
	var order binary.ByteOrder
	switch string(t[:2]) {
	case "II":
		order = binary.LittleEndian
	case "MM":
		order = binary.BigEndian
	default:
		return 1
	}
	ifd := int(order.Uint32(t[4:]))
	if ifd+2 > len(t) {
		return 1
	}
	count := int(order.Uint16(t[ifd:]))
	for e := ifd + 2; e+12 <= len(t) && count > 0; e, count = e+12, count-1 {
		if order.Uint16(t[e:]) == 0x0112 {
			if v := int(order.Uint16(t[e+8:])); v >= 1 && v <= 8 {
				return v
			}
			return 1
		}
	}
	return 1
}

// Blend writes from + (to-from)*t into dst, with t in 0..256
func Blend(dst, from, to *image.RGBA, t int) {
	d, a, b := dst.Pix, from.Pix, to.Pix
	for i := range d {
		x, y := int(a[i]), int(b[i])
		d[i] = uint8(x + (y-x)*t>>8)
	}
}
