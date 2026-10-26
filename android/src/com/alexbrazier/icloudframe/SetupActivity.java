package com.alexbrazier.icloudframe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/**
 * Shows a QR code for the phone setup page. The page is only served while this screen is open,
 * and it closes itself after 15 minutes.
 */
public final class SetupActivity extends Activity implements SetupServer.Listener {
    private static final long CLOSE_AFTER_MS = 15 * 60 * 1000L;
    /** Set when a new frame opens here by itself, to greet rather than just show the code. */
    static final String EXTRA_WELCOME = "welcome";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable close = this::finish;
    private ImageView qr;
    private TextView hint, pinText;
    private Button wifi;
    // The Wi-Fi screen opens on top of this one; keep the same link working meanwhile
    private boolean openingWifi;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // The QR code on the left, how to use it on the right (stacked on a portrait frame)
        boolean wide = FrameApp.screenLandscape(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF101A33, 0xFF05070D}));
        int pad = dp(32);
        layout.setPadding(pad, pad, pad, pad);

        qr = new ImageView(this);
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(dp(20));
        qr.setBackground(card);
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        int side = (int) (Math.min(FrameApp.screenShort(), FrameApp.screenLong()) * (wide ? 0.62f : 0.5f));
        layout.addView(qr, new LinearLayout.LayoutParams(side, side));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(wide ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        if (wide) info.setPadding(dp(40), 0, 0, 0);
        else info.setPadding(0, dp(24), 0, 0);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        heading.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        boolean welcome = getIntent().getBooleanExtra(EXTRA_WELCOME, false);
        TextView title = text(welcome ? "Welcome to iCloud Frame" : "Set up from your phone", 26);
        title.setPadding(dp(14), 0, 0, 0);
        heading.addView(title);
        info.addView(heading);

        hint = text((welcome ? "Scan the code with your phone's camera to add your first shared album and choose "
                + "settings.\n" : "Scan the code with your phone's camera.\n")
                + "Your phone needs to be on the same Wi-Fi as the frame.", 16);
        hint.setTextColor(0xFFB8C2D9);
        hint.setPadding(0, dp(14), 0, dp(18));
        info.addView(hint);

        pinText = text("", 16);
        pinText.setTextColor(0xFFB8C2D9);
        pinText.setLineSpacing(dp(4), 1f);
        info.addView(pinText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(24), 0, 0);
        wifi = button("Wi-Fi", 0x33FFFFFF, () -> {
            openingWifi = true;
            startActivity(new Intent(this, WifiActivity.class));
        });
        wifi.setVisibility(View.GONE);
        buttons.addView(wifi);
        buttons.addView(button("Done", 0xFF3B82F6, this::finish));
        info.addView(buttons);
        int align = wide ? Gravity.START : Gravity.CENTER;
        heading.setGravity(align | Gravity.CENTER_VERTICAL);
        hint.setGravity(align);
        pinText.setGravity(align);
        buttons.setGravity(align);
        layout.addView(info);
        setContentView(layout);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onResume() {
        super.onResume();
        openingWifi = false;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        try {
            SetupServer server = SetupServer.start(this, this);
            qr.setImageBitmap(QrCode.bitmap(server.url(), Math.min(FrameApp.screenShort(), 900)));
            showPin(server);
            wifi.setVisibility(View.GONE);
        } catch (IOException e) {
            qr.setImageDrawable(null);
            hint.setText("Connect the frame to Wi-Fi first, then come back here.");
            pinText.setText("");
            wifi.setVisibility(View.VISIBLE);
        }
        handler.removeCallbacks(close);
        handler.postDelayed(close, CLOSE_AFTER_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // The page only exists while the code is on screen (the 15 minute limit keeps running
        // while the Wi-Fi screen is open)
        if (!openingWifi) {
            handler.removeCallbacks(close);
            SetupServer.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SetupServer.stop();
    }

    private void showPin(SetupServer server) {
        String pin = server.pin();
        if (pin == null) {
            pinText.setText("Typing the address is switched off after too many wrong PINs.\nScan the code, or close this screen and open it again.");
            return;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("No camera? In your phone's browser, go to\n");
        bold(sb, server.base().replace("http://", ""), 1.25f);
        sb.append("\nand enter the PIN  ");
        bold(sb, pin.substring(0, 3) + " " + pin.substring(3), 1.6f);
        pinText.setText(sb);
    }

    private static void bold(android.text.SpannableStringBuilder sb, String text, float size) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(size), start, sb.length(), 0);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Color.WHITE), start, sb.length(), 0);
    }

    @Override
    public void onPinLocked() {
        pinText.setText("Typing the address is switched off after too many wrong PINs.\nScan the code, or close this screen and open it again.");
    }

    @Override
    public void onSaved() {
        Toast.makeText(this, "Settings saved from your phone", Toast.LENGTH_SHORT).show();
        if (getIntent().getBooleanExtra(EXTRA_WELCOME, false) && !Albums.enabledIds(this).isEmpty()) {
            hint.setText("Album added. Keep changing settings on your phone, or tap Done to start the slideshow.");
        }
        handler.removeCallbacks(close);
        handler.postDelayed(close, CLOSE_AFTER_MS);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button button(String label, int color, Runnable action) {
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
        b.setMinWidth(dp(130));
        b.setMinHeight(dp(52));
        b.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(6), 0, dp(6), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run());
        return b;
    }
}
