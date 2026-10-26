package com.alexbrazier.icloudframe;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Extras when this app is the device owner (set once over adb, see README): it stays the Home app
 * even if the vendor app comes back, there's no lock screen, and updates install without a prompt.
 */
final class Device {
    private Device() {}

    static ComponentName admin(Context context) {
        return new ComponentName(context, AdminReceiver.class);
    }

    /**
     * Whether the app may open itself from the background (after a reboot, an update or the
     * morning alarm). Android 10+ only allows that for the Home app or an app with "Display over
     * other apps"; Fire tablets never let another app be Home, so they need the latter.
     */
    static boolean canStartFromBackground(Context context) {
        return android.os.Build.VERSION.SDK_INT < 29 || isHome(context)
                || android.provider.Settings.canDrawOverlays(context);
    }

    static boolean isHome(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo info = context.getPackageManager()
                .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null
                && context.getPackageName().equals(info.activityInfo.packageName);
    }

    /** Asks Android to make this app a device admin (just for locking the screen). */
    static void requestAdmin(Context context) {
        context.startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets the frame turn its screen off at night and back on in the morning."));
    }

    /** Opens Android's "Display over other apps" screen for this app. */
    static void requestBackgroundStart(Context context) {
        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + context.getPackageName()));
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            // Some builds only have the list of apps, without going straight to this one
            context.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    static boolean isOwner(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    static void setUp(Context context) {
        if (!isOwner(context)) return;
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            dpm.addPersistentPreferredActivity(admin(context), home,
                    new ComponentName(context, MainActivity.class));
            // No lock screen, so the slideshow comes straight back after the night schedule
            dpm.setKeyguardDisabled(admin(context), true);
        } catch (SecurityException e) {
            Log.w("Couldn't set up device owner policies: " + e);
        }
    }

    /**
     * Gives up device owner (and admin), so the app can be uninstalled and the frame used normally
     * again without a factory reset. Setting it again needs adb and a frame with no accounts.
     */
    @SuppressWarnings("deprecation")
    static void releaseOwner(Context context) {
        if (!isOwner(context)) return;
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            dpm.setKeyguardDisabled(admin(context), false);
        } catch (SecurityException e) {
            Log.w("Couldn't re-enable the lock screen: " + e);
        }
        dpm.clearPackagePersistentPreferredActivities(admin(context), context.getPackageName());
        dpm.clearDeviceOwnerApp(context.getPackageName());
        Log.i("No longer device owner");
    }

    /** Undoes setUp, for the "Choose Home app" setting. */
    static void releaseHome(Context context) {
        if (!isOwner(context)) return;
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        dpm.clearPackagePersistentPreferredActivities(admin(context), context.getPackageName());
    }
}
