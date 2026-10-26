package main

import (
	"fmt"
	"image"
	"image/png"
	"os"
	"path/filepath"
)

// Display shows full frames of exactly Size() pixels
type Display interface {
	Size() (w, h int)
	Show(frame *image.RGBA) error
}

// A backend opens its display and calls run with it. The window backend has
// to own the main thread, so backends drive run rather than return a Display.
type backend func(cfg Config, run func(Display) error) error

var backends = map[string]backend{
	"png": runPNG,
}

// pngDisplay writes each frame to a numbered file, for headless checks
type pngDisplay struct {
	dir  string
	w, h int
	n    int
}

func runPNG(cfg Config, run func(Display) error) error {
	if err := os.MkdirAll(cfg.OutDir, 0o755); err != nil {
		return err
	}
	return run(&pngDisplay{dir: cfg.OutDir, w: cfg.Width, h: cfg.Height})
}

func (d *pngDisplay) Size() (int, int) { return d.w, d.h }

func (d *pngDisplay) Show(frame *image.RGBA) error {
	d.n++
	f, err := os.Create(filepath.Join(d.dir, fmt.Sprintf("frame-%04d.png", d.n)))
	if err != nil {
		return err
	}
	defer f.Close()
	return (&png.Encoder{CompressionLevel: png.BestSpeed}).Encode(f, frame)
}
