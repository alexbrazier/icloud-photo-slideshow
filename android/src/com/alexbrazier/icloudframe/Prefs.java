package com.alexbrazier.icloudframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Typed access to the settings screen's values, with the same defaults as preferences.xml. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context);
    }

    SharedPreferences raw() { return sp; }

    boolean iCloudEnabled() { return sp.getBoolean("src_icloud", true); }
    boolean localEnabled() { return sp.getBoolean("src_local", false); }
    boolean includeVideos() { return sp.getBoolean("videos", true); }
    boolean videoSound() { return sp.getBoolean("video_sound", false); }
    long intervalMs() { return number("interval", 60) * 1000L; }
    long fadeMs() { return number("fade", 1000); }
    String order() { return sp.getString("order", "shuffle"); }
    String orientation() { return sp.getString("orientation", "all"); }
    /** Photos the same shape as the screen: fill (crops), near (fills unless that crops a lot) or fit. */
    String fitSame() { return sp.getString("fit_same", legacyFit("near")); }
    /** Photos the other shape (portrait on a landscape frame): fit or fill. */
    String fitOther() { return sp.getString("fit_other", legacyFit("fit")); }
    /** Behind photos that don't fill the screen: blur, black, grey, white or custom. */
    String background() { return sp.getString("background", "blur"); }
    String backgroundColor() { return sp.getString("bg_color", "#202020"); }
    /** What to write on each photo: none, date, album or both. */
    String caption() { return sp.getString("caption", "none"); }
    String captionPos() { return sp.getString("caption_pos", "top_left"); }
    long cacheLimitBytes() { return number("cache_limit", 2048) * 1024L * 1024L; }

    boolean clockShow() { return sp.getBoolean("clock_show", true); }
    boolean clock24h() { return sp.getBoolean("clock_24h", true); }
    boolean clockDate() { return sp.getBoolean("clock_date", true); }
    String clockPos() { return sp.getString("clock_pos", "bottom_left"); }
    String clockSize() { return sp.getString("clock_size", "medium"); }

    boolean weatherShow() { return sp.getBoolean("weather_show", false); }
    String weatherLat() { return sp.getString("weather_lat", "").trim(); }
    String weatherLon() { return sp.getString("weather_lon", "").trim(); }
    boolean weatherFahrenheit() { return "f".equals(sp.getString("weather_units", "c")); }
    String weatherPos() { return sp.getString("weather_pos", "bottom_right"); }

    /** Screen brightness from 0.01 to 1, or -1 to leave it to Android. */
    float brightness() { return level("brightness", "system"); }
    boolean dimEnabled() { return sp.getBoolean("dim_enabled", false); }
    int dimStart() { return minutes(sp.getString("dim_start", "20:00")); }
    int dimEnd() { return minutes(sp.getString("dim_end", "07:00")); }
    float dimLevel() { return level("dim_level", "25"); }

    boolean nightEnabled() { return sp.getBoolean("night_enabled", false); }
    /** Minutes after midnight, or -1 when unparseable. */
    int nightOff() { return minutes(sp.getString("night_off", "22:00")); }
    int nightOn() { return minutes(sp.getString("night_on", "07:00")); }

    boolean updateAuto() { return sp.getBoolean("update_auto", true); }
    String updateRepo() { return sp.getString("update_repo", "alexbrazier/icloud-photo-slideshow").trim(); }

    /** The single "Fit to screen" setting from before fit_same and fit_other. */
    private String legacyFit(String fallback) {
        String old = sp.getString("fit", "smart");
        return "fill".equals(old) || "fit".equals(old) ? old : fallback;
    }

    private float level(String key, String fallback) {
        String v = sp.getString(key, fallback);
        if ("system".equals(v)) return -1f;
        try {
            return Math.max(1, Math.min(100, Integer.parseInt(v.trim()))) / 100f;
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    private long number(String key, long fallback) {
        try {
            return Long.parseLong(sp.getString(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int minutes(String hhmm) {
        String[] parts = hhmm.trim().split(":");
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return h >= 0 && h < 24 && m >= 0 && m < 60 ? h * 60 + m : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
