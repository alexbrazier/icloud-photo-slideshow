package com.alexbrazier.icloudframe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * The "Wi-Fi adb" setting: keeps adb over the network on (or off) across restarts, so a frame can
 * be looked after without a cable. Apps can't normally do this, so it needs one of:
 * - root (many Frameo-style frames have it): sets the adb port and restarts adb, any Android version
 * - Android 11+ with WRITE_SECURE_SETTINGS, granted once over USB: Android's own Wireless debugging
 * Either way, Android still asks on the frame before a new computer can connect.
 */
final class Adb {
    static final int PORT = 5555;

    enum Way { ROOT, WIRELESS_DEBUGGING, NEEDS_GRANT, NOT_POSSIBLE }

    private static volatile Boolean rooted;
    private static volatile String lastResult = "";

    private Adb() {}

    /** "leave" (don't touch it), "on" or "off". */
    static String mode(Context context) {
        return new Prefs(context).raw().getString("adb_wifi", "leave");
    }

    /** Safe on the main thread: until root has been tried in the background, guesses from the su file. */
    static Way way(Context context) {
        Boolean r = rooted;
        if (r != null ? r : hasSu()) return Way.ROOT;
        if (Build.VERSION.SDK_INT >= 30) {
            return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    ? Way.WIRELESS_DEBUGGING : Way.NEEDS_GRANT;
        }
        return Way.NOT_POSSIBLE;
    }

    /** The command that grants the Android 11+ permission, run on a computer with the frame on USB. */
    static String grantCommand(Context context) {
        return "adb shell pm grant " + context.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
    }

    /** What happened the last time the setting was applied, for the settings screen. */
    static String lastResult() { return lastResult; }

    /** Applies the setting in the background: at startup (so after every restart) and when it changes. */
    static void apply(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> applyNow(app), "adb-wifi").start();
    }

    private static synchronized void applyNow(Context context) {
        String mode = mode(context);
        if ("leave".equals(mode)) {
            lastResult = "";
            return;
        }
        boolean on = "on".equals(mode);
        try {
            isRooted();
            switch (way(context)) {
                case ROOT:
                    lastResult = applyWithRoot(on);
                    break;
                case WIRELESS_DEBUGGING:
                    Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", on ? 1 : 0);
                    lastResult = on ? "On: Android's Wireless debugging (pair new computers from its settings)" : "Off";
                    break;
                case NEEDS_GRANT:
                    lastResult = "Needs a one-off permission over USB: " + grantCommand(context);
                    break;
                default:
                    lastResult = "Not possible on this frame: it isn't rooted and is older than Android 11";
            }
        } catch (Exception e) {
            lastResult = "Couldn't change it: " + e.getMessage();
        }
        Log.i("Wi-Fi adb " + mode + ": " + lastResult);
    }

    /**
     * adbd listens on service.adb.tcp.port, or persist.adb.tcp.port when that isn't set, and only
     * reads them when it starts, so it's restarted when they change. Restarting drops any adb
     * connection, so it's skipped when it's already as wanted.
     */
    private static String applyWithRoot(boolean on) throws Exception {
        int now = Math.max(port("service.adb.tcp.port"), 0);
        if (now == 0) now = Math.max(port("persist.adb.tcp.port"), 0);
        if (on) {
            if (now == PORT) return "On: port " + PORT;
            su("settings put global adb_enabled 1; setprop service.adb.tcp.port " + PORT + "; stop adbd; start adbd");
            return "On: port " + PORT;
        }
        if (now == 0) return "Off";
        // 0 isn't a port, so adbd falls back to USB only; the persistent one is cleared too or it wins
        su("setprop service.adb.tcp.port 0; if [ -n \"$(getprop persist.adb.tcp.port)\" ]; then "
                + "setprop persist.adb.tcp.port 0; fi; stop adbd; start adbd");
        return "Off";
    }

    private static int port(String prop) {
        try {
            return Integer.parseInt(run(new String[] {"getprop", prop}).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean hasSu() {
        for (String dir : new String[] {"/system/bin/", "/system/xbin/", "/sbin/", "/su/bin/"}) {
            if (new java.io.File(dir + "su").exists()) return true;
        }
        return false;
    }

    /** Tries su, so not on the main thread; rooted phones may ask whether to allow it. */
    private static boolean isRooted() {
        Boolean r = rooted;
        if (r == null) {
            try {
                r = su("id").contains("uid=0");
            } catch (Exception e) {
                r = false;
            }
            rooted = r;
        }
        return r;
    }

    /** Runs a shell command as root. su builds differ: some take "su 0 cmd", others "su -c cmd". */
    private static String su(String command) throws Exception {
        try {
            return run(new String[] {"su", "0", "sh", "-c", command});
        } catch (Exception e) {
            return run(new String[] {"su", "-c", command});
        }
    }

    private static String run(String[] command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!waitFor(p, 15) || p.exitValue() != 0) throw new Exception(out.toString().trim());
        return out.toString();
    }

    // Process.waitFor(timeout) needs API 26
    private static boolean waitFor(Process p, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException running) {
                if (System.nanoTime() > end) {
                    p.destroy();
                    return false;
                }
                Thread.sleep(100);
            }
        }
    }
}
