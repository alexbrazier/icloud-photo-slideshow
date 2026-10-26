package com.alexbrazier.icloudframe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;

/** Settings, opened from the slideshow's controls or by long-pressing the photo. */
public final class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        if (state == null) {
            getFragmentManager().beginTransaction().replace(android.R.id.content, new SettingsFragment()).commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    public static final class SettingsFragment extends PreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        // Set while this screen writes the looked-up place, so that write isn't looked up again
        private boolean writingPlace;
        private final Runnable refreshStatus = new Runnable() {
            @Override
            public void run() {
                Preference status = findPreference("status");
                if (status != null) status.setSummary(FrameApp.get().sync().status());
                handler.postDelayed(this, 2000);
            }
        };

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            addPreferencesFromResource(R.xml.preferences);
            updateSummaries();
        }

        @Override
        public void onResume() {
            super.onResume();
            updateSummaries();
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
            handler.post(refreshStatus);
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
            handler.removeCallbacks(refreshStatus);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            updateSummaries();
            boolean reload = "src_icloud".equals(key) || "videos".equals(key);
            FrameApp.get().sync().wake(reload);
            if (key != null && key.startsWith("night_")) Schedule.update(getActivity());
            if ("weather_place".equals(key) && !writingPlace) lookUpPlace(sp.getString(key, ""));
        }

        /** Fills in the coordinates for a typed town or city. */
        private void lookUpPlace(String place) {
            if (place.trim().isEmpty()) return;
            setSummary("weather_place", "Looking up " + place.trim() + "…");
            android.app.Activity activity = getActivity();
            new Thread(() -> {
                String[] found;
                try {
                    found = Weather.geocode(place);
                } catch (Exception e) {
                    found = new String[0];
                }
                String[] result = found;
                handler.post(() -> {
                    if (!isAdded()) return;
                    if (result == null || result.length == 0) {
                        toast(result == null ? "Couldn't find " + place.trim() : "Couldn't look it up. Is the frame online?");
                        updateSummaries();
                        return;
                    }
                    writingPlace = true;
                    new Prefs(activity).raw().edit().putString("weather_place", result[0])
                            .putString("weather_lat", result[1]).putString("weather_lon", result[2])
                            .putBoolean("weather_show", true).apply();
                    writingPlace = false;
                    // The text fields hold their own copies
                    ((EditTextPreference) findPreference("weather_place")).setText(result[0]);
                    ((EditTextPreference) findPreference("weather_lat")).setText(result[1]);
                    ((EditTextPreference) findPreference("weather_lon")).setText(result[2]);
                    ((android.preference.SwitchPreference) findPreference("weather_show")).setChecked(true);
                    updateSummaries();
                });
            }).start();
        }

        private void updateSummaries() {
            Prefs prefs = new Prefs(getActivity());
            List<Albums.Album> albums = Albums.list(getActivity());
            StringBuilder names = new StringBuilder();
            int on = 0;
            for (Albums.Album a : albums) {
                if (!a.on) continue;
                on++;
                String name = FrameApp.get().store().name(a.id);
                if (names.length() > 0) names.append(", ");
                names.append(name.isEmpty() ? "Album " + a.id.substring(0, 6) + "…" : name);
            }
            setSummary("albums", albums.isEmpty() ? "None yet: tap to add one"
                    : on == 0 ? albums.size() + " saved, none switched on"
                    : "Showing " + names + (albums.size() > on ? " (" + albums.size() + " saved)" : ""));
            setSummary("weather_place", text("weather_place", "Type a town or city to fill in the coordinates"));
            setSummary("weather_lat", text("weather_lat", "Not set"));
            setSummary("weather_lon", text("weather_lon", "Not set"));
            setSummary("night_off", prefs.nightOff() < 0 ? "Not a valid time" : text("night_off", ""));
            setSummary("night_on", prefs.nightOn() < 0 ? "Not a valid time" : text("night_on", ""));
            setSummary("update_repo", prefs.updateRepo());
            setSummary("bg_color", "custom".equals(prefs.background()) ? text("bg_color", "#202020")
                    : "Used when \"Behind photos\" is set to Custom colour");
            setSummary("dim_start", prefs.dimStart() < 0 ? "Not a valid time" : text("dim_start", ""));
            setSummary("dim_end", prefs.dimEnd() < 0 ? "Not a valid time" : text("dim_end", ""));
            setSummary("check_update", "Installed version " + Updater.currentVersion(getActivity())
                    + (Device.isOwner(getActivity()) ? " · installs silently (device owner)" : ""));
            setSummary("release_owner", Device.isOwner(getActivity())
                    ? "This app is the device owner. Tap to give that up, e.g. before uninstalling it"
                    : "Not the device owner");
            setSummary("screen_off_permission", Schedule.canTurnOff(getActivity())
                    ? "Allowed: the screen turns fully off, and a tap of the power button wakes it for 5 minutes"
                    : "Not allowed: the screen goes black and dim instead. Tap to allow.");
        }

        private String text(String key, String fallback) {
            Preference p = findPreference(key);
            String v = p instanceof EditTextPreference ? ((EditTextPreference) p).getText() : null;
            return v == null || v.trim().isEmpty() ? fallback : v.trim();
        }

        private void setSummary(String key, String summary) {
            Preference p = findPreference(key);
            if (p != null) p.setSummary(summary);
        }

        @Override
        public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference pref) {
            String key = pref.getKey();
            if (key == null) return super.onPreferenceTreeClick(screen, pref);
            switch (key) {
                case "sync_now":
                    FrameApp.get().sync().wake(true);
                    toast("Checking the album");
                    return true;
                case "clear_cache":
                    new AlertDialog.Builder(getActivity())
                            .setMessage("Delete the saved photos? They'll download again from the album.")
                            .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                                // Thousands of files take a while to delete, so not on the main thread
                                FrameApp.get().store().clear();
                                FrameApp.get().sync().wake(true);
                            }).start())
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                case "albums":
                    startActivity(new Intent(getActivity(), AlbumsActivity.class));
                    return true;
                case "phone_setup":
                    startActivity(new Intent(getActivity(), SetupActivity.class));
                    return true;
                case "screen_off_permission":
                    if (!Schedule.canTurnOff(getActivity())) {
                        startActivity(new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, Device.admin(getActivity()))
                                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "Lets the frame turn its screen off at night and back on in the morning."));
                    }
                    return true;
                case "wifi":
                    startActivity(new Intent(getActivity(), WifiActivity.class));
                    return true;
                case "android_settings":
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                    return true;
                case "other_home":
                    new AlertDialog.Builder(getActivity())
                            .setMessage("Let another app be the Home screen? To come back, open iCloud Frame from it and choose it as Home again.")
                            .setPositiveButton("Continue", (d, w) -> {
                                Device.releaseHome(getActivity());
                                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                case "release_owner":
                    if (!Device.isOwner(getActivity())) return true;
                    new AlertDialog.Builder(getActivity())
                            .setMessage("Stop being the device owner? Updates will ask before installing, the lock screen "
                                    + "can come back, and the app can be uninstalled. Making it device owner again needs "
                                    + "adb and a frame with no accounts, which may mean a factory reset.")
                            .setPositiveButton("Release", (d, w) -> {
                                Device.releaseOwner(getActivity());
                                updateSummaries();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                case "check_update":
                    pref.setSummary("Checking…");
                    Updater.checkNow(getActivity(), message -> {
                        if (isAdded()) pref.setSummary(message);
                    });
                    return true;
                default:
                    return super.onPreferenceTreeClick(screen, pref);
            }
        }

        private void toast(String message) {
            Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
