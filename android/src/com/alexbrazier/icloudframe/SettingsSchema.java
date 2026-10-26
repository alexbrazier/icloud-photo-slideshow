package com.alexbrazier.icloudframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The settings that can be changed from outside the settings screen (adb extras and the phone
 * setup page), with validation, so neither can store a value the app can't use.
 */
final class SettingsSchema {
    /** TIME and COLOR are text with a format, so the phone page can show a picker. */
    enum Type { BOOL, CHOICE, TEXT, TIME, COLOR }

    static final class Field {
        final String key, section, title;
        final Type type;
        final Object fallback;
        final int labelsId, valuesId;
        final Pattern pattern;
        final String hint;

        Field(String section, String key, String title, Type type, Object fallback, int labelsId, int valuesId,
              Pattern pattern, String hint) {
            this.section = section;
            this.key = key;
            this.title = title;
            this.type = type;
            this.fallback = fallback;
            this.labelsId = labelsId;
            this.valuesId = valuesId;
            this.pattern = pattern;
            this.hint = hint;
        }
    }

    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):[0-5]\\d");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d{1,3}(\\.\\d{1,8})?");
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final int MAX_TEXT = 120;
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    private static void bool(String section, String key, String title, boolean fallback) {
        FIELDS.put(key, new Field(section, key, title, Type.BOOL, fallback, 0, 0, null, null));
    }

    private static void choice(String section, String key, String title, String fallback, int labels, int values) {
        FIELDS.put(key, new Field(section, key, title, Type.CHOICE, fallback, labels, values, null, null));
    }

    private static void text(String section, String key, String title, String fallback, Pattern p, String hint) {
        FIELDS.put(key, new Field(section, key, title, Type.TEXT, fallback, 0, 0, p, hint));
    }

    private static void time(String section, String key, String title, String fallback) {
        FIELDS.put(key, new Field(section, key, title, Type.TIME, fallback, 0, 0, TIME, fallback));
    }

    static {
        String photos = "Photos", display = "Display", clock = "Clock", weather = "Weather", schedule = "Screen schedule", updates = "Updates";
        bool(photos, "src_icloud", "iCloud shared albums", true);
        bool(photos, "src_local", "Photos on the frame, SD card and USB stick", false);
        bool(photos, "videos", "Include videos", true);
        bool(photos, "video_sound", "Play video sound", false);
        choice(photos, "interval", "Time per photo", "60", R.array.interval_names, R.array.interval_values);
        choice(photos, "fade", "Transition", "1000", R.array.fade_names, R.array.fade_values);
        choice(photos, "order", "Order", "shuffle", R.array.order_names, R.array.order_values);
        choice(photos, "orientation", "Show", "all", R.array.orientation_names, R.array.orientation_values);
        choice(photos, "cache_limit", "Offline storage limit", "2048", R.array.cache_names, R.array.cache_values);
        choice(display, "fit_same", "Photos the same shape as the screen", "near", R.array.fit_same_names,
                R.array.fit_same_values);
        choice(display, "fit_other", "Photos the other shape", "fit", R.array.fit_other_names, R.array.fit_other_values);
        choice(display, "background", "Behind photos that don't fill the screen", "blur", R.array.background_names,
                R.array.background_values);
        FIELDS.put("bg_color", new Field(display, "bg_color", "Custom colour", Type.COLOR, "#202020", 0, 0, COLOR,
                "#202020"));
        choice(display, "caption", "Show on each photo", "none", R.array.caption_names, R.array.caption_values);
        choice(display, "caption_pos", "Caption position", "top_left", R.array.pos_names, R.array.pos_values);
        choice(display, "brightness", "Brightness", "system", R.array.brightness_names, R.array.brightness_values);
        bool(display, "dim_enabled", "Dim in the evening", false);
        time(display, "dim_start", "Dim from", "20:00");
        time(display, "dim_end", "Until", "07:00");
        choice(display, "dim_level", "Dimmed brightness", "25", R.array.dim_names, R.array.dim_values);
        bool(clock, "clock_show", "Show clock", true);
        bool(clock, "clock_24h", "24-hour time", true);
        bool(clock, "clock_date", "Show date", true);
        choice(clock, "clock_pos", "Position", "bottom_left", R.array.pos_names, R.array.pos_values);
        choice(clock, "clock_size", "Size", "medium", R.array.size_names, R.array.size_values);
        bool(weather, "weather_show", "Show weather", false);
        text(weather, "weather_place", "Location", "", null, "London");
        text(weather, "weather_lat", "Latitude", "", DECIMAL, "51.507");
        text(weather, "weather_lon", "Longitude", "", DECIMAL, "-0.128");
        choice(weather, "weather_units", "Units", "c", R.array.unit_names, R.array.unit_values);
        choice(weather, "weather_pos", "Position", "bottom_right", R.array.pos_names, R.array.pos_values);
        bool(schedule, "night_enabled", "Turn the screen off at night", false);
        time(schedule, "night_off", "Off at", "22:00");
        time(schedule, "night_on", "On at", "07:00");
        bool(updates, "update_auto", "Install updates automatically", true);
        text(updates, "update_repo", "Update source (GitHub repository)", "alexbrazier/icloud-photo-slideshow", REPO,
                "owner/repo");
    }

    private SettingsSchema() {}

    /**
     * Validates and stores one value. Returns an error message, or null when it was saved.
     * Booleans accept true/false; everything else is a string.
     */
    static String put(Context context, SharedPreferences.Editor edit, String key, Object value) {
        // "album" adds an album (and switches it on), for adb and older setups
        if (key.equals("album")) return value == null ? "Album: missing value" : Albums.add(context, String.valueOf(value));
        Field f = FIELDS.get(key);
        if (f == null) return "Unknown setting " + key;
        if (value == null || value == JSONObject.NULL) return f.title + ": missing value";
        switch (f.type) {
            case BOOL:
                if (value instanceof Boolean) {
                    edit.putBoolean(key, (Boolean) value);
                    return null;
                }
                String s = String.valueOf(value).trim().toLowerCase();
                if (!s.equals("true") && !s.equals("false")) return f.title + ": expected true or false";
                edit.putBoolean(key, s.equals("true"));
                return null;
            case CHOICE:
                String v = String.valueOf(value).trim();
                if (!Arrays.asList(context.getResources().getStringArray(f.valuesId)).contains(v)) {
                    return f.title + ": not one of the options";
                }
                edit.putString(key, v);
                return null;
            default:
                String t = String.valueOf(value).trim();
                if (t.length() > MAX_TEXT) return f.title + ": too long";
                if (!t.isEmpty() && f.pattern != null && !f.pattern.matcher(t).matches()) {
                    return f.title + ": should look like " + f.hint;
                }
                if (f.type == Type.COLOR) t = t.toUpperCase(java.util.Locale.ROOT);
                if (key.equals("weather_lat") && !t.isEmpty() && Math.abs(Double.parseDouble(t)) > 90) {
                    return "Latitude should be between -90 and 90";
                }
                if (key.equals("weather_lon") && !t.isEmpty() && Math.abs(Double.parseDouble(t)) > 180) {
                    return "Longitude should be between -180 and 180";
                }
                edit.putString(key, t);
                return null;
        }
    }

    /** Current values and the options for each, for the phone setup page. */
    static JSONObject describe(Context context) throws JSONException {
        SharedPreferences sp = new Prefs(context).raw();
        Resources res = context.getResources();
        JSONArray fields = new JSONArray();
        for (Field f : FIELDS.values()) {
            JSONObject o = new JSONObject().put("key", f.key).put("section", f.section).put("title", f.title)
                    .put("type", f.type.name().toLowerCase());
            switch (f.type) {
                case BOOL:
                    o.put("value", sp.getBoolean(f.key, (Boolean) f.fallback));
                    break;
                case CHOICE:
                    o.put("value", sp.getString(f.key, (String) f.fallback));
                    String[] labels = res.getStringArray(f.labelsId), values = res.getStringArray(f.valuesId);
                    JSONArray choices = new JSONArray();
                    for (int i = 0; i < values.length; i++) {
                        choices.put(new JSONObject().put("value", values[i]).put("label", labels[i]));
                    }
                    o.put("choices", choices);
                    break;
                default:
                    o.put("value", sp.getString(f.key, (String) f.fallback));
                    if (f.hint != null) o.put("hint", f.hint);
            }
            fields.put(o);
        }
        return new JSONObject().put("fields", fields);
    }

    static List<String> keys() {
        return new ArrayList<>(FIELDS.keySet());
    }
}
