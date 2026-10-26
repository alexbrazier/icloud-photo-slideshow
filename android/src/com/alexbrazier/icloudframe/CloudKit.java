package com.alexbrazier.icloudframe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.net.Uri;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Shared albums made with iOS/macOS 27 and later, whose links look like
 * https://photos.icloud.com/shared/album/0AbCdEfGhIjKlMnOpQrStUvWx/ (the web app's
 * server/cloudkit.ts does the same).
 *
 * They're CloudKit shares rather than the older "sharedstreams" albums. The link's ID resolves,
 * without signing in, to the album's zone and a 20-minute access token; the photos are listed
 * from that zone, and each download address comes from looking the photo's record up again just
 * before downloading, as those addresses expire after ~20 minutes too.
 */
final class CloudKit {
    private static final String RESOLVE_URL =
            "https://ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production/public/records/resolve";
    private static final Pattern PARTITION = Pattern.compile("https://p[0-9]{1,4}-ckdatabasews\\.icloud\\.com(:443)?");
    private static final Pattern GUID = Pattern.compile("[A-Za-z0-9_.-]{8,80}");
    private static final Pattern CONTENT_HOST = Pattern.compile("([a-z0-9-]+\\.)*icloud-content\\.com");
    // Items per page; each is two records (asset and master)
    private static final int PAGE_ITEMS = 100;
    // Shared albums hold at most a few thousand items
    private static final int MAX_PAGES = 100;
    private static final int MAX_REPLY = 16 * 1024 * 1024;
    private static final long TOKEN_MARGIN_MS = 2 * 60 * 1000L;
    // The record fields that can hold a photo or video to download
    private static final String[] FILE_FIELDS = {"resJPEGFullRes", "resJPEGMedRes", "resJPEGThumbRes",
            "resVidMedRes", "resVidSmallRes"};

    private static final class Session {
        final String partition, token, name;
        final JSONObject zoneId;
        final long expires;

        Session(String partition, String token, JSONObject zoneId, String name, long expires) {
            this.partition = partition;
            this.token = token;
            this.zoneId = zoneId;
            this.name = name;
            this.expires = expires;
        }
    }

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private CloudKit() {}

    /** These albums' IDs start with 0; the older albums' with a letter (B0…, B2…). */
    static boolean isCloudKit(String albumId) {
        return albumId.startsWith("0");
    }

    /**
     * Record names are base64, whose / and + can't go in the file names photos are saved under,
     * so they're swapped for characters base64 doesn't use (and swapped back to look them up).
     */
    static String toGuid(String recordName) {
        return recordName.replace('/', '_').replace('+', '.');
    }

    private static String toRecordName(String guid) {
        return guid.replace('_', '/').replace('.', '+');
    }

    static ICloud.Album album(String albumId) throws IOException {
        Session s = session(albumId, false);
        List<ICloud.Photo> photos = new ArrayList<>();
        int rank = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONObject query;
            try {
                query = new JSONObject()
                        .put("query", new JSONObject()
                                .put("recordType", "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted")
                                .put("filterBy", new JSONArray()
                                        .put(filter("direction", "ASCENDING", "STRING"))
                                        .put(filter("startRank", rank, "INT64"))))
                        .put("resultsLimit", PAGE_ITEMS * 2);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            JSONArray records = zoneRequest(albumId, "records/query", query).optJSONArray("records");
            if (records == null) break;
            Map<String, JSONObject> masters = new HashMap<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r != null && "CPLMaster".equals(r.optString("recordType"))) masters.put(r.optString("recordName"), r);
            }
            int assets = 0;
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.optJSONObject(i);
                if (r == null || !"CPLAsset".equals(r.optString("recordType"))) continue;
                assets++;
                JSONObject ref = value(r, "masterRef") instanceof JSONObject ? (JSONObject) value(r, "masterRef") : null;
                JSONObject master = ref == null ? null : masters.get(ref.optString("recordName"));
                ICloud.Photo p = master == null ? null : toPhoto(r, master);
                if (p != null) photos.add(p);
            }
            if (assets < PAGE_ITEMS) break;
            rank += assets;
        }
        return new ICloud.Album(s.name, photos);
    }

    private static JSONObject filter(String field, Object value, String type) throws JSONException {
        return new JSONObject().put("fieldName", field).put("comparator", "EQUALS")
                .put("fieldValue", new JSONObject().put("value", value).put("type", type));
    }

    private static Object value(JSONObject record, String field) {
        JSONObject fields = record.optJSONObject("fields");
        JSONObject f = fields == null ? null : fields.optJSONObject(field);
        return f == null ? null : f.opt("value");
    }

    private static int number(JSONObject record, String field) {
        Object v = value(record, field);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    /** A rendition of a record, e.g. "resJPEGMed" for resJPEGMedRes, -Width and -Height. */
    private static ICloud.Derivative derivative(JSONObject record, String prefix) {
        Object res = value(record, prefix + "Res");
        String checksum = res instanceof JSONObject ? ((JSONObject) res).optString("fileChecksum") : "";
        int w = number(record, prefix + "Width"), h = number(record, prefix + "Height");
        return checksum.isEmpty() || w <= 0 || h <= 0 ? null : new ICloud.Derivative(prefix, checksum, w, h);
    }

    /**
     * Edited photos have their rendered version on the asset record; otherwise the master's
     * ready-made JPEGs are used (already rotated upright, unlike the HEIC original). Videos get
     * the master's poster JPEGs and its two video sizes; Live Photos show as photos.
     */
    private static ICloud.Photo toPhoto(JSONObject asset, JSONObject master) {
        String type = String.valueOf(value(master, "itemType"));
        boolean video = type.equals("com.apple.quicktime-movie") || type.equals("public.mpeg-4");
        Object assetDate = value(asset, "assetDate");
        long date = assetDate instanceof Number ? ((Number) assetDate).longValue() : 0;

        List<ICloud.Derivative> images = new ArrayList<>(), videos = new ArrayList<>();
        ICloud.Derivative edited = video ? null : derivative(asset, "resJPEGFull");
        JSONObject record = master;
        if (edited != null) {
            images.add(edited);
            record = asset;
        } else {
            for (String prefix : new String[] {"resJPEGMed", "resJPEGThumb"}) {
                ICloud.Derivative d = derivative(master, prefix);
                if (d != null) images.add(d);
            }
        }
        if (video) {
            for (String prefix : new String[] {"resVidMed", "resVidSmall"}) {
                ICloud.Derivative d = derivative(master, prefix);
                if (d != null) videos.add(d);
            }
            if (videos.isEmpty()) return null;
        }
        // It names the files the photo is saved under, so only plain characters
        String guid = toGuid(record.optString("recordName"));
        if (images.isEmpty() || !GUID.matcher(guid).matches()) return null;
        return new ICloud.Photo(guid, images, videos, date);
    }

    /** A fresh, short-lived download address for one rendition of a photo or video. */
    static String downloadUrl(String albumId, String guid, String checksum) throws IOException {
        JSONObject body;
        try {
            JSONArray keys = new JSONArray();
            for (String f : FILE_FIELDS) keys.put(f);
            body = new JSONObject().put("records", new JSONArray().put(
                    new JSONObject().put("recordName", toRecordName(guid)).put("desiredKeys", keys)));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        JSONArray records = zoneRequest(albumId, "records/lookup", body).optJSONArray("records");
        JSONObject record = records == null ? null : records.optJSONObject(0);
        if (record == null) throw new IOException("Photo no longer in the album");
        for (String field : FILE_FIELDS) {
            Object res = value(record, field);
            if (!(res instanceof JSONObject) || !checksum.equals(((JSONObject) res).optString("fileChecksum"))) continue;
            String url = ((JSONObject) res).optString("downloadURL").replace("${f}", "photo");
            Uri uri = Uri.parse(url);
            // Only Apple's content servers are fetched
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || !CONTENT_HOST.matcher(uri.getHost()).matches()) {
                throw new IOException("Unexpected download address from iCloud");
            }
            return url;
        }
        throw new IOException("No download address for photo");
    }

    private static Session session(String albumId, boolean renew) throws IOException {
        Session s = sessions.get(albumId);
        if (!renew && s != null && s.expires > System.currentTimeMillis()) return s;
        s = resolve(albumId);
        sessions.put(albumId, s);
        return s;
    }

    private static Session resolve(String albumId) throws IOException {
        String url = RESOLVE_URL + "?remapEnums=true&sharing_url_key=" + URLEncoder.encode(albumId, "UTF-8");
        Net.Response res = Net.postJson(url, "{\"shortGUIDs\":[{\"value\":" + JSONObject.quote(albumId) + "}]}",
                1024 * 1024);
        if (res.status != 200) throw new IOException("iCloud returned HTTP " + res.status);
        try {
            JSONObject result = new JSONObject(res.text()).getJSONArray("results").getJSONObject(0);
            if ("NOT_FOUND".equals(result.optString("serverErrorCode"))) {
                throw new IOException("Album not found. Check the link and that it's still shared");
            }
            JSONObject access = result.optJSONObject("anonymousPublicAccess");
            if (result.optBoolean("requireAppleLogin") || access == null || access.optString("token").isEmpty()) {
                throw new IOException("This album needs an Apple Account to view. Turn on Public Website for it");
            }
            String partition = access.optString("databasePartition");
            if (!PARTITION.matcher(partition).matches()) throw new IOException("Unexpected iCloud partition");
            JSONObject share = result.optJSONObject("share");
            Object title = share == null ? null : value(share, "cloudkit.title");
            long ttl = access.optLong("tokenTTL", 20 * 60 * 1000L);
            return new Session(partition.replace(":443", ""), access.getString("token"),
                    result.getJSONObject("zoneID"), title instanceof String ? ((String) title).trim() : "",
                    System.currentTimeMillis() + ttl - TOKEN_MARGIN_MS);
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from iCloud");
        }
    }

    /** A request to the album's zone, renewing the access token once if it's refused. */
    private static JSONObject zoneRequest(String albumId, String path, JSONObject body) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Session s = session(albumId, attempt > 0);
            String url = s.partition + "/database/1/com.apple.photos.cloud/production/shared/" + path
                    + "?remapEnums=true&sharing_url_key=" + URLEncoder.encode(albumId, "UTF-8")
                    + "&publicAccessAuthToken=" + URLEncoder.encode(s.token, "UTF-8");
            Net.Response res;
            try {
                res = Net.postJson(url, body.put("zoneID", s.zoneId).toString(), MAX_REPLY);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            if ((res.status == 401 || res.status == 403) && attempt == 0) continue;
            if (res.status != 200) throw new IOException("iCloud returned HTTP " + res.status);
            try {
                return new JSONObject(res.text());
            } catch (JSONException e) {
                throw new IOException("Unexpected reply from iCloud");
            }
        }
        throw new IOException("iCloud refused the album's access token");
    }
}
