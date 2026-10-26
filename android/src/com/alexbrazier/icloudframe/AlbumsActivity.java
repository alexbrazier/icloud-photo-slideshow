package com.alexbrazier.icloudframe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The saved iCloud albums: tick the ones to show (one or several), add and remove. */
public final class AlbumsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Albums.Album> albums = new ArrayList<>();
    private Adapter adapter;
    // Album covers by album id, with the file each came from so a new newest photo replaces it
    private final Map<String, Bitmap> covers = new HashMap<>();
    private final Map<String, File> coverFiles = new HashMap<>();
    private final ExecutorService coverLoader = Executors.newSingleThreadExecutor();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            reload();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        layout.setPadding(pad, pad, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("Tick the albums to show; several can be on at once. Albums that are off keep their saved photos, so switching back is instant.");
        hint.setPadding(0, 0, 0, dp(8));
        layout.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(button("Add album", this::askForLink));
        buttons.addView(button("Add from your phone", () -> startActivity(new Intent(this, SetupActivity.class))));
        layout.addView(buttons);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(layout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        coverLoader.shutdownNow();
    }

    /** The album's cover, loading it in the background the first time (or when it changes). */
    private Bitmap cover(String album) {
        File file = FrameApp.get().store().cover(album);
        if (file != null && !file.equals(coverFiles.get(album))) {
            coverFiles.put(album, file);
            int size = dp(64);
            coverLoader.execute(() -> {
                Bitmap b = Images.decode(file, size * 2);
                handler.post(() -> {
                    if (isDestroyed() || b == null) return;
                    covers.put(album, b);
                    adapter.notifyDataSetChanged();
                });
            });
        }
        return covers.get(album);
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    private void reload() {
        albums.clear();
        albums.addAll(Albums.list(this));
        adapter.notifyDataSetChanged();
    }

    private void changed() {
        FrameApp.get().sync().wake(true);
        FrameApp.get().settingsChanged();
        reload();
    }

    private void askForLink() {
        EditText link = new EditText(this);
        link.setHint("https://www.icloud.com/sharedalbum/#B0…");
        link.setSingleLine();
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(link, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Add album")
                .setMessage("Paste the shared album's Public Website link. Typing it is easier from your phone.")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String error = Albums.add(this, link.getText().toString());
                    if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    else changed();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() { return albums.isEmpty() ? 1 : albums.size(); }

        @Override
        public Object getItem(int position) { return albums.isEmpty() ? null : albums.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (albums.isEmpty()) {
                TextView empty = new TextView(AlbumsActivity.this);
                empty.setText("No albums yet. Add one with its shared album link.");
                empty.setPadding(0, dp(16), 0, dp(16));
                return empty;
            }
            Albums.Album a = albums.get(position);
            PhotoStore store = FrameApp.get().store();
            LinearLayout row = new LinearLayout(AlbumsActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            CheckBox on = new CheckBox(AlbumsActivity.this);
            on.setChecked(a.on);
            on.setOnCheckedChangeListener((b, checked) -> {
                Albums.setOn(AlbumsActivity.this, a.id, checked);
                changed();
            });
            row.addView(on);

            ImageView thumb = new ImageView(AlbumsActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable placeholder = new GradientDrawable();
            placeholder.setColor(0xFFE2E2E6);
            placeholder.setCornerRadius(dp(8));
            thumb.setBackground(placeholder);
            thumb.setClipToOutline(true);
            thumb.setImageBitmap(cover(a.id));
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(64), dp(64));
            thumbLp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(thumb, thumbLp);

            TextView text = new TextView(AlbumsActivity.this);
            String name = store.name(a.id);
            int count = store.count(a.id);
            String meta = count > 0 ? store.savedCount(a.id) + " of " + count + " saved" : "Not loaded yet";
            String error = store.error(a.id);
            text.setText((name.isEmpty() ? "Album " + a.id.substring(0, 6) + "…" : name) + "\n" + meta
                    + (error.isEmpty() ? "" : "\n" + error));
            text.setTextSize(16);
            text.setPadding(dp(8), 0, dp(8), 0);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

            Button remove = button("Remove", () -> new AlertDialog.Builder(AlbumsActivity.this)
                    .setMessage("Remove " + (name.isEmpty() ? "this album" : name) + " and its saved photos?")
                    .setPositiveButton("Remove", (d, w) -> {
                        Albums.remove(AlbumsActivity.this, a.id);
                        changed();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            row.addView(remove);
            return row;
        }
    }
}
