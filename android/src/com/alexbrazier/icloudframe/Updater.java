package com.alexbrazier.icloudframe;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs newer versions from GitHub releases tagged android-v<versionCode> with an .apk asset.
 * The APK must have this app's package name and signing key, so only builds signed with the
 * same keystore can replace it. Device owners install silently; otherwise Android asks.
 */
final class Updater {
    private static final long CHECK_EVERY_MS = 24 * 60 * 60 * 1000L;
    private static final Pattern TAG = Pattern.compile("android-v(\\d+)");
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    interface Callback {
        void done(String message);
    }

    private static Handler worker;

    private Updater() {}

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread t = new HandlerThread("updates");
            t.start();
            worker = new Handler(t.getLooper());
        }
        return worker;
    }

    /**
     * Checks once a day while automatic updates are on. The time of the last check is saved, so
     * restarting the app (or the frame) doesn't check again: GitHub allows 60 anonymous requests
     * an hour, shared by everything on the same internet connection.
     */
    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        Runnable check = new Runnable() {
            @Override
            public void run() {
                SharedPreferences sp = new Prefs(app).raw();
                long now = System.currentTimeMillis();
                if (!Clock.isSet()) {
                    // 1970 after a reboot: neither the saved times nor HTTPS work yet
                    worker().postDelayed(this, 15 * 60 * 1000L);
                    return;
                }
                long last = sp.getLong("update_checked", 0);
                // A check saved "in the future" was made while the clock was wrong
                if (last > now) last = 0;
                long due = Math.max(last + CHECK_EVERY_MS, sp.getLong("update_retry_at", 0));
                if (now < due) {
                    worker().postDelayed(this, Math.min(due - now, CHECK_EVERY_MS));
                    return;
                }
                if (new Prefs(app).updateAuto()) {
                    sp.edit().putLong("update_checked", now).apply();
                    try {
                        // Only device owners install unattended; otherwise Android would prompt
                        // every day, maybe in the night, so leave installing to "Check now"
                        Log.i("Update check: " + check(app, Device.isOwner(app)));
                    } catch (Exception e) {
                        Log.w("Update check failed: " + e.getMessage());
                    }
                }
                worker().postDelayed(this, CHECK_EVERY_MS);
            }
        };
        worker().postDelayed(check, 5 * 60 * 1000L);
    }

    static void checkNow(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        worker().post(() -> {
            String message;
            try {
                message = check(app, true);
            } catch (Exception e) {
                message = "Update check failed: " + e.getMessage();
            }
            String result = message;
            main.post(() -> callback.done(result));
        });
    }

    /**
     * The repository's releases. GitHub doesn't count a request against its hourly limit when
     * nothing has changed (304), so the last reply and its ETag are kept to ask "changed since?".
     */
    private static String releases(Context context, String repo) throws IOException {
        SharedPreferences sp = new Prefs(context).raw();
        File cache = new File(context.getCacheDir(), "releases.json");
        // A cached reply is only valid for the repository it came from
        String etag = repo.equals(sp.getString("update_etag_repo", "")) && cache.exists()
                ? sp.getString("update_etag", "") : "";
        java.net.HttpURLConnection conn = Net.open("https://api.github.com/repos/" + repo + "/releases?per_page=100");
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (!etag.isEmpty()) conn.setRequestProperty("If-None-Match", etag);
            Net.Response res = Net.read(conn, 2 * 1024 * 1024);
            if (res.status == 304) return readFile(cache);
            if (res.status == 200) {
                try (OutputStream out = new java.io.FileOutputStream(cache)) {
                    out.write(res.body);
                }
                String newTag = conn.getHeaderField("ETag");
                sp.edit().putString("update_etag", newTag == null ? "" : newTag)
                        .putString("update_etag_repo", repo).apply();
                return res.text();
            }
            String remaining = conn.getHeaderField("X-RateLimit-Remaining");
            if (res.status == 429 || (res.status == 403 && "0".equals(remaining))) {
                long reset = 0;
                try {
                    reset = Long.parseLong(conn.getHeaderField("X-RateLimit-Reset")) * 1000L;
                } catch (NumberFormatException ignored) {
                    // No reset time: try again in an hour
                }
                if (reset <= System.currentTimeMillis()) reset = System.currentTimeMillis() + 60 * 60 * 1000L;
                sp.edit().putLong("update_retry_at", reset + 60_000).apply();
                throw new IOException("GitHub's limit of 60 checks an hour for this internet connection is used up "
                        + "(it's shared by every device on it). Try again after "
                        + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(reset)));
            }
            if (res.status == 404) throw new IOException("No GitHub repository called " + repo);
            throw new IOException("GitHub returned HTTP " + res.status);
        } finally {
            conn.disconnect();
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    static int currentVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static String check(Context context, boolean install) throws IOException {
        String repo = new Prefs(context).updateRepo();
        if (!REPO.matcher(repo).matches()) throw new IOException("Update source should look like owner/repo");
        String releasesJson = releases(context, repo);

        int current = currentVersion(context), best = current;
        String apkUrl = null;
        try {
            JSONArray releases = new JSONArray(releasesJson);
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                Matcher m = TAG.matcher(r.optString("tag_name"));
                if (!m.matches() || m.group(1).length() > 9) continue;
                int version = Integer.parseInt(m.group(1));
                if (version <= best) continue;
                JSONArray assets = r.optJSONArray("assets");
                for (int j = 0; assets != null && j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    String url = a.optString("browser_download_url");
                    if (a.optString("name").endsWith(".apk") && url.startsWith("https://github.com/")) {
                        best = version;
                        apkUrl = url;
                        break;
                    }
                }
            }
        } catch (JSONException | NumberFormatException e) {
            throw new IOException("Unexpected reply from GitHub");
        }
        if (apkUrl == null) return "Up to date (version " + current + ")";
        if (!install) return "Version " + best + " is available: Settings → Check for updates now installs it";

        File apk = new File(context.getCacheDir(), "update.apk");
        Net.download(apkUrl, apk, 50L * 1024 * 1024, true);
        verify(context, apk, best);
        install(context, apk);
        return "Installing version " + best;
    }

    @SuppressWarnings("deprecation")
    private static void verify(Context context, File apk, int expectedVersion) throws IOException {
        PackageManager pm = context.getPackageManager();
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (update == null || !context.getPackageName().equals(update.packageName)) {
            throw new IOException("Downloaded file isn't this app");
        }
        if (update.versionCode != expectedVersion) throw new IOException("Downloaded version doesn't match its release");
        try {
            Signature[] installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            if (!Arrays.equals(installed, update.signatures)) {
                throw new IOException("Update isn't signed with this app's key");
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Can't read this app's signature");
        }
    }

    private static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            PendingIntent done = PendingIntent.getBroadcast(context, id,
                    new Intent(context, InstallReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(done.getIntentSender());
            committed = true;
        } finally {
            // Don't leave half-written sessions behind to fill storage on repeated failures
            if (!committed) installer.abandonSession(id);
        }
    }

    /** Install results. Without device ownership Android asks first, so show its prompt. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w("Update install failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }
}
