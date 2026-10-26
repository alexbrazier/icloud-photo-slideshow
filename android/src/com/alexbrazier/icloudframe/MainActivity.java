package com.alexbrazier.icloudframe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The slideshow. Tap for controls, swipe to change photo, long-press for settings. It is also a
 * Home app, so a frame can boot straight into it.
 */
public final class MainActivity extends Activity implements Sync.Listener, FrameApp.SettingsListener {
    private static final long CONTROLS_HIDE_MS = 8000;
    private static final long NIGHT_WAKE_MS = 5 * 60 * 1000L;
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    private static final long REBUILD_EVERY_MS = 10 * 60 * 1000L;
    private static final long MAX_VIDEO_MS = 5 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Slides slides = new Slides();
    // Two slots that cross-fade, each a background (blur or colour) with the photo over it
    private final FrameLayout[] slots = new FrameLayout[2];
    private final ImageView[] images = new ImageView[2];
    private final ImageView[] backs = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private TextureView videoView;
    private MediaPlayer player;
    private Surface videoSurface;
    private int front;
    private int generation;
    private int failedInARow;

    private Prefs prefs;
    private FrameLayout root;
    private final FrameLayout[] corners = new FrameLayout[4];
    private LinearLayout clockBox;
    private FrameLayout controls;
    private TextView clockTime, clockDate, weatherText, statusText, controlsTitle, controlsStatus, caption;
    private android.widget.ImageButton playButton;
    private Button setupButton;
    private View nightCover;
    private LinearLayout splash;
    private ProgressBar spinner;

    private List<Slides.Item> localItems = Collections.emptyList();
    private boolean paused, dirty, nightActive, showing, resumed, askedStorage;
    private long lastRebuild, wakeUntil, weatherFetched;
    private String weatherKey = "", lookKey = "";
    private float brightness = Float.NaN;
    private Weather weather;
    private GestureDetector gestures;

    // Static, so it's once per app start rather than once per screen (which rotating recreates)
    private static boolean askedStartThisRun, openedSetupThisRun;

    private final Runnable advanceTask = () -> advance(1);
    private final Runnable hideControls = this::hideControls;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            updateNight();
            applyBrightness();
            maybeFetchWeather();
            handler.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            scanLocal();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Show over the lock screen, so the slideshow is what appears when the screen comes on
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        buildViews();
        FrameApp.get().sync().addListener(this);
        FrameApp.get().setSettingsListener(this);

        IntentFilter media = new IntentFilter();
        media.addAction(Intent.ACTION_MEDIA_MOUNTED);
        media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        media.addAction(Intent.ACTION_MEDIA_REMOVED);
        media.addDataScheme("file");
        registerReceiver(mediaReceiver, media);
    }

    @Override
    public void onSettingsChanged() {
        if (!resumed) return;
        applySettings();
        // The local source or video settings may have changed
        scanLocal();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemUi();
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && km.isKeyguardLocked() && !km.isKeyguardSecure()) {
            // Clears a swipe-only lock screen so Settings opened from here isn't behind it.
            // A PIN lock screen is left alone rather than asking for the PIN every morning.
            km.requestDismissKeyguard(this, null);
        }
        // Coming back on during the night (power button) stays on for a few minutes
        if (Schedule.inOffWindow(prefs)) wakeUntil = System.currentTimeMillis() + NIGHT_WAKE_MS;
        applySettings();
        scanLocal();
        FrameApp.get().sync().wake(false);
        handler.post(tick);
    }

    /**
     * On frames where the app can't open itself after a restart (it isn't Home and lacks
     * "Display over other apps", as on every Fire tablet), offers to turn that on: once each time
     * the app starts, and only once it's actually on screen (not behind a lock screen), until
     * it's allowed or the answer is "Don't ask again".
     */
    private void askToStartByItself() {
        SharedPreferences sp = prefs.raw();
        if (askedStartThisRun || Device.canStartFromBackground(this)
                || sp.getBoolean("never_ask_start_on_boot", false)) return;
        askedStartThisRun = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Start by itself?")
                .setMessage("To open the slideshow after a restart or power cut, and bring it back for the "
                        + "morning, it needs Display over other apps. On the next screen, tap iCloud Frame (and "
                        + "turn it on, if there's a switch), then press Back. You can do this later in "
                        + "Settings → Screen schedule → Start by itself.")
                .setPositiveButton("Continue", (d, w) -> Device.requestBackgroundStart(this))
                .setNegativeButton("Not now", null)
                .setNeutralButton("Don't ask again",
                        (d, w) -> sp.edit().putBoolean("never_ask_start_on_boot", true).apply())
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopVideo();
        resumed = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(advanceTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Nothing queued may run against the shut-down executors below
        handler.removeCallbacksAndMessages(null);
        FrameApp.get().sync().removeListener(this);
        FrameApp.get().setSettingsListener(null);
        unregisterReceiver(mediaReceiver);
        loader.shutdownNow();
        background.shutdownNow();
    }

    /** Runs on an executor unless this screen is being destroyed (which shuts them down). */
    private void submit(ExecutorService executor, Runnable task) {
        if (isDestroyed()) return;
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Shutting down
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
            // Setting up comes first; the "start by itself" question waits until it's done
            if (!openSetupIfNew()) askToStartByItself();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // Rotating can change whether a photo should fill the screen or be shown whole
        Bitmap b = bitmaps[front];
        if (b != null) images[front].setScaleType(scaleFor(b.getWidth(), b.getHeight()));
        if ("match".equals(prefs.orientation())) rebuild();
    }

    @Override
    public void onBackPressed() {
        // This is the Home app, so Back has nowhere to go
        controls.setVisibility(View.GONE);
    }

    // ---- Views ----

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout stage = new FrameLayout(this);
        for (int i = 0; i < 2; i++) {
            slots[i] = new FrameLayout(this);
            slots[i].setAlpha(0f);
            backs[i] = new ImageView(this);
            backs[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            // Darkened so the photo stands out from its blurred copy
            backs[i].setColorFilter(0x59000000, PorterDuff.Mode.SRC_ATOP);
            slots[i].addView(backs[i], new FrameLayout.LayoutParams(-1, -1));
            images[i] = new ImageView(this);
            slots[i].addView(images[i], new FrameLayout.LayoutParams(-1, -1));
            stage.addView(slots[i], new FrameLayout.LayoutParams(-1, -1));
        }
        // Videos play over their poster, in a TextureView so they can fade like photos. Not
        // opaque, so the poster's background shows around a video that doesn't fill the screen.
        videoView = new TextureView(this);
        videoView.setOpaque(false);
        videoView.setAlpha(0f);
        stage.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        int[] gravities = {Gravity.BOTTOM | Gravity.START, Gravity.BOTTOM | Gravity.END,
                Gravity.TOP | Gravity.START, Gravity.TOP | Gravity.END};
        for (int i = 0; i < 4; i++) {
            corners[i] = new FrameLayout(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravities[i]);
            lp.setMargins(dp(28), dp(20), dp(28), dp(20));
            root.addView(corners[i], lp);
        }

        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockTime = overlayText();
        clockDate = overlayText();
        clockBox.addView(clockTime);
        clockBox.addView(clockDate);
        weatherText = overlayText();
        caption = overlayText();
        caption.setAlpha(0.9f);

        // Splash: shown until the first photo is on screen
        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF101A33, 0xFF05070D}));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        splash.addView(logo, new LinearLayout.LayoutParams(dp(120), dp(120)));
        TextView title = overlayText();
        title.setText(R.string.app_name);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, 0);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        splash.addView(title);
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyle);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF8FB4FF));
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        spinnerLp.setMargins(0, dp(32), 0, dp(16));
        splash.addView(spinner, spinnerLp);
        statusText = overlayText();
        statusText.setGravity(Gravity.CENTER);
        statusText.setTextSize(17);
        statusText.setTextColor(0xFF9AA6BF);
        statusText.setLineSpacing(dp(4), 1f);
        statusText.setPadding(dp(32), 0, dp(32), dp(24));
        splash.addView(statusText);
        setupButton = pillButton("Set up from your phone", 0xFF3B82F6);
        setupButton.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        splash.addView(setupButton);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));

        buildControls();

        nightCover = new View(this);
        nightCover.setBackgroundColor(Color.BLACK);
        nightCover.setVisibility(View.GONE);
        root.addView(nightCover, new FrameLayout.LayoutParams(-1, -1));

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (nightActive) {
                    wakeUntil = System.currentTimeMillis() + NIGHT_WAKE_MS;
                    updateNight();
                } else {
                    toggleControls();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (nightActive || Math.abs(vx) < Math.abs(vy) || Math.abs(vx) < 800) return false;
                advance(vx < 0 ? 1 : -1);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!nightActive) openSettings();
            }
        });
        root.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));
        setContentView(root);
    }

    private TextView overlayText() {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(10, 0, 2, 0xDD000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * The tap overlay: what's showing and the frame's status along the top, with Phone setup and
     * Settings, and Back, Play/Pause and Next along the bottom. Dark gradients behind each keep
     * them readable on any photo; the middle is left clear, so a tap there hides it again.
     */
    private void buildControls() {
        controls = new FrameLayout(this);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(28), dp(20), dp(16), dp(64));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xD9000000, 0x80000000, 0x00000000}));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        controlsTitle = overlayText();
        controlsTitle.setTextSize(20);
        controlsTitle.setSingleLine();
        controlsTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controlsStatus = overlayText();
        controlsStatus.setTextSize(15);
        controlsStatus.setTextColor(0xF2FFFFFF);
        controlsStatus.setPadding(0, dp(6), 0, 0);
        text.addView(controlsTitle);
        text.addView(controlsStatus);
        top.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(iconButton(R.drawable.ic_phone, "Phone setup", 52, false, v -> {
            hideControls();
            startActivity(new Intent(this, SetupActivity.class));
        }));
        top.addView(iconButton(R.drawable.ic_settings, "Settings", 52, false, v -> openSettings()));
        controls.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, dp(72), 0, dp(28));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xB3000000, 0x59000000, 0x00000000}));
        bottom.addView(iconButton(R.drawable.ic_previous, "Previous photo", 64, false, v -> advance(-1)));
        playButton = iconButton(R.drawable.ic_pause, "Pause", 84, true, v -> togglePause());
        LinearLayout.LayoutParams playLp = (LinearLayout.LayoutParams) playButton.getLayoutParams();
        playLp.setMargins(dp(28), 0, dp(28), 0);
        bottom.addView(playButton);
        bottom.addView(iconButton(R.drawable.ic_next, "Next photo", 64, false, v -> advance(1)));
        controls.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        controls.setVisibility(View.GONE);
        // Just above the photos, so the clock and weather in the corners stay on top of its shading
        root.addView(controls, 1, new FrameLayout.LayoutParams(-1, -1));
    }

    /** A round icon button; the main one is solid white, the others frosted. */
    private android.widget.ImageButton iconButton(int icon, String label, int sizeDp, boolean primary,
                                                  View.OnClickListener click) {
        android.widget.ImageButton b = new android.widget.ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(sizeDp * (primary ? 0.26f : 0.24f));
        b.setPadding(pad, pad, pad, pad);
        if (primary) b.setColorFilter(0xFF111111);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        // Dark glass with an outline for the others, so they show on light and dark photos alike
        bg.setColor(primary ? 0xF2FFFFFF : 0x66000000);
        if (!primary) bg.setStroke(dp(1.5f), 0x99FFFFFF);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x55FFFFFF), bg, null));
        b.setOnClickListener(v -> {
            click.onClick(v);
            if (controls.getVisibility() == View.VISIBLE) showControls();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(8), 0, dp(8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private Button pillButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setMinWidth(dp(110));
        b.setMinHeight(dp(56));
        b.setPadding(dp(20), 0, dp(20), 0);
        return b;
    }

    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) hideControls();
        else showControls();
    }

    private void showControls() {
        Slides.Item current = slides.current();
        String about = current == null ? "" : photoLabel(current, true, true);
        controlsTitle.setText(about.isEmpty() ? getString(R.string.app_name) : about);
        controlsStatus.setText(statusLine());
        if (controls.getVisibility() != View.VISIBLE) {
            controls.setAlpha(0f);
            controls.setVisibility(View.VISIBLE);
        }
        controls.animate().cancel();
        controls.animate().alpha(1f).setDuration(200).start();
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, CONTROLS_HIDE_MS);
    }

    private void hideControls() {
        handler.removeCallbacks(hideControls);
        if (controls.getVisibility() != View.VISIBLE) return;
        controls.animate().cancel();
        controls.animate().alpha(0f).setDuration(250).withEndAction(() -> controls.setVisibility(View.GONE)).start();
    }

    private void togglePause() {
        paused = !paused;
        playButton.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        playButton.setContentDescription(paused ? "Play" : "Pause");
        if (player != null) {
            try {
                if (paused) player.pause();
                else player.start();
            } catch (IllegalStateException ignored) {
                // Not prepared yet
            }
        }
        if (paused) handler.removeCallbacks(advanceTask);
        else scheduleNext();
    }

    private void openSettings() {
        controls.setVisibility(View.GONE);
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---- Settings ----

    private void applySettings() {
        for (View v : new View[] {clockBox, weatherText, caption}) {
            ViewGroup parent = (ViewGroup) v.getParent();
            if (parent != null) parent.removeView(v);
        }
        for (FrameLayout corner : corners) corner.removeAllViews();
        place(clockBox, prefs.clockShow() ? prefs.clockPos() : null);
        float[] sizes = "small".equals(prefs.clockSize()) ? new float[] {30, 15}
                : "large".equals(prefs.clockSize()) ? new float[] {72, 26} : new float[] {48, 19};
        clockTime.setTextSize(sizes[0]);
        clockDate.setTextSize(sizes[1]);
        clockDate.setVisibility(prefs.clockDate() ? View.VISIBLE : View.GONE);
        boolean left = prefs.clockPos().endsWith("left");
        clockTime.setGravity(left ? Gravity.START : Gravity.END);
        clockDate.setGravity(left ? Gravity.START : Gravity.END);
        clockBox.setGravity(left ? Gravity.START : Gravity.END);

        boolean weatherOn = prefs.weatherShow() && !prefs.weatherLat().isEmpty() && !prefs.weatherLon().isEmpty();
        place(weatherText, weatherOn ? prefs.weatherPos() : null);
        weatherText.setTextSize(sizes[1]);
        weatherText.setGravity(prefs.weatherPos().endsWith("left") ? Gravity.START : Gravity.END);
        String key = prefs.weatherLat() + "," + prefs.weatherLon();
        if (!key.equals(weatherKey)) {
            weatherKey = key;
            weather = null;
            weatherFetched = 0;
        }
        updateWeatherText();
        updateClock();

        place(caption, "none".equals(prefs.caption()) ? null : prefs.captionPos());
        caption.setTextSize(sizes[1] * 0.85f);
        caption.setGravity(prefs.captionPos().endsWith("left") ? Gravity.START : Gravity.END);
        brightness = Float.NaN;
        applyBrightness();

        rebuild();
        // Fit and background changes show straight away, on the current photo
        String look = prefs.fitSame() + prefs.fitOther() + prefs.background() + prefs.backgroundColor() + prefs.caption();
        if (!look.equals(lookKey)) {
            boolean first = lookKey.isEmpty();
            lookKey = look;
            Slides.Item current = slides.current();
            if (!first && showing && current != null) show(current);
        }
        if (!paused) scheduleNext();
    }

    /** Puts an overlay in a corner, stacking it with whatever else is there. */
    private void place(View view, String pos) {
        if (pos == null) return;
        int i = Arrays.asList("bottom_left", "bottom_right", "top_left", "top_right").indexOf(pos);
        FrameLayout corner = corners[Math.max(i, 0)];
        if (corner.getChildCount() == 0) {
            corner.addView(view);
            return;
        }
        // Two overlays in one corner go in a column
        View other = corner.getChildAt(0);
        LinearLayout column;
        if (other instanceof LinearLayout && "column".equals(other.getTag())) {
            column = (LinearLayout) other;
        } else {
            corner.removeView(other);
            column = new LinearLayout(this);
            column.setTag("column");
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(other);
            corner.addView(column);
        }
        View spacer = new View(this);
        column.addView(spacer, new LinearLayout.LayoutParams(1, dp(10)));
        column.addView(view);
        column.setGravity(pos.endsWith("left") ? Gravity.START : Gravity.END);
    }

    // ---- Photos ----

    @Override
    public void onPhotosChanged() {
        // Rebuilding on every download would keep reshuffling, so batch changes up
        dirty = true;
        if (slides.isEmpty() || slides.size() < 30) rebuild();
        updateStatus();
    }

    private void rebuild() {
        List<Slides.Item> items = new ArrayList<>();
        if (prefs.iCloudEnabled()) {
            items.addAll(FrameApp.get().store().items(Albums.enabledIds(this), prefs.includeVideos()));
        }
        if (prefs.localEnabled()) items.addAll(localItems);
        slides.rebuild(items, prefs.order(), prefs.orientation(), screenLandscape());
        dirty = false;
        lastRebuild = System.currentTimeMillis();
        updateStatus();
        // While paused (e.g. in Settings) the first photo waits until the slideshow is back on screen
        if (!showing && !slides.isEmpty() && resumed) advance(1);
        if (slides.isEmpty() && showing) clearImages();
    }

    // ---- Video ----

    /** Plays the current item's video over its poster, then moves on when it ends. */
    private void startVideo(Slides.Item item, int gen) {
        if (!resumed || nightActive || gen != generation || !videoView.isAvailable()) return;
        stopVideo();
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(item.video.getPath());
            videoSurface = new Surface(videoView.getSurfaceTexture());
            mp.setSurface(videoSurface);
            if (!prefs.videoSound()) mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                fitVideo(item, p.getVideoWidth(), p.getVideoHeight());
                if (!paused) p.start();
                // Move on when it ends, with a cap in case it stalls or is very long
                handler.removeCallbacks(advanceTask);
                long length = p.getDuration() > 0 ? p.getDuration() : prefs.intervalMs();
                handler.postDelayed(advanceTask, Math.min(length, MAX_VIDEO_MS) + 3000);
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player == p) {
                    videoView.animate().alpha(1f).setDuration(300).start();
                }
                return false;
            });
            mp.setOnCompletionListener(p -> {
                if (player == p) advance(1);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                // Unplayable here: keep showing the poster for the usual time
                Log.w("Couldn't play " + item.video.getName() + " (" + what + "/" + extra + ")");
                if (player == p) stopVideo();
                scheduleNext();
                return true;
            });
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            Log.w("Couldn't play " + item.video.getName() + ": " + e);
            stopVideo();
        }
    }

    private void stopVideo() {
        if (player == null) return;
        MediaPlayer mp = player;
        player = null;
        videoView.animate().cancel();
        videoView.setAlpha(0f);
        try {
            mp.reset();
        } catch (IllegalStateException ignored) {
            // Releasing anyway
        }
        mp.release();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
    }

    /** Scales the video in the TextureView like the photo underneath it (fill or fit). */
    private void fitVideo(Slides.Item item, int videoWidth, int videoHeight) {
        int w = videoView.getWidth(), h = videoView.getHeight();
        if (w == 0 || h == 0 || videoWidth == 0 || videoHeight == 0) return;
        float scale = scaleFor(videoWidth, videoHeight) == ImageView.ScaleType.CENTER_CROP
                ? Math.max(w / (float) videoWidth, h / (float) videoHeight)
                : Math.min(w / (float) videoWidth, h / (float) videoHeight);
        android.graphics.Matrix m = new android.graphics.Matrix();
        // A TextureView stretches the video to fill it, so undo that and apply the scale
        m.setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2f, h / 2f);
        videoView.setTransform(m);
    }

    private boolean screenLandscape() {
        return getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT;
    }

    private void advance(int direction) {
        handler.removeCallbacks(advanceTask);
        if (direction > 0 && dirty && System.currentTimeMillis() - lastRebuild > REBUILD_EVERY_MS) rebuild();
        Slides.Item item = direction > 0 ? slides.next(prefs.order()) : slides.previous();
        if (item == null) return;
        show(item);
        scheduleNext();
    }

    private void scheduleNext() {
        handler.removeCallbacks(advanceTask);
        if (resumed && !paused && !nightActive && !slides.isEmpty()) handler.postDelayed(advanceTask, prefs.intervalMs());
    }

    private void show(Slides.Item item) {
        stopVideo();
        int gen = ++generation;
        boolean blur = "blur".equals(prefs.background());
        submit(loader, () -> {
            Bitmap bitmap = null, blurred = null;
            try {
                bitmap = item.file != null ? Images.decode(item.file, FrameApp.screenLong())
                        : Images.videoFrame(item.video, FrameApp.screenLong());
                if (bitmap != null && blur) blurred = Images.blurred(bitmap);
            } catch (OutOfMemoryError e) {
                Log.w("Out of memory decoding " + item.file);
            }
            Bitmap result = bitmap, back = blurred;
            handler.post(() -> {
                if (gen != generation || isDestroyed()) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result == null) {
                    // Unreadable file: skip it, without spinning if they're all unreadable
                    if (++failedInARow < slides.size()) handler.postDelayed(() -> advance(1), 200);
                    return;
                }
                failedInARow = 0;
                display(result, back, item);
            });
        });
    }

    private void display(Bitmap bitmap, Bitmap blurred, Slides.Item item) {
        int back = 1 - front, oldIndex = front;
        FrameLayout incoming = slots[back], outgoing = slots[front];
        ImageView outgoingImage = images[front], outgoingBack = backs[front];
        Bitmap old = bitmaps[front];
        incoming.animate().cancel();
        outgoing.animate().cancel();
        images[back].setScaleType(scaleFor(bitmap.getWidth(), bitmap.getHeight()));
        images[back].setImageBitmap(bitmap);
        backs[back].setImageBitmap(blurred);
        incoming.setBackgroundColor(backgroundColor());
        bitmaps[back] = bitmap;
        incoming.bringToFront();
        updateCaption(item);
        long fade = showing ? prefs.fadeMs() : 400;
        Runnable finish = () -> {
            outgoingImage.setImageDrawable(null);
            outgoingBack.setImageDrawable(null);
            outgoing.setAlpha(0f);
            if (bitmaps[oldIndex] == old) bitmaps[oldIndex] = null;
            if (old != null && old != bitmap) old.recycle();
        };
        front = back;
        showing = true;
        if (splash.getVisibility() == View.VISIBLE) {
            splash.animate().alpha(0f).setDuration(600).withEndAction(() -> splash.setVisibility(View.GONE)).start();
        }
        if (fade <= 0) {
            incoming.setAlpha(1f);
            finish.run();
            if (item.video != null) startVideo(item, generation);
        } else {
            incoming.setAlpha(0f);
            int gen = generation;
            incoming.animate().alpha(1f).setDuration(fade).withEndAction(() -> {
                finish.run();
                if (item.video != null) startVideo(item, gen);
            }).start();
            outgoing.animate().alpha(0f).setDuration(fade).start();
        }
    }

    private void clearImages() {
        caption.setText("");
        for (int i = 0; i < 2; i++) {
            images[i].setImageDrawable(null);
            backs[i].setImageDrawable(null);
            slots[i].setAlpha(0f);
            if (bitmaps[i] != null) bitmaps[i].recycle();
            bitmaps[i] = null;
        }
        showing = false;
    }

    /** Fill (crop) or fit, from the photo's shape against the screen's and the fit settings. */
    private ImageView.ScaleType scaleFor(int width, int height) {
        int sw = root.getWidth() > 0 ? root.getWidth() : screenLandscape() ? FrameApp.screenLong() : FrameApp.screenShort();
        int sh = root.getHeight() > 0 ? root.getHeight() : screenLandscape() ? FrameApp.screenShort() : FrameApp.screenLong();
        boolean sameShape = (width >= height) == (sw >= sh);
        String mode = sameShape ? prefs.fitSame() : prefs.fitOther();
        if ("near".equals(mode)) {
            // Fill if it keeps at least 80% of the photo: a 3:2 photo on a 16:10 screen fills,
            // a panorama is shown whole
            float photo = width / (float) height, screen = sw / (float) sh;
            mode = Math.min(photo, screen) / Math.max(photo, screen) >= 0.8f ? "fill" : "fit";
        }
        return "fill".equals(mode) ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER;
    }

    /** The colour behind photos that don't fill the screen (the blurred copy goes over it). */
    private int backgroundColor() {
        switch (prefs.background()) {
            case "white": return 0xFFF4F4F2;
            case "grey": return 0xFF202124;
            case "custom":
                try {
                    return Color.parseColor(prefs.backgroundColor());
                } catch (IllegalArgumentException e) {
                    return Color.BLACK;
                }
            default: return Color.BLACK;
        }
    }

    private void updateCaption(Slides.Item item) {
        String mode = prefs.caption();
        caption.setText(photoLabel(item, mode.equals("date") || mode.equals("both"),
                mode.equals("album") || mode.equals("both")));
    }

    /** The photo's date taken and/or album, e.g. "14 August 2024  ·  Smart Picture Frame". */
    private String photoLabel(Slides.Item item, boolean date, boolean album) {
        StringBuilder text = new StringBuilder();
        if (date && item.date > 0) {
            text.append(new SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(new Date(item.date)));
        }
        if (album && item.album != null && !item.album.isEmpty()) {
            if (text.length() > 0) text.append("  ·  ");
            text.append(item.album);
        }
        return text.toString();
    }

    private void scanLocal() {
        if (!prefs.localEnabled()) {
            if (!localItems.isEmpty()) {
                localItems = Collections.emptyList();
                rebuild();
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Ask once per visit; asking from every resume loops when the answer is no
            if (!askedStorage) {
                askedStorage = true;
                Permissions.request(this, Manifest.permission.READ_EXTERNAL_STORAGE, 1);
            }
            return;
        }
        submit(background, () -> {
            List<Slides.Item> found = LocalPhotos.scan(prefs.includeVideos());
            handler.post(() -> {
                if (isDestroyed()) return;
                localItems = found;
                rebuild();
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) scanLocal();
    }

    private void updateStatus() {
        if (showing) return;
        boolean needsSetup = needsSetup();
        String message;
        if (!prefs.iCloudEnabled() && !prefs.localEnabled()) {
            message = "No photo sources are on.";
        } else if (needsSetup) {
            message = "Add an iCloud shared album to get started.";
        } else {
            message = "Loading photos…\n" + FrameApp.get().sync().status().split("\n")[0];
        }
        statusText.setText(message);
        spinner.setVisibility(needsSetup ? View.GONE : View.VISIBLE);
        setupButton.setVisibility(needsSetup ? View.VISIBLE : View.GONE);
        splash.animate().cancel();
        splash.setAlpha(1f);
        splash.setVisibility(View.VISIBLE);
    }

    /** No photo source at all: no album switched on, and local photos off. */
    private boolean needsSetup() {
        return (!prefs.iCloudEnabled() || Albums.enabledIds(this).isEmpty()) && !prefs.localEnabled();
    }

    /**
     * A frame with nothing set up opens straight into phone setup, once each time the app starts
     * (so Done leaves it on the splash, whose button opens it again).
     */
    private boolean openSetupIfNew() {
        if (openedSetupThisRun || !needsSetup()) return false;
        openedSetupThisRun = true;
        startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_WELCOME, true));
        return true;
    }

    private String statusLine() {
        int count = slides.size();
        return count + (count == 1 ? " photo" : " photos") + " · " + FrameApp.get().sync().status().replace("\n", " · ");
    }

    // ---- Clock, weather and night ----

    private void updateClock() {
        if (!prefs.clockShow()) return;
        Date now = new Date();
        String time = new SimpleDateFormat(prefs.clock24h() ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        if (!prefs.clock24h()) time += new SimpleDateFormat(" a", Locale.getDefault()).format(now).toLowerCase(Locale.getDefault());
        clockTime.setText(time);
        clockDate.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
    }

    private void maybeFetchWeather() {
        if (!prefs.weatherShow() || prefs.weatherLat().isEmpty() || prefs.weatherLon().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - weatherFetched < (weather == null ? 60_000 : WEATHER_EVERY_MS)) return;
        weatherFetched = now;
        String lat = prefs.weatherLat(), lon = prefs.weatherLon(), key = weatherKey;
        submit(background, () -> {
            try {
                Weather w = Weather.fetch(lat, lon);
                handler.post(() -> {
                    if (!key.equals(weatherKey)) return;
                    weather = w;
                    updateWeatherText();
                });
            } catch (Exception e) {
                Log.w("Weather failed: " + e);
            }
        });
    }

    private void updateWeatherText() {
        weatherText.setText(weather == null ? "" : weather.format(prefs.weatherFahrenheit()));
    }

    /** The brightness setting, the evening dim, or right down behind the night cover. */
    private void applyBrightness() {
        float level;
        if (nightActive) {
            // 0 turns the backlight right down on most panels
            level = 0f;
        } else if (prefs.dimEnabled() && Schedule.inWindow(prefs.dimStart(), prefs.dimEnd())) {
            float normal = prefs.brightness();
            level = normal < 0 ? prefs.dimLevel() : Math.min(normal, prefs.dimLevel());
        } else {
            // -1 hands brightness back to Android
            level = prefs.brightness();
        }
        if (level == brightness) return;
        brightness = level;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = level < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : level;
        getWindow().setAttributes(lp);
    }

    private void updateNight() {
        boolean night = Schedule.inOffWindow(prefs) && System.currentTimeMillis() > wakeUntil;
        if (night == nightActive) return;
        nightActive = night;
        if (night) stopVideo();
        if (night && Schedule.canTurnOff(this)) {
            // Really switch the panel off; an alarm switches it back on at the "on" time
            handler.removeCallbacks(advanceTask);
            controls.setVisibility(View.GONE);
            Schedule.screenOff(this);
            return;
        }
        // Without admin rights, the best an app can do is a black, dimmed screen
        nightCover.setVisibility(night ? View.VISIBLE : View.GONE);
        applyBrightness();
        if (night) {
            handler.removeCallbacks(advanceTask);
            controls.setVisibility(View.GONE);
        } else {
            scheduleNext();
        }
    }
}
