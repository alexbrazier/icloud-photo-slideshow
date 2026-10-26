package com.alexbrazier.icloudframe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the slideshow back after a reboot or an update, for frames where it isn't Home. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Alarms don't survive a reboot or an update
        Schedule.update(context);
        // Android 10+ quietly ignores this unless the app is Home or has "Display over other apps"
        if (!Device.canStartFromBackground(context)) {
            Log.w("Can't open after a restart: allow Display over other apps (Settings → Device)");
        }
        try {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w("Couldn't start after boot: " + e);
        }
    }
}
