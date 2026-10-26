package com.alexbrazier.icloudframe;

import android.media.MediaMetadataRetriever;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Photos and videos on inserted SD cards and USB sticks, and in the frame's own Pictures, DCIM
 * and Movies folders, like the stock frame app's card slideshow.
 */
final class LocalPhotos {
    private static final int MAX_FILES = 5000;
    private static final int MAX_DEPTH = 6;

    private LocalPhotos() {}

    /** Slow (reads every file's header), so call it off the main thread. */
    static List<Slides.Item> scan(boolean videos) {
        List<Slides.Item> items = new ArrayList<>();
        for (File root : roots()) walk(root, 0, items, videos);
        Log.i("Found " + items.size() + " photos and videos on the frame, cards and drives");
        return items;
    }

    private static List<File> roots() {
        List<File> roots = new ArrayList<>();
        addVolumes(new File("/storage"), roots);
        // Some frames mount USB sticks under /mnt instead
        addVolumes(new File("/mnt/media_rw"), roots);
        addVolumes(new File("/mnt/usb_storage"), roots);
        File internal = Environment.getExternalStorageDirectory();
        for (String folder : new String[] {"DCIM", "Pictures", "Movies"}) {
            File f = new File(internal, folder);
            if (f.isDirectory() && f.canRead()) roots.add(f);
        }
        return roots;
    }

    private static void addVolumes(File parent, List<File> roots) {
        File[] children = parent.listFiles();
        if (children == null) return;
        for (File f : children) {
            String name = f.getName();
            if (name.equals("emulated") || name.equals("self") || name.equals("sdcard0")) continue;
            if (f.isDirectory() && f.canRead()) roots.add(f);
        }
    }

    private static void walk(File dir, int depth, List<Slides.Item> items, boolean videos) {
        if (depth > MAX_DEPTH || items.size() >= MAX_FILES) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (items.size() >= MAX_FILES) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (!name.equalsIgnoreCase("Android") && !name.equalsIgnoreCase("LOST.DIR")) {
                    walk(f, depth + 1, items, videos);
                }
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
                int[] size = Images.size(f);
                if (size != null) {
                    long taken = Images.takenAt(f);
                    items.add(new Slides.Item("local:" + f.getPath(), f, null, size[0] >= size[1],
                            taken > 0 ? taken : f.lastModified(), dir.getName()));
                }
            } else if (videos && (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")
                    || lower.endsWith(".3gp") || lower.endsWith(".webm") || lower.endsWith(".mkv"))) {
                Boolean landscape = videoLandscape(f);
                if (landscape != null) {
                    items.add(new Slides.Item("local:" + f.getPath(), null, f, landscape, f.lastModified(), dir.getName()));
                }
            }
        }
    }

    /** Whether a video is wider than tall once rotated, or null if Android can't read it. */
    private static Boolean videoLandscape(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            boolean turned = "90".equals(rot) || "270".equals(rot);
            return turned ? h >= w : w >= h;
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // Already released
            }
        }
    }
}
