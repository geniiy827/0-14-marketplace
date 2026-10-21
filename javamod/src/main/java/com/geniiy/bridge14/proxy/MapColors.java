package com.geniiy.bridge14.proxy;

/** Перевод RGBA-картинки карты 0.14 в цвета карт Java (палитра 1.20.1: 61 цвет × 4 оттенка). */
public final class MapColors {
    private static final int[] BASE = {0, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8,
            0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C,
            0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5, 0x4A80FF, 0x00D93A,
            0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A, 0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C,
            0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E,
            0x14B485, 0x646464, 0xD8AF93, 0x7FA796};
    private static final int[] MUL = {180, 220, 255, 135};
    private static final int[] R = new int[BASE.length * 4], G = new int[R.length], B = new int[R.length];
    private static final byte[] CACHE = new byte[1 << 15];
    private static final boolean[] HAVE = new boolean[1 << 15];

    static {
        for (int i = 4; i < R.length; i++) {
            int c = BASE[i >> 2], m = MUL[i & 3];
            R[i] = ((c >> 16) & 255) * m / 255; G[i] = ((c >> 8) & 255) * m / 255; B[i] = (c & 255) * m / 255;
        }
    }

    private MapColors() {}

    public static synchronized byte nearest(int r, int g, int b, int a) {
        if (a < 128) return 0;
        int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
        if (HAVE[key]) return CACHE[key];
        int best = 4; long bd = Long.MAX_VALUE;
        for (int i = 4; i < R.length; i++) {
            long dr = R[i] - r, dg = G[i] - g, db = B[i] - b;
            long d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bd) { bd = d; best = i; }
        }
        CACHE[key] = (byte) best; HAVE[key] = true;
        return (byte) best;
    }
}
