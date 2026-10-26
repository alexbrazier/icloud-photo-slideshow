// icloud-frame shows an iCloud shared album as a slideshow, drawing straight
// to the Linux framebuffer on a Raspberry Pi (or a preview window on a Mac).
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type Config struct {
	AlbumID     string
	Interval    time.Duration
	Fade        time.Duration
	Orientation string
	Rotate      int
	Output      string
	Width       int
	Height      int
	OutDir      string
	Framebuffer string
	Console     string
	Count       int
}

// Flags default to FRAME_* environment variables, so the Pi's config file
// can be a plain systemd EnvironmentFile
func env(name, fallback string) string {
	if v, ok := os.LookupEnv(name); ok {
		return v
	}
	return fallback
}

func envDuration(name string, fallback time.Duration) time.Duration {
	raw := env(name, "")
	if raw == "" {
		return fallback
	}
	// Bare numbers are seconds, matching the web app's ?transition=
	if secs, err := strconv.ParseFloat(raw, 64); err == nil {
		return time.Duration(secs * float64(time.Second))
	}
	if d, err := time.ParseDuration(raw); err == nil {
		return d
	}
	log.Fatalf("invalid %s %q", name, raw)
	return 0
}

func envInt(name string, fallback int) int {
	raw := env(name, "")
	if raw == "" {
		return fallback
	}
	n, err := strconv.Atoi(raw)
	if err != nil {
		log.Fatalf("invalid %s %q", name, raw)
	}
	return n
}

func main() {
	defaultOutput := "fb"
	if runtime.GOOS == "darwin" {
		defaultOutput = "window"
	}
	var names []string
	for name := range backends {
		names = append(names, name)
	}
	sort.Strings(names)

	var cfg Config
	var album, size string
	flag.StringVar(&album, "album", env("ICLOUD_ALBUM_ID", ""), "iCloud shared album ID or share link (ICLOUD_ALBUM_ID)")
	flag.DurationVar(&cfg.Interval, "interval", envDuration("FRAME_INTERVAL", time.Minute), "time per photo (FRAME_INTERVAL)")
	flag.DurationVar(&cfg.Fade, "fade", envDuration("FRAME_FADE", time.Second), "crossfade duration, 0 to cut (FRAME_FADE)")
	flag.StringVar(&cfg.Orientation, "orientation", env("FRAME_ORIENTATION", "all"), "all, landscape or portrait (FRAME_ORIENTATION)")
	flag.IntVar(&cfg.Rotate, "rotate", envInt("FRAME_ROTATE", 0), "turn the picture clockwise: 0, 90, 180, 270 (FRAME_ROTATE)")
	flag.StringVar(&cfg.Output, "output", env("FRAME_OUTPUT", defaultOutput), "where to draw: "+strings.Join(names, ", ")+" (FRAME_OUTPUT)")
	flag.StringVar(&size, "size", env("FRAME_SIZE", "1280x800"), "frame size for window and png output (FRAME_SIZE)")
	flag.StringVar(&cfg.OutDir, "out", env("FRAME_OUT", "frames"), "directory for png output (FRAME_OUT)")
	flag.StringVar(&cfg.Framebuffer, "fb", env("FRAME_FB", "/dev/fb0"), "framebuffer device (FRAME_FB)")
	flag.StringVar(&cfg.Console, "console", env("FRAME_CONSOLE", "/dev/tty1"), "console to hide while drawing (FRAME_CONSOLE)")
	flag.IntVar(&cfg.Count, "count", 0, "stop after this many photos, 0 to run forever")
	flag.Parse()

	var err error
	if cfg.AlbumID, err = ParseAlbumID(album); err != nil {
		log.Fatal(err, " (set -album or ICLOUD_ALBUM_ID)")
	}
	if _, err := fmt.Sscanf(size, "%dx%d", &cfg.Width, &cfg.Height); err != nil || cfg.Width <= 0 || cfg.Height <= 0 {
		log.Fatalf("invalid size %q, want e.g. 1280x800", size)
	}
	switch cfg.Orientation {
	case "all", "landscape", "portrait":
	default:
		log.Fatalf("invalid orientation %q", cfg.Orientation)
	}
	switch cfg.Rotate {
	case 0, 90, 180, 270:
	default:
		log.Fatalf("invalid rotate %d", cfg.Rotate)
	}
	if cfg.Interval < cfg.Fade {
		log.Fatal("interval must be longer than the fade")
	}
	run, ok := backends[cfg.Output]
	if !ok {
		log.Fatalf("output %q isn't available here, want one of: %s", cfg.Output, strings.Join(names, ", "))
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	err = run(cfg, func(d Display) error {
		return runSlideshow(ctx, cfg, d)
	})
	if err != nil && !errors.Is(err, context.Canceled) {
		log.Fatal(err)
	}
}
