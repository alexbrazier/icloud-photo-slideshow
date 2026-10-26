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
        try {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            // Android 10+ blocks starts from the background; being the Home app covers that
            Log.w("Couldn't start after boot: " + e);
        }
    }
}
