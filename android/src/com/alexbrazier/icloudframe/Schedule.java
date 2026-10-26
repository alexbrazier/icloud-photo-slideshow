package com.alexbrazier.icloudframe;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import java.util.Calendar;

/**
 * The night screen-off schedule. With device admin (or owner) rights the panel really turns off
 * (lockNow) and an alarm wakes it at the "on" time; without them the slideshow shows a black,
 * dimmed screen instead.
 */
final class Schedule {
    private static final String ACTION_OFF = "com.alexbrazier.icloudframe.SCREEN_OFF";
    private static final String ACTION_ON = "com.alexbrazier.icloudframe.SCREEN_ON";

    private Schedule() {}

    /** Whether the app may turn the screen off: it's an active device admin. */
    static boolean canTurnOff(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(Device.admin(context));
    }

    static boolean inOffWindow(Prefs prefs) {
        // 1970 after a reboot would read as 1am, so never treat an unset clock as night
        return prefs.nightEnabled() && inWindow(prefs.nightOff(), prefs.nightOn());
    }

    /** Whether now is between two times (minutes after midnight), which may span midnight. */
    static boolean inWindow(int start, int end) {
        if (!Clock.isSet() || start < 0 || end < 0 || start == end) return false;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return start < end ? now >= start && now < end : now >= start || now < end;
    }

    /** Sets (or clears) the off and on alarms from the current settings. */
    static void update(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent offIntent = pending(app, ACTION_OFF), onIntent = pending(app, ACTION_ON);
        alarms.cancel(offIntent);
        alarms.cancel(onIntent);
        Prefs prefs = new Prefs(app);
        int off = prefs.nightOff(), on = prefs.nightOn();
        // Without the real time the alarms would land on the wrong day; the time-set broadcast reschedules
        if (!prefs.nightEnabled() || off < 0 || on < 0 || off == on || !Clock.isSet()) return;
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(off), offIntent);
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(on), onIntent);
    }

    private static PendingIntent pending(Context context, String action) {
        Intent i = new Intent(context, Receiver.class).setAction(action);
        return PendingIntent.getBroadcast(context, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The next time today or tomorrow at this many minutes past midnight. */
    private static long next(int minutes) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    static void screenOff(Context context) {
        if (!canTurnOff(context)) return;
        try {
            ((DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE)).lockNow();
            Log.i("Screen off for the night");
        } catch (SecurityException e) {
            Log.w("Couldn't turn the screen off: " + e);
        }
    }

    @SuppressWarnings("deprecation")
    static void screenOn(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        // The deprecated full wake lock is still the only way for an app to switch the panel on
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "icloudframe:morning");
        lock.acquire(15_000);
        try {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            // Usually it's still on top; Android 10+ only lets it come forward itself as the
            // Home app or with "Display over other apps"
            Log.w("Couldn't bring the slideshow forward: " + e);
        }
        Log.i("Screen on for the morning");
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_OFF.equals(action) && inOffWindow(new Prefs(context))) screenOff(context);
            else if (ACTION_ON.equals(action)) screenOn(context);
            update(context);
        }
    }
}
