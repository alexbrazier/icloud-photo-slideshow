package main

// Shared albums made with iOS/macOS 27 and later, whose links look like
// https://photos.icloud.com/shared/album/0AbCdEfGhIjKlMnOpQrStUvWx/ (the web
// app's server/cloudkit.ts does the same).
//
// They're CloudKit shares rather than the older "sharedstreams" albums. The
// link's ID resolves, without signing in, to the album's zone and a 20-minute
// access token; photos are listed from that zone, and each download URL comes
// from looking the photo's record up again, as those URLs expire too.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

const ckResolveURL = "https://ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production/public/records/resolve"

var ckPartitionPattern = regexp.MustCompile(`^https://p[0-9]{1,4}-ckdatabasews\.icloud\.com(:443)?$`)

const (
	// Items per page; each item is two records (asset and master)
	ckPageItems = 100
	// Shared albums hold at most a few thousand items
	ckMaxPages = 100
)

// These albums' IDs start with 0; the older albums' with a letter (B0…, B2…)
func isCloudKitAlbum(albumID string) bool {
	return strings.HasPrefix(albumID, "0")
}

type ckSession struct {
	partition string
	token     string
	zoneID    json.RawMessage
	expires   time.Time
}

type ckField struct {
	Value json.RawMessage `json:"value"`
}

type ckRecord struct {
	RecordName string             `json:"recordName"`
	RecordType string             `json:"recordType"`
	Fields     map[string]ckField `json:"fields"`
}

type ckFile struct {
	FileChecksum string `json:"fileChecksum"`
	DownloadURL  string `json:"downloadURL"`
}

func (r ckRecord) number(field string) int {
	var n int
	json.Unmarshal(r.Fields[field].Value, &n)
	return n
}

func (r ckRecord) file(field string) ckFile {
	var f ckFile
	json.Unmarshal(r.Fields[field].Value, &f)
	return f
}

func (r ckRecord) str(field string) string {
	var s string
	json.Unmarshal(r.Fields[field].Value, &s)
	return s
}

func (c *ICloud) ckPost(ctx context.Context, url string, body any, out any) (int, error) {
	payload, err := json.Marshal(body)
	if err != nil {
		return 0, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "text/plain")
	res, err := c.client.Do(req)
	if err != nil {
		return 0, err
	}
	defer res.Body.Close()
	data, err := io.ReadAll(io.LimitReader(res.Body, 64<<20))
	if err != nil {
		return res.StatusCode, err
	}
	if res.StatusCode != http.StatusOK {
		return res.StatusCode, fmt.Errorf("iCloud: %s", res.Status)
	}
	return res.StatusCode, json.Unmarshal(data, out)
}

func (c *ICloud) ckResolve(ctx context.Context) error {
	var reply struct {
		Results []struct {
			ServerErrorCode   string          `json:"serverErrorCode"`
			RequireAppleLogin bool            `json:"requireAppleLogin"`
			ZoneID            json.RawMessage `json:"zoneID"`
			Access            *struct {
				Token     string `json:"token"`
				TokenTTL  int64  `json:"tokenTTL"`
				Partition string `json:"databasePartition"`
			} `json:"anonymousPublicAccess"`
		} `json:"results"`
	}
	u := ckResolveURL + "?remapEnums=true&sharing_url_key=" + url.QueryEscape(c.albumID)
	body := map[string]any{"shortGUIDs": []map[string]string{{"value": c.albumID}}}
	if _, err := c.ckPost(ctx, u, body, &reply); err != nil {
		return err
	}
	if len(reply.Results) == 0 {
		return errors.New("unexpected reply from iCloud")
	}
	r := reply.Results[0]
	if r.ServerErrorCode == "NOT_FOUND" {
		return errors.New("album not found: check the link, and that it's still shared")
	}
	if r.RequireAppleLogin || r.Access == nil || r.Access.Token == "" {
		return errors.New("this album needs an Apple Account to view: turn on Public Website for it")
	}
	if !ckPartitionPattern.MatchString(r.Access.Partition) {
		return errors.New("unexpected iCloud partition")
	}
	ttl := time.Duration(r.Access.TokenTTL) * time.Millisecond
	if ttl <= 0 {
		ttl = 20 * time.Minute
	}
	c.ck = &ckSession{
		partition: strings.TrimSuffix(r.Access.Partition, ":443"),
		token:     r.Access.Token,
		zoneID:    r.ZoneID,
		// Renew a little before it lapses
		expires: time.Now().Add(ttl - 2*time.Minute),
	}
	return nil
}

// ckZone sends a request to the album's zone, renewing the access token once
// if it's refused
func (c *ICloud) ckZone(ctx context.Context, path string, body map[string]any) ([]ckRecord, error) {
	for attempt := range 2 {
		if c.ck == nil || attempt > 0 || time.Now().After(c.ck.expires) {
			if err := c.ckResolve(ctx); err != nil {
				return nil, err
			}
		}
		body["zoneID"] = c.ck.zoneID
		params := url.Values{
			"remapEnums":            {"true"},
			"sharing_url_key":       {c.albumID},
			"publicAccessAuthToken": {c.ck.token},
		}
		u := c.ck.partition + "/database/1/com.apple.photos.cloud/production/shared/" + path + "?" + params.Encode()
		var reply struct {
			Records []ckRecord `json:"records"`
		}
		status, err := c.ckPost(ctx, u, body, &reply)
		if (status == http.StatusUnauthorized || status == http.StatusForbidden) && attempt == 0 {
			continue
		}
		return reply.Records, err
	}
	return nil, errors.New("iCloud refused the album's access token")
}

var ckVideoTypes = map[string]bool{"com.apple.quicktime-movie": true, "public.mpeg-4": true}

// Edited photos have their rendered version on the asset record; otherwise
// the master's ready-made JPEGs (already rotated upright) are used
func ckPhoto(asset, master ckRecord) (Photo, bool) {
	if ckVideoTypes[master.str("itemType")] {
		return Photo{}, false
	}
	record, prefixes := master, []string{"resJPEGMed", "resJPEGThumb"}
	if asset.file("resJPEGFullRes").FileChecksum != "" {
		record, prefixes = asset, []string{"resJPEGFull"}
	}
	photo := Photo{GUID: record.RecordName}
	for _, p := range prefixes {
		d := Derivative{
			Checksum: record.file(p + "Res").FileChecksum,
			Width:    record.number(p + "Width"),
			Height:   record.number(p + "Height"),
			Field:    p + "Res",
		}
		if d.Checksum != "" && d.Width > 0 && d.Height > 0 {
			photo.Derivatives = append(photo.Derivatives, d)
		}
	}
	if len(photo.Derivatives) == 0 {
		return Photo{}, false
	}
	photo.Landscape = photo.Derivatives[0].Width >= photo.Derivatives[0].Height
	return photo, true
}

func (c *ICloud) ckPhotos(ctx context.Context) ([]Photo, error) {
	var photos []Photo
	for page, rank := 0, 0; page < ckMaxPages; page++ {
		records, err := c.ckZone(ctx, "records/query", map[string]any{
			"query": map[string]any{
				"recordType": "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted",
				"filterBy": []map[string]any{
					{"fieldName": "direction", "comparator": "EQUALS", "fieldValue": map[string]any{"value": "ASCENDING", "type": "STRING"}},
					{"fieldName": "startRank", "comparator": "EQUALS", "fieldValue": map[string]any{"value": rank, "type": "INT64"}},
				},
			},
			"resultsLimit": ckPageItems * 2,
		})
		if err != nil {
			return nil, err
		}
		masters := map[string]ckRecord{}
		for _, r := range records {
			if r.RecordType == "CPLMaster" {
				masters[r.RecordName] = r
			}
		}
		assets := 0
		for _, r := range records {
			if r.RecordType != "CPLAsset" {
				continue
			}
			assets++
			var ref struct {
				RecordName string `json:"recordName"`
			}
			json.Unmarshal(r.Fields["masterRef"].Value, &ref)
			if master, ok := masters[ref.RecordName]; ok {
				if p, ok := ckPhoto(r, master); ok {
					photos = append(photos, p)
				}
			}
		}
		if assets < ckPageItems {
			break
		}
		rank += assets
	}
	return photos, nil
}

// ckDownloadURL looks the photo's record up for a fresh download URL
func (c *ICloud) ckDownloadURL(ctx context.Context, photo Photo, d Derivative) (string, error) {
	records, err := c.ckZone(ctx, "records/lookup", map[string]any{
		"records": []map[string]any{{"recordName": photo.GUID, "desiredKeys": []string{d.Field}}},
	})
	if err != nil {
		return "", err
	}
	if len(records) == 0 {
		return "", fmt.Errorf("photo %s is no longer in the album", photo.GUID)
	}
	f := records[0].file(d.Field)
	u, err := url.Parse(strings.Replace(f.DownloadURL, "${f}", "photo.jpg", 1))
	if err != nil || f.FileChecksum != d.Checksum {
		return "", fmt.Errorf("no download URL for photo %s", photo.GUID)
	}
	// Only fetch from Apple's content servers
	if u.Scheme != "https" || u.Hostname() != "icloud-content.com" && !strings.HasSuffix(u.Hostname(), ".icloud-content.com") {
		return "", fmt.Errorf("unexpected download address for photo %s", photo.GUID)
	}
	return u.String(), nil
}
