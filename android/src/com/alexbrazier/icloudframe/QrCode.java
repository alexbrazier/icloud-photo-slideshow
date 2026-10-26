package com.alexbrazier.icloudframe;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder for the phone-setup link: byte mode, error correction level M,
 * versions 1 to 6 (up to 106 bytes), which covers a LAN URL with a key in it.
 */
final class QrCode {
    // Per version (index 1-6): data codewords per block, number of blocks, EC codewords per block
    private static final int[] DATA_PER_BLOCK = {0, 16, 28, 44, 32, 43, 27};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16};
    private static final int[] ALIGNMENT = {0, 0, 18, 22, 26, 30, 34};

    private final int size;
    private final boolean[][] modules;
    private final boolean[][] function;

    private QrCode(int version) {
        size = 17 + 4 * version;
        modules = new boolean[size][size];
        function = new boolean[size][size];
    }

    static boolean[][] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (version <= 6 && capacity(version) < bytes.length) version++;
        if (version > 6) throw new IllegalArgumentException("Text too long for a QR code");

        int dataCodewords = DATA_PER_BLOCK[version] * BLOCKS[version];
        byte[] data = dataCodewords(bytes, dataCodewords);
        byte[] all = withErrorCorrection(data, version);

        QrCode qr = new QrCode(version);
        qr.drawFunctionPatterns(version);
        qr.drawData(all);
        int best = 0, bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            qr.applyMask(mask);
            qr.drawFormat(mask);
            int penalty = qr.penalty();
            if (penalty < bestPenalty) {
                best = mask;
                bestPenalty = penalty;
            }
            qr.applyMask(mask); // XOR again to undo
        }
        qr.applyMask(best);
        qr.drawFormat(best);
        return qr.modules;
    }

    /** Renders with the standard 4-module quiet zone, scaled to about sizePx. */
    static Bitmap bitmap(String text, int sizePx) {
        boolean[][] m = encode(text);
        int n = m.length + 8;
        int scale = Math.max(1, sizePx / n);
        int side = n * scale;
        int[] pixels = new int[side * side];
        for (int py = 0; py < side; py++) {
            int my = py / scale - 4;
            for (int px = 0; px < side; px++) {
                int mx = px / scale - 4;
                boolean dark = my >= 0 && mx >= 0 && my < m.length && mx < m.length && m[my][mx];
                pixels[py * side + px] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
    }

    private static int capacity(int version) {
        // Mode (4 bits) and an 8-bit length come before the bytes
        return (DATA_PER_BLOCK[version] * BLOCKS[version] * 8 - 12) / 8;
    }

    private static byte[] dataCodewords(byte[] bytes, int count) {
        int[] bits = new int[count * 8];
        int n = 0;
        n = append(bits, n, 0b0100, 4);
        n = append(bits, n, bytes.length, 8);
        for (byte b : bytes) n = append(bits, n, b & 0xFF, 8);
        n += Math.min(4, bits.length - n); // terminator
        n = (n + 7) / 8 * 8;
        byte[] out = new byte[count];
        for (int i = 0; i < n / 8; i++) {
            int v = 0;
            for (int j = 0; j < 8; j++) v = (v << 1) | bits[i * 8 + j];
            out[i] = (byte) v;
        }
        for (int i = n / 8, pad = 0; i < count; i++, pad ^= 1) out[i] = (byte) (pad == 0 ? 0xEC : 0x11);
        return out;
    }

    private static int append(int[] bits, int n, int value, int length) {
        for (int i = length - 1; i >= 0; i--) bits[n++] = (value >>> i) & 1;
        return n;
    }

    private static byte[] withErrorCorrection(byte[] data, int version) {
        int blocks = BLOCKS[version], per = DATA_PER_BLOCK[version], ec = EC_PER_BLOCK[version];
        byte[] divisor = rsDivisor(ec);
        byte[][] dataBlocks = new byte[blocks][], ecBlocks = new byte[blocks][];
        for (int b = 0; b < blocks; b++) {
            dataBlocks[b] = new byte[per];
            System.arraycopy(data, b * per, dataBlocks[b], 0, per);
            ecBlocks[b] = rsRemainder(dataBlocks[b], divisor);
        }
        // Interleave: a codeword from each block in turn (all blocks are the same size here)
        byte[] out = new byte[blocks * (per + ec)];
        int k = 0;
        for (int i = 0; i < per; i++) for (int b = 0; b < blocks; b++) out[k++] = dataBlocks[b][i];
        for (int i = 0; i < ec; i++) for (int b = 0; b < blocks; b++) out[k++] = ecBlocks[b][i];
        return out;
    }

    // ---- Reed-Solomon over GF(256), polynomial 0x11D ----

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    // ---- Matrix ----

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (version >= 2) {
            int p = ALIGNMENT[version];
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) set(p + dx, p + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormat(0); // reserves the format areas; redrawn once the mask is chosen
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, dist != 2 && dist != 4);
            }
        }
    }

    private void drawFormat(int mask) {
        int data = mask; // level M's bits are 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true); // the always-dark module
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void drawData(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5; // skip the vertical timing pattern
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (function[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** The standard penalty score; only used to pick a mask that scans well. */
    private int penalty() {
        int score = 0, dark = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
                    boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) score += 3;
                        else if (run > 5) score++;
                    } else {
                        run = 1;
                    }
                }
                // Finder-like 1:1:3:1:1 runs with light space on one side
                for (int b = 0; b + 10 < size; b++) {
                    if (matches(pass, a, b, new boolean[] {true, false, true, true, true, false, true, false, false, false, false})
                            || matches(pass, a, b, new boolean[] {false, false, false, false, true, false, true, true, true, false, true})) {
                        score += 40;
                    }
                }
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x + 1 < size && y + 1 < size) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) score += 3;
                }
            }
        }
        int percent = dark * 100 / (size * size);
        score += Math.abs(percent - 50) / 5 * 10;
        return score;
    }

    private boolean matches(int pass, int a, int b, boolean[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            boolean v = pass == 0 ? modules[a][b + i] : modules[b + i][a];
            if (v != pattern[i]) return false;
        }
        return true;
    }
}
