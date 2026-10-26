package com.alexbrazier.icloudframe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * Settings from adb, for setting a frame up before gifting it:
 *   adb shell am broadcast -n com.alexbrazier.icloudframe/.ConfigReceiver --es album "…"
 * Protected by the DUMP permission in the manifest, which the adb shell has and apps can't get.
 */
public final class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (RuntimeException e) {
            return;
        }
        if (extras == null || extras.isEmpty()) return;
        SharedPreferences.Editor edit = new Prefs(context).raw().edit();
        StringBuilder result = new StringBuilder();
        boolean reload = false;
        int saved = 0;
        for (String key : extras.keySet()) {
            String error = SettingsSchema.put(context, edit, key, extras.get(key));
            if (error != null) {
                result.append("Ignored ").append(key).append(": ").append(error).append('\n');
                continue;
            }
            saved++;
            if (key.equals("album") || key.equals("src_icloud") || key.equals("videos")) reload = true;
        }
        edit.commit();
        result.append("Saved ").append(saved).append(saved == 1 ? " setting" : " settings");
        Log.i(result.toString().replace('\n', ' '));
        if (isOrderedBroadcast()) setResultData(result.toString());
        FrameApp app = FrameApp.get();
        app.sync().wake(reload);
        Schedule.update(context);
        app.settingsChanged();
    }
}
