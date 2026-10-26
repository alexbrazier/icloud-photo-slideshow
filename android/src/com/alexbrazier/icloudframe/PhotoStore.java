package com.alexbrazier.icloudframe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Each saved album's photo list and name, and the screen-sized copies (and videos) saved for
 * offline use, in files/photos. Everything is recorded in index.json, so the frame keeps showing
 * photos offline, including after a reboot. A photo in several albums is saved once.
 */
final class PhotoStore {
    static final class Entry {
        final String guid, checksum, videoChecksum;
        final long date;

        /** checksum is the photo (or a video's poster); videoChecksum is null for photos. */
        Entry(String guid, String checksum, String videoChecksum, long date) {
            this.guid = guid;
            this.checksum = checksum;
            this.videoChecksum = videoChecksum;
            this.date = date;
        }

        boolean isVideo() { return videoChecksum != null; }
    }

    static final class AlbumState {
        final String id;
        String name = "", host = "", error = "";
        long fetched;
        List<Entry> entries = new ArrayList<>();

        AlbumState(String id) { this.id = id; }
    }

    private static final class Saved {
        final String checksum;
        final boolean landscape;
        final long bytes;
        String videoChecksum;
        long videoBytes;

        Saved(String checksum, boolean landscape, long bytes) {
            this.checksum = checksum;
            this.landscape = landscape;
            this.bytes = bytes;
        }
    }

    // Writing the whole index after every download wears cheap flash, so batch it
    private static final int SAVE_EVERY = 20;

    private final File dir;
    private final File index;
    private final Map<String, AlbumState> albums = new LinkedHashMap<>();
    private final Map<String, Saved> saved = new HashMap<>();
    private int generation;
    private int unsaved;

    PhotoStore(File filesDir) {
        dir = new File(filesDir, "photos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        index = new File(dir, "index.json");
        load();
    }

    File file(String guid) { return new File(dir, guid + ".jpg"); }
    File videoFile(String guid) { return new File(dir, guid + ".mp4"); }

    /** Free space on the storage the photos are saved to. */
    long usableSpace() { return dir.getUsableSpace(); }

    /** Changes whenever the store is cleared, so a download started before then is dropped. */
    synchronized int generation() { return generation; }

    synchronized long fetched(String album) {
        AlbumState a = albums.get(album);
        return a == null ? 0 : a.fetched;
    }

    synchronized String host(String album) {
        AlbumState a = albums.get(album);
        return a == null ? "" : a.host;
    }

    synchronized String name(String album) {
        AlbumState a = albums.get(album);
        return a == null ? "" : a.name;
    }

    synchronized int count(String album) {
        AlbumState a = albums.get(album);
        return a == null ? 0 : a.entries.size();
    }

    synchronized int savedCount(String album) {
        AlbumState a = albums.get(album);
        int n = 0;
        if (a != null) for (Entry e : a.entries) if (saved.containsKey(e.guid)) n++;
        return n;
    }

    synchronized void setError(String album, String error) {
        AlbumState a = albums.get(album);
        if (a != null) a.error = error;
    }

    synchronized String error(String album) {
        AlbumState a = albums.get(album);
        return a == null ? "" : a.error;
    }

    synchronized int totalCount(Collection<String> ids) {
        Set<String> guids = new HashSet<>();
        for (String id : ids) {
            AlbumState a = albums.get(id);
            if (a != null) for (Entry e : a.entries) guids.add(e.guid);
        }
        return guids.size();
    }

    synchronized int totalSaved(Collection<String> ids) {
        Set<String> guids = new HashSet<>();
        for (String id : ids) {
            AlbumState a = albums.get(id);
            if (a != null) for (Entry e : a.entries) if (saved.containsKey(e.guid)) guids.add(e.guid);
        }
        return guids.size();
    }

    synchronized long savedBytes() {
        long total = 0;
        for (Saved s : saved.values()) total += s.bytes + s.videoBytes;
        return total;
    }

    /** Forgets albums that were removed from the list, deleting photos no other album has. */
    void keepAlbums(Collection<String> ids) {
        boolean changed;
        synchronized (this) {
            changed = albums.keySet().retainAll(new HashSet<>(ids));
            // A download for a removed album that's in flight is thrown away when it finishes
            if (changed) generation++;
            for (String id : ids) if (!albums.containsKey(id)) albums.put(id, new AlbumState(id));
        }
        if (changed) deleteOrphans();
    }

    /** Deletes every saved photo and video. Slow with thousands, so call it off the main thread. */
    void clear() {
        synchronized (this) {
            generation++;
            for (AlbumState a : albums.values()) {
                a.entries = new ArrayList<>();
                a.fetched = 0;
            }
            saved.clear();
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.equals(index)) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        save();
    }

    /** Replaces an album's list. Returns whether it changed. */
    boolean setEntries(String album, List<Entry> list, String host, String name) {
        boolean changed;
        synchronized (this) {
            AlbumState a = albums.get(album);
            if (a == null) return false; // removed while loading
            changed = !sameEntries(a.entries, list) || !a.name.equals(name);
            a.entries = new ArrayList<>(list);
            a.host = host;
            a.name = name;
            a.error = "";
            a.fetched = System.currentTimeMillis();
        }
        if (deleteOrphans()) changed = true;
        else save();
        return changed;
    }

    /** Deletes saved files that no album has any more. Returns whether any were deleted. */
    private boolean deleteOrphans() {
        List<String> removed = new ArrayList<>();
        synchronized (this) {
            Set<String> keep = new HashSet<>();
            for (AlbumState a : albums.values()) for (Entry e : a.entries) keep.add(e.guid);
            for (String guid : new ArrayList<>(saved.keySet())) {
                if (!keep.contains(guid)) {
                    saved.remove(guid);
                    removed.add(guid);
                }
            }
        }
        for (String guid : removed) deleteFiles(guid);
        save();
        return !removed.isEmpty();
    }

    private void deleteFiles(String guid) {
        //noinspection ResultOfMethodCallIgnored
        file(guid).delete();
        //noinspection ResultOfMethodCallIgnored
        videoFile(guid).delete();
    }

    private static boolean sameEntries(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Entry x = a.get(i), y = b.get(i);
            if (!x.guid.equals(y.guid) || !x.checksum.equals(y.checksum)
                    || !String.valueOf(x.videoChecksum).equals(String.valueOf(y.videoChecksum))) return false;
        }
        return true;
    }

    /** The next download: the newest photo (or poster) not saved, then videos, skipping repeat failures. */
    synchronized Download nextMissing(Collection<String> enabled, Set<String> skip, boolean videos) {
        Entry best = null, bestVideo = null;
        for (String id : enabled) {
            AlbumState a = albums.get(id);
            if (a == null) continue;
            for (Entry e : a.entries) {
                if (skip.contains(e.guid) || (e.isVideo() && !videos)) continue;
                Saved s = saved.get(e.guid);
                if (s == null || !s.checksum.equals(e.checksum)) {
                    if (best == null || e.date > best.date) best = e;
                } else if (e.isVideo() && !e.videoChecksum.equals(s.videoChecksum)) {
                    if (bestVideo == null || e.date > bestVideo.date) bestVideo = e;
                }
            }
        }
        // Posters and photos first, so every slide has something to show while videos download
        if (best != null) return new Download(albumOf(best), best, false);
        if (bestVideo != null) return new Download(albumOf(bestVideo), bestVideo, true);
        return null;
    }

    static final class Download {
        final String album;
        final Entry entry;
        final boolean video;

        Download(String album, Entry entry, boolean video) {
            this.album = album;
            this.entry = entry;
            this.video = video;
        }
    }

    private String albumOf(Entry e) {
        for (AlbumState a : albums.values()) if (a.entries.contains(e)) return a.id;
        return "";
    }

    /**
     * Makes room under the limit by deleting saved photos: first ones only in albums that are
     * switched off, then the oldest, but never one newer than the photo about to be saved.
     * Returns whether anything was deleted.
     */
    boolean evict(Collection<String> enabled, Entry wanted, long limitBytes) {
        List<String> removed = new ArrayList<>();
        synchronized (this) {
            Map<String, Long> rank = new HashMap<>();
            Set<String> inEnabled = new HashSet<>();
            for (String id : enabled) {
                AlbumState a = albums.get(id);
                if (a != null) for (Entry e : a.entries) inEnabled.add(e.guid);
            }
            for (AlbumState a : albums.values()) {
                for (Entry e : a.entries) rank.put(e.guid, inEnabled.contains(e.guid) ? e.date : Long.MIN_VALUE);
            }
            long total = 0;
            for (Saved s : saved.values()) total += s.bytes + s.videoBytes;
            while (total > limitBytes) {
                String oldest = null;
                long oldestRank = Long.MAX_VALUE;
                for (String guid : saved.keySet()) {
                    Long r = rank.get(guid);
                    long value = r == null ? Long.MIN_VALUE : r;
                    if (value < oldestRank) {
                        oldest = guid;
                        oldestRank = value;
                    }
                }
                if (oldest == null || (wanted != null && oldestRank != Long.MIN_VALUE && oldestRank >= wanted.date)) break;
                Saved s = saved.remove(oldest);
                total -= s.bytes + s.videoBytes;
                removed.add(oldest);
            }
        }
        for (String guid : removed) deleteFiles(guid);
        if (!removed.isEmpty()) save();
        return !removed.isEmpty();
    }

    /** Records a downloaded photo or poster, unless the store was cleared since it started. */
    void markSaved(Entry e, boolean landscape, int startedGeneration) {
        boolean write;
        synchronized (this) {
            if (startedGeneration != generation || !file(e.guid).exists()) {
                //noinspection ResultOfMethodCallIgnored
                file(e.guid).delete();
                return;
            }
            saved.put(e.guid, new Saved(e.checksum, landscape, file(e.guid).length()));
            write = ++unsaved >= SAVE_EVERY;
        }
        if (write) save();
    }

    /** Records a downloaded video for an already-saved poster. */
    void markVideoSaved(Entry e, int startedGeneration) {
        boolean write;
        synchronized (this) {
            Saved s = saved.get(e.guid);
            if (startedGeneration != generation || s == null || !videoFile(e.guid).exists()) {
                //noinspection ResultOfMethodCallIgnored
                videoFile(e.guid).delete();
                return;
            }
            s.videoChecksum = e.videoChecksum;
            s.videoBytes = videoFile(e.guid).length();
            write = ++unsaved >= SAVE_EVERY;
        }
        if (write) save();
    }

    /** Writes any batched changes, e.g. once the queue of downloads is empty. */
    void flush() {
        boolean write;
        synchronized (this) {
            write = unsaved > 0;
        }
        if (write) save();
    }

    /** Saved photos and videos from the given albums, for the slideshow. */
    /** The newest saved photo (or video poster) in an album, for its cover, or null. */
    synchronized File cover(String album) {
        AlbumState a = albums.get(album);
        if (a == null) return null;
        Entry best = null;
        for (Entry e : a.entries) {
            if (saved.containsKey(e.guid) && (best == null || e.date > best.date)) best = e;
        }
        return best == null ? null : file(best.guid);
    }

    synchronized List<Slides.Item> items(Collection<String> enabled, boolean videos) {
        List<Slides.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : enabled) {
            AlbumState a = albums.get(id);
            if (a == null) continue;
            for (Entry e : a.entries) {
                Saved s = saved.get(e.guid);
                if (s == null || !seen.add(e.guid)) continue;
                File video = null;
                if (e.isVideo()) {
                    if (!videos) continue;
                    // Until the video itself is saved, its poster shows as a photo
                    if (e.videoChecksum.equals(s.videoChecksum)) video = videoFile(e.guid);
                }
                items.add(new Slides.Item("ic:" + e.guid, file(e.guid), video, s.landscape, e.date, a.name));
            }
        }
        return items;
    }

    private void load() {
        if (!index.exists()) return;
        try (InputStream in = new FileInputStream(index)) {
            byte[] data = new byte[(int) index.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            JSONObject json = new JSONObject(new String(data, 0, off, StandardCharsets.UTF_8));
            JSONArray list = json.optJSONArray("albums");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    AlbumState a = new AlbumState(o.getString("id"));
                    a.name = o.optString("name");
                    a.host = o.optString("host");
                    a.fetched = o.optLong("fetched");
                    a.entries = readEntries(o.optJSONArray("entries"));
                    albums.put(a.id, a);
                }
            } else if (!json.optString("album").isEmpty()) {
                // The single-album index from earlier versions
                AlbumState a = new AlbumState(json.getString("album"));
                a.host = json.optString("host");
                a.fetched = json.optLong("fetched");
                a.entries = readEntries(json.optJSONArray("entries"));
                albums.put(a.id, a);
            }
            JSONObject s = json.optJSONObject("saved");
            if (s != null) {
                for (java.util.Iterator<String> it = s.keys(); it.hasNext(); ) {
                    String guid = it.next();
                    JSONObject o = s.getJSONObject(guid);
                    if (!file(guid).exists()) continue;
                    Saved sv = new Saved(o.getString("checksum"), o.optBoolean("landscape", true), o.optLong("bytes"));
                    if (o.has("video") && videoFile(guid).exists()) {
                        sv.videoChecksum = o.getString("video");
                        sv.videoBytes = o.optLong("videoBytes");
                    }
                    saved.put(guid, sv);
                }
            }
        } catch (IOException | JSONException e) {
            // A damaged index only costs a fresh download
            albums.clear();
            saved.clear();
        }
    }

    private static List<Entry> readEntries(JSONArray list) throws JSONException {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            entries.add(new Entry(e.getString("guid"), e.getString("checksum"),
                    e.has("video") ? e.getString("video") : null, e.optLong("date")));
        }
        return entries;
    }

    /** Snapshots the state under the lock, then writes it without holding the lock. */
    private void save() {
        String text;
        try {
            synchronized (this) {
                unsaved = 0;
                JSONArray list = new JSONArray();
                for (AlbumState a : albums.values()) {
                    JSONArray entries = new JSONArray();
                    for (Entry e : a.entries) {
                        JSONObject o = new JSONObject().put("guid", e.guid).put("checksum", e.checksum).put("date", e.date);
                        if (e.videoChecksum != null) o.put("video", e.videoChecksum);
                        entries.put(o);
                    }
                    list.put(new JSONObject().put("id", a.id).put("name", a.name).put("host", a.host)
                            .put("fetched", a.fetched).put("entries", entries));
                }
                JSONObject s = new JSONObject();
                for (Map.Entry<String, Saved> e : saved.entrySet()) {
                    Saved v = e.getValue();
                    JSONObject o = new JSONObject().put("checksum", v.checksum).put("landscape", v.landscape)
                            .put("bytes", v.bytes);
                    if (v.videoChecksum != null) o.put("video", v.videoChecksum).put("videoBytes", v.videoBytes);
                    s.put(e.getKey(), o);
                }
                text = new JSONObject().put("albums", list).put("saved", s).toString();
            }
        } catch (JSONException e) {
            Log.w("Couldn't save photo index: " + e);
            return;
        }
        synchronized (index) {
            File tmp = new File(dir, "index.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w("Couldn't save photo index: " + e);
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(index);
        }
    }
}
