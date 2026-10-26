package com.alexbrazier.icloudframe;

/** Logcat under one tag: adb logcat -s iCloudFrame */
final class Log {
    private static final String TAG = "iCloudFrame";

    private Log() {}

    static void i(String msg) { android.util.Log.i(TAG, msg); }
    static void w(String msg) { android.util.Log.w(TAG, msg); }
}
