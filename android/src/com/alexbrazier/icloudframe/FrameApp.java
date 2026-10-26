package com.alexbrazier.icloudframe;

import android.app.Application;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/** Starts the background sync and update checks with the process, whatever is on screen. */
public final class FrameApp extends Application {
    private static FrameApp instance;
    private static int screenLong = 1280, screenShort = 800;

    private PhotoStore store;
    private Sync sync;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        screenLong = Math.max(dm.widthPixels, dm.heightPixels);
        screenShort = Math.min(dm.widthPixels, dm.heightPixels);

        Net.init(this);
        store = new PhotoStore(getFilesDir());
        sync = new Sync(this, store);
        sync.start();
        Updater.schedule(this);
        Device.setUp(this);
        Schedule.update(this);
        // adb forgets its network port on every restart
        Adb.apply(this);
        SetupServer.applySetting(this);
    }

    /** Told when settings change from outside the settings screen (adb, the phone page). */
    interface SettingsListener {
        void onSettingsChanged();
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile SettingsListener settingsListener;

    void setSettingsListener(SettingsListener listener) { settingsListener = listener; }

    void settingsChanged() {
        main.post(() -> {
            SettingsListener l = settingsListener;
            if (l != null) l.onSettingsChanged();
        });
    }

    static FrameApp get() { return instance; }
    static int screenLong() { return screenLong; }
    static int screenShort() { return screenShort; }

    static boolean screenLandscape(android.content.Context context) {
        return context.getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    PhotoStore store() { return store; }
    Sync sync() { return sync; }
}
