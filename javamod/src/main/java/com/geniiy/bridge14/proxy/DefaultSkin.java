package com.geniiy.bridge14.proxy;

/** Простой скин-робот 64x32 (RGBA), рисуется кодом. */
public final class DefaultSkin {
    private static byte[] cache;

    public static synchronized byte[] make() {
        if (cache != null) return cache;
        int W = 64, H = 32;
        int[] px = new int[W * H];
        int metal = 0xFF9AA3AD, dark = 0xFF5B636B, light = 0xFFC9D1D9, visor = 0xFF1EC8FF, glow = 0xFFB8F2FF, accent = 0xFFFF8A1E;
        for (int i = 0; i < px.length; i++) px[i] = 0;
        // голова (8x8x8): верх 8..16,0..8; низ 16..24,0..8; бока y 8..16
        fill(px, W, 0, 8, 32, 8, metal);
        fill(px, W, 8, 0, 16, 8, light);
        fill(px, W, 8, 8, 8, 8, metal);          // лицо
        fill(px, W, 9, 10, 6, 3, visor);         // визор
        fill(px, W, 10, 11, 1, 1, glow);
        fill(px, W, 13, 11, 1, 1, glow);
        fill(px, W, 10, 14, 4, 1, dark);         // рот-решётка
        fill(px, W, 8, 8, 8, 1, dark);
        // антенна сбоку
        fill(px, W, 0, 10, 1, 2, accent);
        fill(px, W, 23, 10, 1, 2, accent);
        // тело 16..40, 16..32
        fill(px, W, 16, 20, 24, 12, metal);
        fill(px, W, 20, 16, 16, 4, light);
        fill(px, W, 20, 20, 8, 12, metal);
        fill(px, W, 22, 22, 4, 3, dark);
        fill(px, W, 23, 23, 2, 1, accent);
        fill(px, W, 20, 30, 8, 2, dark);
        // руки 40..56, 16..32 и ноги 0..16, 16..32
        fill(px, W, 40, 16, 16, 16, metal);
        fill(px, W, 40, 20, 16, 2, dark);
        fill(px, W, 40, 29, 16, 3, light);
        fill(px, W, 0, 16, 16, 16, dark);
        fill(px, W, 0, 20, 16, 2, metal);
        fill(px, W, 0, 30, 16, 2, 0xFF3A4046);
        byte[] out = new byte[W * H * 4];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            out[i * 4] = (byte) (c >> 16); out[i * 4 + 1] = (byte) (c >> 8); out[i * 4 + 2] = (byte) c; out[i * 4 + 3] = (byte) (c >>> 24);
        }
        cache = out;
        return out;
    }

    private static void fill(int[] px, int W, int x, int y, int w, int h, int c) {
        for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) px[yy * W + xx] = c;
    }
}
