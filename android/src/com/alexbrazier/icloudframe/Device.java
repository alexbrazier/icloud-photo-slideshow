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
