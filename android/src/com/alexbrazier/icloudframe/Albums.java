package com.alexbrazier.icloudframe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The saved iCloud albums, each switched on or off. Several can be on at once, and an album
 * that's off keeps its saved photos so switching back is instant. Names come from iCloud and
 * live in the PhotoStore.
 */
final class Albums {
    static final class Album {
        final String id;
        final boolean on;

        Album(String id, boolean on) {
            this.id = id;
            this.on = on;
        }
    }

    private static final String KEY = "albums";
    private static final int MAX_ALBUMS = 50;

    private Albums() {}

    static synchronized List<Album> list(Context context) {
        SharedPreferences sp = new Prefs(context).raw();
        List<Album> albums = new ArrayList<>();
        String raw = sp.getString(KEY, null);
        if (raw == null) {
            // Earlier versions had a single album setting
            String old = ICloud.parseAlbumId(sp.getString("album", ""));
            if (!old.isEmpty()) albums.add(new Album(old, true));
            save(context, albums);
            return albums;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String id = ICloud.parseAlbumId(o.optString("id"));
                if (!id.isEmpty()) albums.add(new Album(id, o.optBoolean("on", true)));
            }
        } catch (JSONException e) {
            Log.w("Album list unreadable: " + e);
        }
        return albums;
    }

    static List<String> enabledIds(Context context) {
        List<String> ids = new ArrayList<>();
        for (Album a : list(context)) if (a.on) ids.add(a.id);
        return ids;
    }

    static List<String> allIds(Context context) {
        List<String> ids = new ArrayList<>();
        for (Album a : list(context)) ids.add(a.id);
        return ids;
    }

    private static void save(Context context, List<Album> albums) {
        JSONArray array = new JSONArray();
        try {
            for (Album a : albums) array.put(new JSONObject().put("id", a.id).put("on", a.on));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        new Prefs(context).raw().edit().putString(KEY, array.toString()).commit();
    }

    /** Adds an album (switched on), or switches it on if it's already saved. Returns an error or null. */
    static synchronized String add(Context context, String linkOrId) {
        String id = ICloud.parseAlbumId(linkOrId);
        if (id.isEmpty()) return "That doesn't look like a shared album link";
        List<Album> albums = list(context);
        for (int i = 0; i < albums.size(); i++) {
            if (albums.get(i).id.equals(id)) {
                albums.set(i, new Album(id, true));
                save(context, albums);
                return null;
            }
        }
        if (albums.size() >= MAX_ALBUMS) return "That's the most albums a frame can hold";
        albums.add(new Album(id, true));
        save(context, albums);
        return null;
    }

    /** Switches an album on or off. Returns whether it was found. */
    static synchronized boolean setOn(Context context, String id, boolean on) {
        List<Album> albums = list(context);
        for (int i = 0; i < albums.size(); i++) {
            if (albums.get(i).id.equals(id)) {
                albums.set(i, new Album(id, on));
                save(context, albums);
                return true;
            }
        }
        return false;
    }

    /** Removes an album; its saved photos are deleted on the next sync. Returns whether it was found. */
    static synchronized boolean remove(Context context, String id) {
        List<Album> albums = list(context);
        for (int i = 0; i < albums.size(); i++) {
            if (albums.get(i).id.equals(id)) {
                albums.remove(i);
                save(context, albums);
                // Its photos are deleted now rather than on the next sync, in the background
                // because thousands of files take a while (photos also in another album stay)
                List<String> keep = allIds(context);
                new Thread(() -> FrameApp.get().store().keepAlbums(keep), "remove-album").start();
                return true;
            }
        }
        return false;
    }
}
