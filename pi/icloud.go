package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Same unofficial endpoints the web app's server uses (server/icloud.ts)
const defaultStreamsHost = "p153-sharedstreams.icloud.com"

// Newer album tokens are longer and can contain _ and -
var albumIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{8,200}$`)

type Derivative struct {
	Checksum string
	Width    int
	Height   int
	// The CloudKit record field it's in (iOS 27 albums only)
	Field string
}

type Photo struct {
	GUID        string
	Derivatives []Derivative
	Landscape   bool
}

type ICloud struct {
	albumID string
	host    string
	client  *http.Client
	// Access to an iOS 27 album, when it's one (see cloudkit.go)
	ck *ckSession
}

// iOS 27's links: https://photos.icloud.com/shared/album/0AbCdEfGhIjKlMnOpQrStUvWx/
var sharedLinkPattern = regexp.MustCompile(`/shared/album/([A-Za-z0-9_-]+)`)

// ParseAlbumID accepts a bare album ID or a full share link, older albums'
// (https://www.icloud.com/sharedalbum/#B0abc123) or iOS 27's
func ParseAlbumID(raw string) (string, error) {
	id := strings.TrimSpace(raw)
	if m := sharedLinkPattern.FindStringSubmatch(id); m != nil {
		id = m[1]
	} else if i := strings.LastIndex(id, "#"); i >= 0 {
		id = id[i+1:]
	}
	// Some links append ";" and the first photo's ID
	id, _, _ = strings.Cut(id, ";")
	if !albumIDPattern.MatchString(id) {
		return "", fmt.Errorf("invalid album ID %q", raw)
	}
	return id, nil
}

func NewICloud(albumID string) *ICloud {
	return &ICloud{
		albumID: albumID,
		host:    defaultStreamsHost,
		client:  &http.Client{Timeout: 60 * time.Second},
	}
}

func (c *ICloud) post(ctx context.Context, endpoint string, body, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	// Apple answers 330 with the album's real partition when it's elsewhere
	for range 2 {
		url := fmt.Sprintf("https://%s/%s/sharedstreams/%s", c.host, c.albumID, endpoint)
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		res, err := c.client.Do(req)
		if err != nil {
			return err
		}
		data, err := io.ReadAll(res.Body)
		res.Body.Close()
		if err != nil {
			return err
		}
		if res.StatusCode == 330 {
			var redirect struct {
				Host string `json:"X-Apple-MMe-Host"`
			}
			if json.Unmarshal(data, &redirect) == nil && redirect.Host != "" {
				c.host = redirect.Host
				continue
			}
		}
		if res.StatusCode != http.StatusOK {
			return fmt.Errorf("%s: %s: %s", endpoint, res.Status, bytes.TrimSpace(data))
		}
		return json.Unmarshal(data, out)
	}
	return errors.New(endpoint + ": too many redirects")
}

// Apple sends sizes as strings or numbers depending on the field
type flexInt int

func (n *flexInt) UnmarshalJSON(b []byte) error {
	v, err := strconv.Atoi(strings.Trim(string(b), `"`))
	if err != nil {
		return nil
	}
	*n = flexInt(v)
	return nil
}

// Photos lists the album's photos (videos are skipped). Slow, ~10s+.
func (c *ICloud) Photos(ctx context.Context) ([]Photo, error) {
	if isCloudKitAlbum(c.albumID) {
		return c.ckPhotos(ctx)
	}
	var stream struct {
		Photos []struct {
			PhotoGUID      string `json:"photoGuid"`
			MediaAssetType string `json:"mediaAssetType"`
			Derivatives    map[string]struct {
				Checksum string  `json:"checksum"`
				Width    flexInt `json:"width"`
				Height   flexInt `json:"height"`
			} `json:"derivatives"`
		} `json:"photos"`
	}
	if err := c.post(ctx, "webstream", map[string]any{"streamCtag": nil}, &stream); err != nil {
		return nil, err
	}

	var photos []Photo
	for _, p := range stream.Photos {
		if p.MediaAssetType == "video" {
			continue
		}
		photo := Photo{GUID: p.PhotoGUID}
		var largest Derivative
		for _, d := range p.Derivatives {
			if d.Checksum == "" || d.Width <= 0 || d.Height <= 0 {
				continue
			}
			deriv := Derivative{Checksum: d.Checksum, Width: int(d.Width), Height: int(d.Height)}
			photo.Derivatives = append(photo.Derivatives, deriv)
			if deriv.Width*deriv.Height > largest.Width*largest.Height {
				largest = deriv
			}
		}
		if len(photo.Derivatives) == 0 {
			continue
		}
		photo.Landscape = largest.Width >= largest.Height
		photos = append(photos, photo)
	}
	return photos, nil
}

// Download fetches one derivative. URLs are signed per request rather than
// cached, as Apple expires them after ~20 minutes and a frame only needs one
// photo per interval.
func (c *ICloud) Download(ctx context.Context, photo Photo, d Derivative) ([]byte, error) {
	url, err := c.downloadURL(ctx, photo, d)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	res, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("download %s: %s", photo.GUID, res.Status)
	}
	return io.ReadAll(res.Body)
}

func (c *ICloud) downloadURL(ctx context.Context, photo Photo, d Derivative) (string, error) {
	if isCloudKitAlbum(c.albumID) {
		return c.ckDownloadURL(ctx, photo, d)
	}
	var assets struct {
		Items map[string]struct {
			Location string `json:"url_location"`
			Path     string `json:"url_path"`
		} `json:"items"`
	}
	if err := c.post(ctx, "webasseturls", map[string]any{"photoGuids": []string{photo.GUID}}, &assets); err != nil {
		return "", err
	}
	item, ok := assets.Items[d.Checksum]
	if !ok {
		return "", fmt.Errorf("no URL for photo %s", photo.GUID)
	}
	// Only fetch from Apple's content servers
	if item.Location != "icloud-content.com" && !strings.HasSuffix(item.Location, ".icloud-content.com") ||
		!strings.HasPrefix(item.Path, "/") {
		return "", fmt.Errorf("unexpected download address for photo %s", photo.GUID)
	}
	return "https://" + item.Location + item.Path, nil
}

// PickDerivative returns the smallest derivative that still fills the
// screen at scale <= 1 for the given fit, falling back to the largest.
func PickDerivative(photo Photo, screenW, screenH int, cover bool) Derivative {
	var best, largest Derivative
	for _, d := range photo.Derivatives {
		px := d.Width * d.Height
		if px > largest.Width*largest.Height {
			largest = d
		}
		bigEnough := d.Width >= screenW || d.Height >= screenH
		if cover {
			bigEnough = d.Width >= screenW && d.Height >= screenH
		}
		if bigEnough && (best.Checksum == "" || px < best.Width*best.Height) {
			best = d
		}
	}
	if best.Checksum == "" {
		return largest
	}
	return best
}
