package com.alexbrazier.icloudframe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The clock being set (often a minute after boot, as frames have no clock battery) or the time
 * zone changing: re-plan the screen schedule and retry the album, which needed the real time.
 */
public final class TimeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Schedule.update(context);
        FrameApp.get().sync().wake(true);
    }
}
