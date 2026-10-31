package com.geniiy.bridge14.bot;

/** Тот же робот, что и DefaultSkin, но в цветах Claude: кремовый корпус, терракотовые акценты, «искра» на груди. */
public final class ClaudeSkin {
    private static byte[] cache;
    public static volatile String style = "claude";

    public static synchronized byte[] make() {
        if (cache != null) return cache;
        if ("gemini".equalsIgnoreCase(style)) return cache = gemini();
        int W = 64, H = 32;
        int[] px = new int[W * H];
        int body = 0xFFF0EEE6, shade = 0xFFD8D2C4, dark = 0xFF3D3929, light = 0xFFFAF9F5,
                coral = 0xFFD97757, coralDark = 0xFFB85C3E, glow = 0xFFF4B39A;
        // голова
        fill(px, W, 0, 8, 32, 8, body);
        fill(px, W, 8, 0, 16, 8, light);
        fill(px, W, 8, 8, 8, 8, body);           // лицо
        fill(px, W, 9, 10, 6, 3, coral);         // визор
        fill(px, W, 10, 11, 1, 1, glow);
        fill(px, W, 13, 11, 1, 1, glow);
        fill(px, W, 10, 14, 4, 1, dark);         // рот-решётка
        fill(px, W, 8, 8, 8, 1, shade);
        fill(px, W, 0, 10, 1, 2, coral);         // «уши»
        fill(px, W, 23, 10, 1, 2, coral);
        // тело
        fill(px, W, 16, 20, 24, 12, body);
        fill(px, W, 20, 16, 16, 4, light);
        fill(px, W, 20, 20, 8, 12, body);
        // искра Claude на груди: 8x8 на всю ширину груди
        String[] spark = {
                "...XX...",
                ".X.XX.X.",
                "..XXXX..",
                "XXXXXXXX",
                "XXXXXXXX",
                "..XXXX..",
                ".X.XX.X.",
                "...XX..."};
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) if (spark[r].charAt(c) == 'X') set(px, W, 20 + c, 21 + r, coral);
        fill(px, W, 20, 30, 8, 2, coralDark);   // пояс
        // руки и ноги
        fill(px, W, 40, 16, 16, 16, body);
        fill(px, W, 40, 20, 16, 2, coral);
        fill(px, W, 40, 29, 16, 3, shade);
        fill(px, W, 0, 16, 16, 16, shade);
        fill(px, W, 0, 20, 16, 2, coral);
        fill(px, W, 0, 30, 16, 2, dark);
        byte[] out = new byte[W * H * 4];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            out[i * 4] = (byte) (c >> 16); out[i * 4 + 1] = (byte) (c >> 8); out[i * 4 + 2] = (byte) c; out[i * 4 + 3] = (byte) (c >>> 24);
        }
        cache = out;
        return out;
    }

    /** Робот в стиле Google Gemini: тёмно-синий корпус, градиент синий-фиолетовый, четырёхлучевая звезда Gemini на груди, цвета Google на руках/ногах. */
    private static byte[] gemini() {
        int W = 64, H = 32;
        int[] px = new int[W * H];
        int body = 0xFF1F2A44, shade = 0xFF151C30, light = 0xFF2B3A5E, blue = 0xFF4285F4, purple = 0xFF9B72CB,
                pink = 0xFFD96570, white = 0xFFF1F3F4, red = 0xFFEA4335, yellow = 0xFFFBBC05, green = 0xFF34A853;
        fill(px, W, 0, 8, 32, 8, body);
        fill(px, W, 8, 0, 16, 8, light);
        fill(px, W, 8, 8, 8, 8, body);
        fill(px, W, 9, 10, 6, 3, blue);          // визор: градиент
        fill(px, W, 12, 10, 3, 3, purple);
        fill(px, W, 10, 11, 1, 1, white);
        fill(px, W, 13, 11, 1, 1, white);
        fill(px, W, 10, 14, 4, 1, pink);
        fill(px, W, 0, 10, 1, 2, blue);
        fill(px, W, 23, 10, 1, 2, purple);
        fill(px, W, 16, 20, 24, 12, body);
        fill(px, W, 20, 16, 16, 4, light);
        fill(px, W, 20, 20, 8, 12, body);
        String[] star = {
                "...X....",
                "...X....",
                "..XXX...",
                "XXXXXXX.",
                "..XXX...",
                "...X....",
                "...X....",
                "........"};
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) if (star[r].charAt(c) == 'X') set(px, W, 20 + c, 21 + r, r < 3 ? blue : r == 3 ? purple : pink);
        fill(px, W, 20, 30, 2, 2, blue); fill(px, W, 22, 30, 2, 2, red); fill(px, W, 24, 30, 2, 2, yellow); fill(px, W, 26, 30, 2, 2, green);
        fill(px, W, 40, 16, 16, 16, body);
        fill(px, W, 40, 20, 16, 2, blue);
        fill(px, W, 40, 22, 16, 1, purple);
        fill(px, W, 40, 29, 16, 3, shade);
        fill(px, W, 0, 16, 16, 16, shade);
        fill(px, W, 0, 20, 4, 2, blue); fill(px, W, 4, 20, 4, 2, red); fill(px, W, 8, 20, 4, 2, yellow); fill(px, W, 12, 20, 4, 2, green);
        fill(px, W, 0, 30, 16, 2, light);
        byte[] out = new byte[W * H * 4];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            out[i * 4] = (byte) (c >> 16); out[i * 4 + 1] = (byte) (c >> 8); out[i * 4 + 2] = (byte) c; out[i * 4 + 3] = (byte) (c >>> 24);
        }
        return out;
    }

    private static void set(int[] px, int W, int x, int y, int c) { px[y * W + x] = c; }

    private static void fill(int[] px, int W, int x, int y, int w, int h, int c) {
        for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) px[yy * W + xx] = c;
    }
}
