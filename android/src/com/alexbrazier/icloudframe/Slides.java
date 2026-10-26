package com.alexbrazier.icloudframe;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The slideshow's order, planned from every iCloud photo (saved or not) and the SD card/USB
 * photos. iCloud photos are downloaded just before they're due; one that isn't ready in time is
 * swapped with the next one that is, so it comes round again shortly after.
 */
final class Slides {
    static final class Item {
        final String id;
        /** The iCloud photo's guid, or null for SD card/USB files, which are always ready. */
        final String guid;
        /** The photo, or a video's poster; null for a local video, whose first frame is used. */
        final File file;
        /** The video to play, or null for a photo. */
        final File video;
        final boolean landscape;
        /** When it was taken (0 if unknown), and the album or folder it's from, for captions. */
        final long date;
        final String album;

        Item(String id, File file, File video, boolean landscape, long date, String album) {
            this(id, null, file, video, landscape, date, album);
        }

        Item(String id, String guid, File file, File video, boolean landscape, long date, String album) {
            this.id = id;
            this.guid = guid;
            this.file = file;
            this.video = video;
            this.landscape = landscape;
            this.date = date;
            this.album = album;
        }

        Item withVideo(File video) {
            return new Item(id, guid, file, video, landscape, date, album);
        }
    }

    /** Whether an item can be shown now (for iCloud photos: whether it's downloaded). */
    interface Ready {
        boolean test(Item item);
    }

    private final Random random = new Random();
    private List<Item> order = new ArrayList<>();
    private int pos = -1;

    boolean isEmpty() { return order.isEmpty(); }
    int size() { return order.size(); }

    /**
     * Rebuilds the order, keeping the current photo (if it's still there) as the current one so
     * the screen doesn't jump.
     */
    void rebuild(List<Item> items, String order, String filter, boolean screenLandscape) {
        Item current = current();
        List<Item> list = new ArrayList<>();
        for (Item item : items) {
            boolean keep;
            switch (filter) {
                case "landscape": keep = item.landscape; break;
                case "portrait": keep = !item.landscape; break;
                case "match": keep = item.landscape == screenLandscape; break;
                default: keep = true;
            }
            if (keep) list.add(item);
        }
        switch (order) {
            case "newest": Collections.sort(list, (a, b) -> Long.compare(b.date, a.date)); break;
            case "oldest": Collections.sort(list, (a, b) -> Long.compare(a.date, b.date)); break;
            default: Collections.shuffle(list, random);
        }
        this.order = list;
        pos = -1;
        if (current == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).id.equals(current.id)) continue;
            if ("shuffle".equals(order)) {
                // Move it to the front so the rest of the new shuffle follows it
                list.add(0, list.remove(i));
                pos = 0;
            } else {
                // Sorted orders carry on from where the current photo sits
                pos = i;
            }
            break;
        }
    }

    Item current() { return pos >= 0 && pos < order.size() ? order.get(pos) : null; }

    /**
     * Advances to the next item that's ready, reshuffling after each full pass like the web app.
     * One that isn't ready swaps places with the next one that is, so it isn't skipped for a whole
     * pass. Null (staying put) when nothing is ready yet.
     */
    Item next(String orderMode, Ready ready) {
        if (order.isEmpty()) return null;
        int start = pos;
        for (int tries = 0; tries < order.size(); tries++) {
            pos++;
            if (pos >= order.size()) {
                if ("shuffle".equals(orderMode) && order.size() > 2) {
                    Item last = order.get(order.size() - 1);
                    Collections.shuffle(order, random);
                    // Don't show the same photo twice in a row across passes
                    if (order.get(0) == last) Collections.swap(order, 0, order.size() - 1);
                }
                pos = 0;
            }
            if (ready.test(order.get(pos))) return order.get(pos);
            for (int j = pos + 1; j < order.size(); j++) {
                if (ready.test(order.get(j))) {
                    Collections.swap(order, pos, j);
                    return order.get(pos);
                }
            }
        }
        pos = start;
        return null;
    }

    /** Back to the previous item that's ready. */
    Item previous(Ready ready) {
        if (order.isEmpty()) return null;
        int start = pos;
        for (int tries = 0; tries < order.size(); tries++) {
            pos = pos <= 0 ? order.size() - 1 : pos - 1;
            if (ready.test(order.get(pos))) return order.get(pos);
        }
        pos = start;
        return null;
    }

    /** The next few items after the current one, to download ahead of time. */
    List<Item> upcoming(int count) {
        List<Item> list = new ArrayList<>();
        for (int i = 1; i <= count && i <= order.size(); i++) {
            // pos is -1 before the first photo, so this never goes negative
            list.add(order.get((pos + i) % order.size()));
        }
        return list;
    }

    /** Swaps in a newer copy of the current item, e.g. once its video has downloaded. */
    void replaceCurrent(Item item) {
        if (pos >= 0 && pos < order.size() && order.get(pos).id.equals(item.id)) order.set(pos, item);
    }
}
