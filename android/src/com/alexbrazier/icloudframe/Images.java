package com.alexbrazier.icloudframe;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Decoding at screen size, with EXIF orientation applied, and saving screen-sized copies. */
final class Images {
    private Images() {}

    /** Width and height after EXIF rotation, or null when the file isn't an image. */
    static int[] size(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        return swapsSides(rotation(file)) ? new int[] {o.outHeight, o.outWidth} : new int[] {o.outWidth, o.outHeight};
    }

    /** Decodes so that the long side is about maxLong pixels (never upscaled), upright. */
    static Bitmap decode(File file, int maxLong) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        // Power-of-two subsampling is cheap and keeps memory low on 1 GB frames
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxLong) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(file.getPath(), o);
        } catch (OutOfMemoryError e) {
            o.inSampleSize = sample * 2;
            bitmap = BitmapFactory.decodeFile(file.getPath(), o);
        }
        if (bitmap == null) return null;

        int degrees = rotation(file);
        boolean mirror = mirrored(file);
        float scale = Math.min(1f, maxLong / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (degrees == 0 && !mirror && scale > 0.9f) return bitmap;
        Matrix m = new Matrix();
        if (scale <= 0.9f) m.postScale(scale, scale);
        // Mirrored orientations are a rotation followed by a horizontal flip
        if (degrees != 0) m.postRotate(degrees);
        if (mirror) m.postScale(-1, 1);
        Bitmap out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        if (out != bitmap) bitmap.recycle();
        return out;
    }

    /** A video's first frame, upright and scaled like decode(), for showing before it plays. */
    static Bitmap videoFrame(File video, int maxLong) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(video.getPath());
            Bitmap frame = r.getFrameAtTime(0);
            if (frame == null) return null;
            float scale = Math.min(1f, maxLong / (float) Math.max(frame.getWidth(), frame.getHeight()));
            if (scale > 0.9f) return frame;
            Bitmap out = Bitmap.createScaledBitmap(frame, Math.round(frame.getWidth() * scale),
                    Math.round(frame.getHeight() * scale), true);
            if (out != frame) frame.recycle();
            return out;
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // Already released
            }
        }
    }

    private static final int BLUR_SIDE = 64;

    /**
     * A tiny, blurred copy for behind photos that don't fill the screen. Scaled up by the view,
     * it looks like a heavy blur, and it works on every Android version (RenderEffect is 12+).
     */
    static Bitmap blurred(Bitmap source) {
        int sw = source.getWidth(), sh = source.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        float s = BLUR_SIDE / (float) Math.max(sw, sh);
        int w = Math.max(1, Math.round(sw * s)), h = Math.max(1, Math.round(sh * s));
        Bitmap small = Bitmap.createScaledBitmap(source, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != source) small.recycle();
        // Three box blurs approximate a Gaussian
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, 2, true);
            boxBlur(tmp, px, w, h, 2, false);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int red = 0, green = 0, blue = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int c = in[horizontal ? line * w + k : k * w + line];
                    red += (c >> 16) & 0xFF;
                    green += (c >> 8) & 0xFF;
                    blue += c & 0xFF;
                    n++;
                }
                out[horizontal ? line * w + i : i * w + line] = 0xFF000000 | (red / n) << 16 | (green / n) << 8 | (blue / n);
            }
        }
    }

    /** When the photo was taken, from EXIF, or 0 when it doesn't say. */
    static long takenAt(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getPath());
            String v = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (v == null) v = exif.getAttribute(ExifInterface.TAG_DATETIME);
            if (v == null) return 0;
            java.util.Date d = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.ROOT).parse(v.trim());
            return d == null ? 0 : d.getTime();
        } catch (IOException | java.text.ParseException | RuntimeException e) {
            return 0;
        }
    }

    /** A small JPEG of the photo, for album covers. */
    static byte[] thumbnail(File file, int maxLong) {
        Bitmap b = decode(file, maxLong);
        if (b == null) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, out);
        b.recycle();
        return out.toByteArray();
    }

    /** Saves an upright, screen-sized JPEG copy. Returns its {width, height}. */
    static int[] saveScaled(File source, File dest, int maxLong) throws IOException {
        Bitmap bitmap = decode(source, maxLong);
        if (bitmap == null) throw new IOException("Not an image");
        File tmp = new File(dest.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("Couldn't save image");
        }
        int[] size = {bitmap.getWidth(), bitmap.getHeight()};
        bitmap.recycle();
        if (!tmp.renameTo(dest)) throw new IOException("Couldn't save image");
        return size;
    }

    private static int rotation(File file) {
        switch (orientation(file)) {
            case ExifInterface.ORIENTATION_ROTATE_90:
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return 270;
            default:
                return 0;
        }
    }

    private static boolean mirrored(File file) {
        int o = orientation(file);
        return o == ExifInterface.ORIENTATION_FLIP_HORIZONTAL || o == ExifInterface.ORIENTATION_FLIP_VERTICAL
                || o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE;
    }

    private static boolean swapsSides(int degrees) {
        return degrees == 90 || degrees == 270;
    }

    private static int orientation(File file) {
        try {
            return new ExifInterface(file.getPath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }
}
