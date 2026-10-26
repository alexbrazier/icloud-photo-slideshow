package com.alexbrazier.icloudframe;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the app asks Android for, what each is for, and where it stands, for the
 * Permissions screen. Runtime permissions are recorded when requested, so "not asked yet" and
 * "denied" can be told apart.
 */
final class Permissions {
    enum Status { ALLOWED, DENIED, NOT_ASKED, NOT_NEEDED, NOT_POSSIBLE, AUTOMATIC }

    static final class Item {
        final String title, purpose, detail;
        final Status status;
        /** What tapping the row's button does, or null when there's nothing to do. */
        final String actionLabel;
        final Runnable action;

        Item(String title, String purpose, Status status, String detail, String actionLabel, Runnable action) {
            this.title = title;
            this.purpose = purpose;
            this.status = status;
            this.detail = detail;
            this.actionLabel = actionLabel;
            this.action = action;
        }
    }

    private Permissions() {}

    /** Asks for a runtime permission, remembering that it was asked. */
    static void request(Activity activity, String permission, int requestCode) {
        new Prefs(activity).raw().edit().putBoolean(askedKey(permission), true).apply();
        activity.requestPermissions(new String[] {permission}, requestCode);
    }

    private static String askedKey(String permission) {
        return "asked_" + permission.substring(permission.lastIndexOf('.') + 1);
    }

    static boolean isFire() {
        return "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
    }

    static List<Item> list(Activity a) {
        List<Item> items = new ArrayList<>();
        boolean fire = isFire();

        boolean bgNeeded = Build.VERSION.SDK_INT >= 29 && !Device.isHome(a);
        items.add(new Item("Display over other apps",
                "Lets the slideshow open itself after a restart or an update, and come forward for the morning. "
                        + "Only needed when it isn't the Home app.",
                !bgNeeded ? Status.NOT_NEEDED : Settings.canDrawOverlays(a) ? Status.ALLOWED : Status.DENIED,
                !bgNeeded ? "Not needed: " + (Build.VERSION.SDK_INT < 29 ? "this Android version allows it" : "it's the Home app")
                        : Settings.canDrawOverlays(a) ? null : "Without it, it stays on the home screen after a restart",
                bgNeeded && !Settings.canDrawOverlays(a) ? "Allow" : null, () -> Device.requestBackgroundStart(a)));

        boolean admin = Schedule.canTurnOff(a);
        items.add(new Item("Device admin: lock the screen",
                "Turns the screen fully off at night. The only power it asks for is locking the screen.",
                admin ? Status.ALLOWED : Status.DENIED,
                admin ? null : "Without it, the night schedule only dims the screen to black",
                admin ? null : "Allow", () -> Device.requestAdmin(a)));

        if (Build.VERSION.SDK_INT >= 26) {
            boolean install = a.getPackageManager().canRequestPackageInstalls();
            items.add(new Item("Install unknown apps",
                    "Lets the app install its own updates from GitHub. Android still asks to confirm each one "
                            + "unless the app is the device owner.",
                    install ? Status.ALLOWED : Status.NOT_ASKED,
                    install ? null : "Android asks for this the first time an update installs",
                    install ? null : "Allow",
                    () -> a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName())))));
        }

        items.add(runtime(a, Manifest.permission.ACCESS_COARSE_LOCATION, "Location",
                "Android only lists nearby Wi-Fi networks to apps with this. Used by the Wi-Fi screen, nothing else; "
                        + "the app never reads where the frame is.", "Without it, add networks by name"));
        items.add(runtime(a, Manifest.permission.READ_EXTERNAL_STORAGE, "Storage",
                "Reads photos and videos from SD cards, USB sticks and the frame's own folders. Only asked for when "
                        + "that source is switched on.", "Without it, only iCloud albums show"));

        boolean home = Device.isHome(a);
        items.add(new Item("Home app",
                "As the Home app, Home, Back and every restart land on the slideshow.",
                home ? Status.ALLOWED : fire ? Status.NOT_POSSIBLE : Status.DENIED,
                home ? null : fire ? "Fire tablets don't let other apps be Home; Display over other apps covers restarts"
                        : "Choose iCloud Frame as the Home app",
                home || fire ? null : "Choose", () -> a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS))));

        boolean owner = Device.isOwner(a);
        items.add(new Item("Device owner",
                "Optional. Installs updates without asking, keeps the app as Home and turns the lock screen off. "
                        + "Can only be set over adb, on a frame with no accounts (see the setup guide).",
                owner ? Status.ALLOWED : fire ? Status.NOT_POSSIBLE : Status.NOT_ASKED,
                owner ? null : fire ? "Not possible on Fire tablets: Amazon's Parental Controls hold the role" : "Not set",
                null, null));

        KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isDeviceSecure()) {
            items.add(new Item("Lock screen PIN",
                    "A PIN or password lock screen comes back after the night schedule and needs unlocking each "
                            + "morning. This isn't a permission, but it stops the frame working on its own.",
                    Status.DENIED, "Remove the PIN in Android's security settings", null, null));
        }

        Adb.Way adb = Adb.way(a);
        items.add(new Item("Wi-Fi adb (optional)",
                "Keeps adb over the network on or off after restarts, for the Wi-Fi adb setting. While it's on, "
                        + "any computer on the network can ask to connect; the frame asks you before allowing one.",
                adb == Adb.Way.ROOT ? Status.AUTOMATIC : adb == Adb.Way.WIRELESS_DEBUGGING ? Status.ALLOWED
                        : adb == Adb.Way.NEEDS_GRANT ? Status.NOT_ASKED : Status.NOT_POSSIBLE,
                adb == Adb.Way.ROOT ? "Uses root, which this frame has"
                        : adb == Adb.Way.WIRELESS_DEBUGGING ? null
                        : adb == Adb.Way.NEEDS_GRANT ? "Apps can't ask for this. Grant it once over USB: " + Adb.grantCommand(a)
                        : "Not possible: the frame isn't rooted and is older than Android 11",
                null, null));

        items.add(new Item("Internet, Wi-Fi, start at boot, wake the screen",
                "Downloading photos and weather, the Wi-Fi screen, setting the night alarms again after a restart, "
                        + "and switching the screen back on in the morning.",
                Status.AUTOMATIC, null, null, null));
        return items;
    }

    private static Item runtime(Activity a, String permission, String title, String purpose, String without) {
        boolean granted = a.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        boolean asked = new Prefs(a).raw().getBoolean(askedKey(permission), false);
        if (granted) return new Item(title, purpose, Status.ALLOWED, null, null, null);
        if (!asked) {
            return new Item(title, purpose, Status.NOT_ASKED, without, "Allow", () -> request(a, permission, 2));
        }
        // After a "don't ask again" (or two refusals on Android 11+), only Android's settings can change it
        boolean canAskAgain = a.shouldShowRequestPermissionRationale(permission);
        return new Item(title, purpose, Status.DENIED, without,
                canAskAgain ? "Ask again" : "Open settings",
                canAskAgain ? () -> request(a, permission, 2) : () -> a.startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))));
    }
}
