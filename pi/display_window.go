//go:build darwin

package main

import (
	"errors"
	"image"
	"sync"

	"github.com/hajimehoshi/ebiten/v2"
)

// windowDisplay previews the frame on a Mac, at the panel's resolution
type windowDisplay struct {
	w, h int

	mu    sync.Mutex
	frame []byte
	dirty bool
	done  error

	screen *ebiten.Image
}

func init() {
	backends["window"] = runWindow
}

func runWindow(cfg Config, run func(Display) error) error {
	d := &windowDisplay{w: cfg.Width, h: cfg.Height}
	go func() {
		err := run(d)
		if err == nil {
			err = ebiten.Termination
		}
		d.mu.Lock()
		d.done = err
		d.mu.Unlock()
	}()

	// Fit the window on screen, keeping the panel's aspect ratio
	sw, sh := ebiten.Monitor().Size()
	scale := min(1, 0.9*float64(sw)/float64(cfg.Width), 0.9*float64(sh)/float64(cfg.Height))
	ebiten.SetWindowSize(int(float64(cfg.Width)*scale), int(float64(cfg.Height)*scale))
	ebiten.SetWindowTitle("iCloud frame preview")
	ebiten.SetWindowResizingMode(ebiten.WindowResizingModeEnabled)
	ebiten.SetTPS(30)

	err := ebiten.RunGame(d)
	if errors.Is(err, ebiten.Termination) {
		return nil
	}
	return err
}

func (d *windowDisplay) Size() (int, int) { return d.w, d.h }

func (d *windowDisplay) Show(frame *image.RGBA) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.frame == nil {
		d.frame = make([]byte, len(frame.Pix))
	}
	copy(d.frame, frame.Pix)
	d.dirty = true
	return nil
}

func (d *windowDisplay) Update() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.done
}

func (d *windowDisplay) Draw(screen *ebiten.Image) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.screen == nil {
		d.screen = ebiten.NewImage(d.w, d.h)
	}
	if d.dirty {
		d.screen.WritePixels(d.frame)
		d.dirty = false
	}
	screen.DrawImage(d.screen, nil)
}

func (d *windowDisplay) Layout(int, int) (int, int) { return d.w, d.h }
