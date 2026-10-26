package main

import (
	"context"
	"fmt"
	"image"
	"log"
	"math/rand/v2"
	"time"
)

const (
	listRefresh = time.Hour
	minRetry    = 30 * time.Second
	maxRetry    = 10 * time.Minute
	frameTime   = time.Second / 30
)

type slideshow struct {
	cfg    Config
	icloud *ICloud
	disp   Display
	w, h   int
	// Screen orientation as seen by the viewer, after rotation
	landscape bool

	photos  []Photo
	fetched time.Time
	order   []int
	pos     int

	scratch *image.RGBA
}

func runSlideshow(ctx context.Context, cfg Config, d Display) error {
	w, h := d.Size()
	lw, lh := w, h
	if cfg.Rotate == 90 || cfg.Rotate == 270 {
		lw, lh = h, w
	}
	s := &slideshow{
		cfg:       cfg,
		icloud:    NewICloud(cfg.AlbumID),
		disp:      d,
		w:         w,
		h:         h,
		landscape: lw >= lh,
		scratch:   image.NewRGBA(image.Rect(0, 0, w, h)),
	}

	cur := blackFrame(w, h)
	if err := d.Show(cur); err != nil {
		return err
	}
	var shownAt time.Time
	for n := 1; cfg.Count == 0 || n <= cfg.Count; n++ {
		next, err := s.prepareNext(ctx)
		if err != nil {
			return err
		}
		if !shownAt.IsZero() {
			if err := sleep(ctx, time.Until(shownAt.Add(cfg.Interval))); err != nil {
				return err
			}
		}
		if err := s.fade(ctx, cur, next); err != nil {
			return err
		}
		cur, shownAt = next, time.Now()
	}
	return nil
}

// prepareNext downloads and renders the next photo in the shuffle, skipping
// ones that fail, and waiting with backoff while the album can't be loaded
func (s *slideshow) prepareNext(ctx context.Context) (*image.RGBA, error) {
	retry := minRetry
	for {
		s.refreshList(ctx)
		if len(s.order) > 0 {
			for range len(s.order) {
				frame, err := s.render(ctx, s.photos[s.order[s.pos]])
				s.advance()
				if err == nil {
					return frame, nil
				}
				if ctx.Err() != nil {
					return nil, ctx.Err()
				}
				log.Printf("skipping photo: %v", err)
			}
		}
		log.Printf("no photos to show, retrying in %s", retry)
		if err := sleep(ctx, retry); err != nil {
			return nil, err
		}
		retry = min(retry*2, maxRetry)
		s.fetched = time.Time{}
	}
}

// refreshList reloads the album when it's stale, keeping the old list (and
// shuffle position) if the reload fails or nothing changed
func (s *slideshow) refreshList(ctx context.Context) {
	if !s.fetched.IsZero() && time.Since(s.fetched) < listRefresh {
		return
	}
	start := time.Now()
	photos, err := s.icloud.Photos(ctx)
	if err != nil {
		log.Printf("loading album: %v", err)
		return
	}
	s.fetched = time.Now()

	var filtered []Photo
	for _, p := range photos {
		if s.cfg.Orientation == "all" || p.Landscape == (s.cfg.Orientation == "landscape") {
			filtered = append(filtered, p)
		}
	}
	log.Printf("album has %d photos (%d shown), loaded in %s",
		len(photos), len(filtered), time.Since(start).Round(time.Millisecond))
	if samePhotos(s.photos, filtered) {
		return
	}
	s.photos = filtered
	s.order = rand.Perm(len(filtered))
	s.pos = 0
}

func (s *slideshow) advance() {
	s.pos++
	// Reshuffle after each full pass, like the web app
	if s.pos >= len(s.order) {
		s.order = rand.Perm(len(s.photos))
		s.pos = 0
	}
}

func (s *slideshow) render(ctx context.Context, p Photo) (*image.RGBA, error) {
	// Fill the screen when orientations match, letterbox when they don't
	cover := p.Landscape == s.landscape
	sw, sh := s.w, s.h
	if s.cfg.Rotate == 90 || s.cfg.Rotate == 270 {
		sw, sh = sh, sw
	}
	d := PickDerivative(p, sw, sh, cover)

	start := time.Now()
	data, err := s.icloud.Download(ctx, p, d)
	if err != nil {
		return nil, err
	}
	fetched := time.Since(start)

	start = time.Now()
	frame, err := Render(data, s.w, s.h, s.cfg.Rotate, cover)
	if err != nil {
		return nil, fmt.Errorf("decode %s: %w", p.GUID, err)
	}
	log.Printf("photo %d/%d: %dx%d, %d KB, fetched in %s, rendered in %s",
		s.pos+1, len(s.order), d.Width, d.Height, len(data)/1024,
		fetched.Round(time.Millisecond), time.Since(start).Round(time.Millisecond))
	return frame, nil
}

// fade crossfades between frames over cfg.Fade, at up to 30 fps. Slow
// devices show fewer, larger steps rather than a longer fade.
func (s *slideshow) fade(ctx context.Context, from, to *image.RGBA) error {
	if s.cfg.Fade > 0 {
		start := time.Now()
		frames := 0
		for {
			t := float64(time.Since(start)) / float64(s.cfg.Fade)
			if t >= 1 {
				break
			}
			eased := t * t * (3 - 2*t)
			Blend(s.scratch, from, to, int(eased*256))
			if err := s.disp.Show(s.scratch); err != nil {
				return err
			}
			frames++
			if err := sleep(ctx, frameTime-time.Since(start)%frameTime); err != nil {
				return err
			}
		}
		log.Printf("crossfade: %d frames, %.0f fps", frames, float64(frames)/time.Since(start).Seconds())
	}
	return s.disp.Show(to)
}

func samePhotos(a, b []Photo) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i].GUID != b[i].GUID {
			return false
		}
	}
	return true
}

func blackFrame(w, h int) *image.RGBA {
	frame := image.NewRGBA(image.Rect(0, 0, w, h))
	for i := 3; i < len(frame.Pix); i += 4 {
		frame.Pix[i] = 0xff
	}
	return frame
}

func sleep(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
