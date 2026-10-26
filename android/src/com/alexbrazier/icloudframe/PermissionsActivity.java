package com.alexbrazier.icloudframe;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Everything the app asks Android for, what it's for, and whether it's allowed. */
public final class PermissionsActivity extends Activity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from Android's settings screens, so the answers may have changed
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        render();
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    private void render() {
        list.removeAllViews();
        TextView intro = text("What the frame asks Android for, and why. Nothing here is needed just to show "
                + "photos; each one makes the frame run more on its own.", 15, 0xFF5F6368);
        intro.setPadding(0, 0, 0, dp(12));
        list.addView(intro);
        for (Permissions.Item item : Permissions.list(this)) list.addView(row(item));
    }

    private View row(Permissions.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(item.title, 17, 0xFF202124);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(badge(item.status));
        row.addView(top);

        TextView purpose = text(item.purpose, 14, 0xFF5F6368);
        purpose.setPadding(0, dp(4), 0, 0);
        row.addView(purpose);
        if (item.detail != null) {
            TextView detail = text(item.detail, 14, 0xFF202124);
            detail.setPadding(0, dp(4), 0, 0);
            row.addView(detail);
        }
        if (item.action != null && item.actionLabel != null) {
            Button b = new Button(this);
            b.setText(item.actionLabel);
            b.setAllCaps(false);
            b.setOnClickListener(v -> item.action.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(8);
            row.addView(b, lp);
        }

        View line = new View(this);
        line.setBackgroundColor(0xFFE2E2E6);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(line, new LinearLayout.LayoutParams(-1, 1));
        return wrap;
    }

    private TextView badge(Permissions.Status status) {
        String label;
        int color;
        switch (status) {
            case ALLOWED: label = "Allowed"; color = 0xFF1E8E3E; break;
            case DENIED: label = "Not allowed"; color = 0xFFD93025; break;
            case NOT_ASKED: label = "Not asked yet"; color = 0xFF5F6368; break;
            case NOT_NEEDED: label = "Not needed"; color = 0xFF5F6368; break;
            case NOT_POSSIBLE: label = "Not possible"; color = 0xFF5F6368; break;
            default: label = "Automatic"; color = 0xFF1E8E3E;
        }
        TextView t = text(label, 13, color);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), color);
        t.setBackground(bg);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        return t;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
