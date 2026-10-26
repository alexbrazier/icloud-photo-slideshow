package com.alexbrazier.icloudframe;

import org.json.JSONException;
import org.json.JSONObject;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Reads an iCloud shared album (with Public Website on) through the same unofficial endpoints as
 * the web app's server (server/icloud.ts). Download addresses are looked up per photo, just before
 * downloading, so their ~20 minute expiry never matters. Albums shared from iOS 27 on are a
 * different kind, handled by CloudKit.
 */
final class ICloud {
    // Any partition answers 330 with the album's own one
    private static final String DEFAULT_HOST = "p23-sharedstreams.icloud.com";
    private static final Pattern HOST = Pattern.compile("p[0-9]{1,4}-sharedstreams\\.icloud\\.com");
    // Newer album tokens are longer and can contain _ and -
    private static final Pattern ALBUM_ID = Pattern.compile("[A-Za-z0-9_-]{8,200}");
    private static final Pattern GUID = Pattern.compile("[A-Za-z0-9-]{8,80}");
    private static final Pattern CONTENT_HOST = Pattern.compile("([a-z0-9-]+\\.)*icloud-content\\.com");
    private static final long MAX_JSON = 64L * 1024 * 1024;

    static final class Derivative {
        final String name, checksum;
        final int width, height;

        Derivative(String name, String checksum, int width, int height) {
            this.name = name;
            this.checksum = checksum;
            this.width = width;
            this.height = height;
        }
    }

    /** A photo, or a video with its poster image as `derivatives` and its MP4s as `videos`. */
    static final class Photo {
        final String guid;
        final List<Derivative> derivatives;
        final List<Derivative> videos;
        final long dateCreated;

        Photo(String guid, List<Derivative> derivatives, List<Derivative> videos, long dateCreated) {
            this.guid = guid;
            this.derivatives = derivatives;
            this.videos = videos;
            this.dateCreated = dateCreated;
        }

        boolean isVideo() { return !videos.isEmpty(); }
    }

    static final class Album {
        final String name;
        final List<Photo> photos;

        Album(String name, List<Photo> photos) {
            this.name = name;
            this.photos = photos;
        }
    }

    // Video versions are named by their height, like "720p"
    private static final Pattern VIDEO_DERIVATIVE = Pattern.compile("\\d{3,4}p");

    private final String albumId;
    private String host;

    ICloud(String albumId, String knownHost) {
        this.albumId = albumId;
        this.host = knownHost != null && HOST.matcher(knownHost).matches() ? knownHost : DEFAULT_HOST;
    }

    /** The album's partition, worth saving so the next start skips the redirect. */
    String host() { return host; }

    // iOS 27's links: https://photos.icloud.com/shared/album/0AbCdEfGhIjKlMnOpQrStUvWx/
    private static final Pattern SHARED_LINK = Pattern.compile("/shared/album/([A-Za-z0-9_-]+)");

    /** Accepts a bare album ID or a share link, returning "" when it isn't valid. */
    static String parseAlbumId(String raw) {
        if (raw == null) return "";
        String id = raw.trim();
        java.util.regex.Matcher shared = SHARED_LINK.matcher(id);
        if (shared.find()) return ALBUM_ID.matcher(shared.group(1)).matches() ? shared.group(1) : "";
        int hash = id.lastIndexOf('#');
        if (hash >= 0) id = id.substring(hash + 1);
        int semi = id.indexOf(';');
        if (semi >= 0) id = id.substring(0, semi);
        return ALBUM_ID.matcher(id).matches() ? id : "";
    }

    /**
     * Sends a request to the album's partition, following Apple's 330 redirect to another one.
     * Returns a connection whose reply is 200 OK, for the caller to read and disconnect.
     */
    private HttpURLConnection request(String endpoint, String body) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String url = "https://" + host + "/" + albumId + "/sharedstreams/" + endpoint;
            HttpURLConnection conn = Net.post(url, body);
            int status = conn.getResponseCode();
            if (status == 200) return conn;
            JSONObject json;
            try {
                json = parse(Net.read(conn, 64 * 1024));
            } finally {
                conn.disconnect();
            }
            if (status == 330) {
                // The album lives on another partition, named in the reply
                String next = json == null ? "" : json.optString("X-Apple-MMe-Host");
                if (!HOST.matcher(next).matches()) throw new IOException("Unexpected iCloud redirect");
                host = next;
                continue;
            }
            if (status == 404 || status == 403 || status == 401) {
                throw new IOException("Album not found. Check the link and that Public Website is on");
            }
            throw new IOException("iCloud returned HTTP " + status);
        }
        throw new IOException("Too many iCloud redirects");
    }

    private static JSONObject parse(Net.Response res) {
        try {
            return new JSONObject(res.text());
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * The album's name and every photo and video in it. Slow (~10s for a few hundred photos).
     * The reply is streamed, as an album of thousands of photos is too big to hold in memory
     * twice on a 1 GB frame.
     */
    Album album() throws IOException {
        if (CloudKit.isCloudKit(albumId)) return CloudKit.album(albumId);
        HttpURLConnection conn = request("webstream", "{\"streamCtag\":null}");
        List<Photo> photos = new ArrayList<>();
        String name = "";
        try (JsonReader r = new JsonReader(new InputStreamReader(
                new BufferedInputStream(Net.limited(conn.getInputStream(), MAX_JSON)), StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                String key = r.nextName();
                if (key.equals("streamName") && r.peek() == JsonToken.STRING) {
                    name = r.nextString().trim();
                    continue;
                }
                if (!key.equals("photos") || r.peek() != JsonToken.BEGIN_ARRAY) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                while (r.hasNext()) {
                    Photo p = readPhoto(r);
                    if (p != null) photos.add(p);
                }
                r.endArray();
            }
            r.endObject();
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Unexpected album data from iCloud");
        } finally {
            conn.disconnect();
        }
        return new Album(name, photos);
    }

    private static Photo readPhoto(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return null;
        }
        String guid = "", type = "", created = "";
        List<Derivative> derivatives = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.nextNull();
            } else if (name.equals("photoGuid")) {
                guid = r.nextString();
            } else if (name.equals("mediaAssetType")) {
                type = r.nextString();
            } else if (name.equals("dateCreated")) {
                created = r.nextString();
            } else if (name.equals("derivatives") && r.peek() == JsonToken.BEGIN_OBJECT) {
                r.beginObject();
                while (r.hasNext()) {
                    Derivative d = readDerivative(r, r.nextName());
                    if (d != null) derivatives.add(d);
                }
                r.endObject();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (!GUID.matcher(guid).matches() || derivatives.isEmpty()) return null;
        List<Derivative> images = new ArrayList<>(), videos = new ArrayList<>();
        for (Derivative d : derivatives) {
            if ("video".equals(type) && VIDEO_DERIVATIVE.matcher(d.name).matches()) videos.add(d);
            else images.add(d);
        }
        // A video needs a poster image to show while it loads, and at least one playable version
        if (images.isEmpty() || ("video".equals(type) && videos.isEmpty())) return null;
        long date = 0;
        if (created.length() >= 19) {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                date = iso.parse(created.substring(0, 19)).getTime();
            } catch (ParseException ignored) {
                // Leave undated photos at 0
            }
        }
        return new Photo(guid, images, videos, date);
    }

    private static Derivative readDerivative(JsonReader r, String derivativeName) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return null;
        }
        String checksum = "";
        int w = 0, h = 0;
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            JsonToken t = r.peek();
            if (name.equals("checksum") && t == JsonToken.STRING) checksum = r.nextString();
            // Apple sends sizes as strings or numbers
            else if (name.equals("width") && (t == JsonToken.STRING || t == JsonToken.NUMBER)) w = toInt(r.nextString());
            else if (name.equals("height") && (t == JsonToken.STRING || t == JsonToken.NUMBER)) h = toInt(r.nextString());
            else r.skipValue();
        }
        r.endObject();
        return checksum.isEmpty() || w <= 0 || h <= 0 ? null : new Derivative(derivativeName, checksum, w, h);
    }

    private static int toInt(Object v) {
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A fresh, short-lived download address for one derivative of a photo. */
    String downloadUrl(String guid, String checksum) throws IOException {
        if (CloudKit.isCloudKit(albumId)) return CloudKit.downloadUrl(albumId, guid, checksum);
        HttpURLConnection conn = request("webasseturls", "{\"photoGuids\":[" + JSONObject.quote(guid) + "]}");
        JSONObject res;
        try {
            res = parse(Net.read(conn, 1024 * 1024));
        } finally {
            conn.disconnect();
        }
        if (res == null) throw new IOException("Unexpected reply from iCloud");
        JSONObject items = res.optJSONObject("items");
        JSONObject item = items == null ? null : items.optJSONObject(checksum);
        if (item == null) throw new IOException("No download address for photo");
        String location = item.optString("url_location");
        String path = item.optString("url_path");
        // Only Apple's content servers are fetched. The character check stops values like
        // "evil.com/.icloud-content.com" passing the suffix test.
        if (!CONTENT_HOST.matcher(location).matches() || !path.startsWith("/")) {
            throw new IOException("Unexpected download address from iCloud");
        }
        return "https://" + location + path;
    }

    /**
     * The video version to play: the smallest that's at least 720p (or the screen, if smaller),
     * as frames' decoders and storage struggle with more.
     */
    static Derivative pickVideo(Photo photo, int screenShort) {
        int want = Math.min(screenShort, 720);
        Derivative best = null, largest = null;
        for (Derivative d : photo.videos) {
            int side = Math.min(d.width, d.height);
            if (largest == null || side > Math.min(largest.width, largest.height)) largest = d;
            if (side >= want && (best == null || side < Math.min(best.width, best.height))) best = d;
        }
        return best != null ? best : largest;
    }

    /**
     * The smallest derivative that still covers a screen of this size in either orientation,
     * falling back to the largest.
     */
    static Derivative pick(Photo photo, int screenLong, int screenShort) {
        Derivative best = null, largest = null;
        for (Derivative d : photo.derivatives) {
            long px = (long) d.width * d.height;
            if (largest == null || px > (long) largest.width * largest.height) largest = d;
            boolean bigEnough = Math.max(d.width, d.height) >= screenLong
                    && Math.min(d.width, d.height) >= screenShort;
            if (bigEnough && (best == null || px < (long) best.width * best.height)) best = d;
        }
        return best != null ? best : largest;
    }
}
