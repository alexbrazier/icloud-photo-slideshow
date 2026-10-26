package com.alexbrazier.icloudframe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Background thread that keeps the saved photos in step with the albums: reloads each album's
 * list hourly, then downloads missing photos one at a time (newest first) as screen-sized
 * copies, then videos.
 */
final class Sync implements Runnable {
    interface Listener {
        void onPhotosChanged();
    }

    private static final long LIST_REFRESH_MS = 60 * 60 * 1000L;
    private static final long MIN_RETRY_MS = 30 * 1000L;
    private static final long MAX_RETRY_MS = 10 * 60 * 1000L;
    private static final long MAX_PHOTO_DOWNLOAD = 60L * 1024 * 1024;
    private static final long MAX_VIDEO_DOWNLOAD = 400L * 1024 * 1024;
    // Leave the frame's own storage some room
    private static final long MIN_FREE_SPACE = 300L * 1024 * 1024;

    private final Context context;
    private final PhotoStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Object lock = new Object();
    private final Map<String, Integer> failures = new HashMap<>();
    // When to retry an album that failed to load, so one bad album doesn't hold the others up
    private final Map<String, Long> retryAt = new HashMap<>();
    // Albums whose downloads iCloud asked to slow down, until when; the others carry on
    private final Map<String, Long> pausedUntil = new HashMap<>();
    private boolean wakeRequested;
    private boolean forceList;
    private long retryMs = MIN_RETRY_MS;
    // Downloads back off separately, since album checks succeeding would otherwise reset it
    private long downloadRetryMs = MIN_RETRY_MS;
    // Apple's "bandwidth exceeded" and other slow-down replies: 5 minutes, doubling up to an hour
    private static final long RATE_LIMIT_RETRY_MS = 5 * 60 * 1000L;
    private static final long MAX_RATE_LIMIT_RETRY_MS = 60 * 60 * 1000L;
    private long rateLimitMs = RATE_LIMIT_RETRY_MS;
    // Photos are only downloaded shortly before the slideshow needs them (see want), rather than
    // saving whole albums at once, which gets "slow down" replies from Apple. Each is two
    // requests (its address, then the file), so flicking through quickly is spaced out too.
    private static final long DOWNLOAD_GAP_MS = 2000L;
    private volatile List<String> wanted = new ArrayList<>();
    // Goes up when an album's list of photos changes, so the slideshow knows to replan
    private volatile int listVersion;
    private volatile String status = "Starting";
    // Whether the status is something to point out (offline, iCloud busy...) rather than routine
    private volatile boolean problem;
    private volatile String lastError = "";

    Sync(Context context, PhotoStore store) {
        this.context = context;
        this.store = store;
    }

    void start() {
        Thread t = new Thread(this, "sync");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Re-checks settings and the network now, e.g. after a settings change. */
    void wake(boolean reloadList) {
        synchronized (lock) {
            wakeRequested = true;
            forceList |= reloadList;
            lock.notifyAll();
        }
    }

    /** The photos the slideshow shows next (in order) that aren't saved yet: these are downloaded. */
    void want(List<String> guids) {
        if (guids.equals(wanted)) return;
        wanted = new ArrayList<>(guids);
        wake(false);
    }

    int listVersion() { return listVersion; }

    private void setStatus(String text, boolean isProblem) {
        status = text;
        problem = isProblem;
    }

    /** A short status for the phone page and the loading screen. */
    String status() {
        String error = lastError.isEmpty() ? "" : friendly(lastError);
        return problem && !error.isEmpty() && !status.startsWith("iCloud is busy") ? status + ". " + error : status;
    }

    /** Something worth pointing out on the slideshow's controls, or "" when all is well. */
    String problem() {
        return problem ? status : "";
    }

    /** The full picture, for Settings → Status. */
    String details() {
        List<String> enabled = Albums.enabledIds(context);
        int total = store.totalCount(enabled), saved = store.totalSaved(enabled);
        String s = status;
        if (total > 0) {
            s += "\n" + total + (total == 1 ? " photo" : " photos") + " in your albums, " + saved + " saved on the frame";
        }
        // A current problem is already the status
        if (!problem && !lastError.isEmpty()) s += "\nLast problem: " + friendly(lastError);
        return s;
    }

    private static String time(long millis) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(new java.util.Date(millis));
    }

    /** Turns technical errors into something the frame's owner can act on. */
    static String friendly(String error) {
        String e = error == null ? "" : error;
        String lower = e.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("HTTP 509") || e.contains("HTTP 429") || e.contains("HTTP 503")) {
            return "iCloud asked the frame to slow down for a while";
        }
        if (lower.contains("unable to resolve") || lower.contains("unknownhost") || lower.contains("timed out")
                || lower.contains("timeout") || lower.contains("failed to connect") || lower.contains("connection")
                || lower.contains("network is unreachable")) {
            return "Couldn't reach iCloud. Check the frame's internet connection";
        }
        if (lower.contains("certpath") || lower.contains("trust anchor") || lower.contains("ssl")
                || lower.contains("handshake") || lower.contains("certificate")) {
            return "Couldn't make a secure connection. Check the frame's date and time";
        }
        if (lower.contains("too large")) return "A photo was too large to download";
        if (lower.contains("unexpected") || e.matches(".*HTTP \\d+.*")) {
            return "iCloud had a problem. The frame will try again";
        }
        // Album messages ("Album not found...") are already written for people
        int colon = e.indexOf(": ");
        return colon > 0 && e.startsWith("Couldn't") ? e.substring(colon + 2) : e;
    }

    @Override
    public void run() {
        while (true) {
            long waitMs;
            try {
                waitMs = step();
            } catch (Throwable t) {
                Log.w("Sync step failed: " + t);
                lastError = String.valueOf(t.getMessage());
                waitMs = MIN_RETRY_MS;
            }
            synchronized (lock) {
                if (!wakeRequested && waitMs > 0) {
                    try {
                        lock.wait(waitMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                wakeRequested = false;
            }
        }
    }

    /** Does one unit of work, returning how long to wait before the next. */
    private long step() {
        Prefs prefs = new Prefs(context);
        // Removed albums lose their photos; switched-off ones keep them for switching back
        store.keepAlbums(Albums.allIds(context));
        List<String> enabled = Albums.enabledIds(context);
        if (!prefs.iCloudEnabled()) {
            setStatus("iCloud albums are off", false);
            return LIST_REFRESH_MS;
        }
        if (enabled.isEmpty()) {
            setStatus("No album switched on", true);
            return LIST_REFRESH_MS;
        }
        if (!Net.online(context)) {
            setStatus("Offline, showing saved photos", true);
            return 60_000;
        }
        if (!Clock.isSet()) {
            // HTTPS can't work until the frame has the time (no clock battery)
            setStatus("Waiting for the clock to be set", true);
            return 15_000;
        }

        boolean force;
        synchronized (lock) {
            force = forceList;
            forceList = false;
        }
        if (force) {
            failures.clear();
            retryAt.clear();
            // pausedUntil stays: asking again sooner than iCloud said only makes it last longer
        }
        long now = System.currentTimeMillis(), nextCheck = LIST_REFRESH_MS;
        boolean anyFailed = false;
        for (String album : enabled) {
            Long retry = retryAt.get(album);
            if (retry != null && now < retry) {
                nextCheck = Math.min(nextCheck, retry - now);
                continue;
            }
            long age = now - store.fetched(album);
            // A check "from the future" means the clock was wrong when it was saved
            if (force || retry != null || age > LIST_REFRESH_MS || age < 0 || store.count(album) == 0) {
                if (loadAlbum(album)) {
                    retryAt.remove(album);
                } else {
                    anyFailed = true;
                    // Keep saving photos from the albums that did load meanwhile
                    retryAt.put(album, now + retryMs);
                    nextCheck = Math.min(nextCheck, retryMs);
                }
            } else {
                nextCheck = Math.min(nextCheck, LIST_REFRESH_MS - age);
            }
        }
        retryMs = anyFailed ? Math.min(retryMs * 2, MAX_RETRY_MS) : MIN_RETRY_MS;

        boolean videos = prefs.includeVideos();
        PhotoStore.Download next = null;
        long pausedFor = Long.MAX_VALUE;
        Set<String> skip = skipped();
        for (String guid : wanted) {
            if (skip.contains(guid)) continue;
            PhotoStore.Download d = store.download(guid, videos);
            if (d == null || !enabled.contains(d.album)) continue;
            Long until = pausedUntil.get(d.album);
            if (until != null && until > now) {
                pausedFor = Math.min(pausedFor, until - now);
                continue;
            }
            next = d;
            break;
        }
        if (next == null && pausedFor != Long.MAX_VALUE) {
            store.flush();
            setStatus("iCloud is busy, trying again at " + time(now + pausedFor), true);
            return pausedFor;
        }
        long limit = prefs.cacheLimitBytes();
        // Make room for newer photos by dropping switched-off albums' and then the oldest photos
        if (store.evict(enabled, next == null ? null : next.entry, limit)) notifyChanged();
        if (next == null) {
            store.flush();
            setStatus("Up to date", false);
            return Math.min(LIST_REFRESH_MS, Math.max(60_000, nextCheck));
        }
        if (store.savedBytes() > limit || store.usableSpace() < MIN_FREE_SPACE) {
            store.flush();
            setStatus("Storage full, showing saved photos", true);
            return Math.min(LIST_REFRESH_MS, Math.max(60_000, nextCheck));
        }
        return download(next);
    }

    /** Reloads one album's list. Returns false if it failed (recorded against the album). */
    private boolean loadAlbum(String album) {
        setStatus("Checking albums", false);
        try {
            ICloud icloud = new ICloud(album, store.host(album));
            ICloud.Album result = icloud.album();
            List<PhotoStore.Entry> entries = new ArrayList<>();
            for (ICloud.Photo p : result.photos) {
                ICloud.Derivative image = ICloud.pick(p, FrameApp.screenLong(), FrameApp.screenShort());
                ICloud.Derivative video = p.isVideo() ? ICloud.pickVideo(p, FrameApp.screenShort()) : null;
                entries.add(new PhotoStore.Entry(p.guid, image.checksum, video == null ? null : video.checksum,
                        p.dateCreated, image.width >= image.height));
            }
            // An empty reply keeps what's saved rather than wiping the frame
            boolean changed = false;
            if (!entries.isEmpty() || store.count(album) == 0) {
                changed = store.setEntries(album, entries, icloud.host(), result.name);
            }
            if (changed) listVersion++;
            lastError = "";
            Log.i("Album \"" + result.name + "\" has " + entries.size() + " items");
            if (changed) notifyChanged();
            return true;
        } catch (IOException e) {
            String message = e.getMessage();
            store.setError(album, message);
            Log.w("Couldn't load album: " + message);
            lastError = message;
            return false;
        }
    }

    private long download(PhotoStore.Download next) {
        PhotoStore.Entry e = next.entry;
        setStatus(next.video ? "Downloading a video" : "Downloading photos", false);
        File tmp = new File(context.getCacheDir(), "download.tmp");
        int generation = store.generation();
        try {
            ICloud icloud = new ICloud(next.album, store.host(next.album));
            if (next.video) {
                Net.download(icloud.downloadUrl(e.guid, e.videoChecksum), tmp, MAX_VIDEO_DOWNLOAD, false);
                File dest = store.videoFile(e.guid);
                if (!tmp.renameTo(dest)) throw new IOException("Couldn't save video");
                store.markVideoSaved(e, generation);
            } else {
                Net.download(icloud.downloadUrl(e.guid, e.checksum), tmp, MAX_PHOTO_DOWNLOAD, false);
                int[] size = Images.saveScaled(tmp, store.file(e.guid), FrameApp.screenLong());
                store.markSaved(e, size[0] >= size[1], generation);
            }
            lastError = "";
            downloadRetryMs = MIN_RETRY_MS;
            rateLimitMs = RATE_LIMIT_RETRY_MS;
            notifyChanged();
            return DOWNLOAD_GAP_MS;
        } catch (IOException | RuntimeException | OutOfMemoryError ex) {
            Integer n = failures.get(e.guid);
            failures.put(e.guid, n == null ? 1 : n + 1);
            String message = (next.video ? "Couldn't save a video: " : "Couldn't save a photo: ") + ex.getMessage();
            Log.w(message);
            lastError = message;
            String why = String.valueOf(ex.getMessage());
            boolean slowDown = why.contains("HTTP 509") || why.contains("HTTP 429") || why.contains("HTTP 503");
            if (slowDown) {
                // Pause just this album; the next step carries on with any others
                pausedUntil.put(next.album, System.currentTimeMillis() + rateLimitMs);
                Log.i("iCloud asked to slow down; pausing for " + rateLimitMs / 60000 + " minutes");
                rateLimitMs = Math.min(rateLimitMs * 2, MAX_RATE_LIMIT_RETRY_MS);
                return 1000;
            }
            setStatus("Couldn't download a photo, trying again soon", true);
            long wait = downloadRetryMs;
            downloadRetryMs = Math.min(wait * 2, MAX_RETRY_MS);
            return wait;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Items that failed three times are skipped until the next album check. */
    private Set<String> skipped() {
        Set<String> skip = new HashSet<>();
        for (Map.Entry<String, Integer> e : failures.entrySet()) {
            if (e.getValue() >= 3) skip.add(e.getKey());
        }
        return skip;
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onPhotosChanged();
        });
    }
}
