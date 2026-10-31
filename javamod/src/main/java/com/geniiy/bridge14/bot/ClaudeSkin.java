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

    /** Gemini: белая одежда, логотип Google «G» на животе, синие джинсы, лицо человека (не робот). */
    private static byte[] gemini() {
        int W = 64, H = 32;
        int[] px = new int[W * H];
        int skin = 0xFFF2C9A5, skinSh = 0xFFD9A982, hair = 0xFF3B2A20, eye = 0xFF4285F4, mouth = 0xFFB5544B,
                white = 0xFFFAFAFA, whiteSh = 0xFFE3E6EA, jeans = 0xFF2F4A80, jeansSh = 0xFF243A66, shoe = 0xFF202124,
                B = 0xFF4285F4, R = 0xFFEA4335, Y = 0xFFFBBC05, G = 0xFF34A853;
        // голова: кожа, волосы сверху и сзади
        fill(px, W, 0, 8, 32, 8, skin);
        fill(px, W, 8, 0, 8, 8, hair);           // макушка
        fill(px, W, 16, 0, 8, 8, skinSh);        // подбородок снизу
        fill(px, W, 0, 8, 32, 2, hair);          // волосы по кругу
        fill(px, W, 24, 8, 8, 8, hair);          // затылок
        fill(px, W, 0, 10, 2, 3, hair); fill(px, W, 22, 10, 2, 3, hair);
        fill(px, W, 9, 11, 2, 1, white); fill(px, W, 10, 11, 1, 1, eye);   // глаза
        fill(px, W, 13, 11, 2, 1, white); fill(px, W, 13, 11, 1, 1, eye);
        fill(px, W, 11, 13, 2, 1, skinSh);       // нос
        fill(px, W, 10, 14, 4, 1, mouth);        // улыбка
        fill(px, W, 9, 13, 1, 1, mouth); fill(px, W, 14, 13, 1, 1, mouth);
        // тело: белая футболка
        fill(px, W, 16, 20, 24, 12, white);
        fill(px, W, 20, 16, 16, 4, white);
        fill(px, W, 16, 20, 4, 12, whiteSh); fill(px, W, 28, 20, 4, 12, whiteSh);
        fill(px, W, 32, 20, 8, 12, white);
        fill(px, W, 22, 20, 4, 1, skin);         // вырез
        // логотип «G» на животе (лицевая сторона тела 20..27 x 20..31), 6x6
        String[] g = {
                ".RRRR.",
                "R....R",
                "Y.....",
                "Y..BBB",
                "G....B",
                ".GGGG."};
        for (int r = 0; r < 6; r++) for (int c = 0; c < 6; c++) {
            char ch = g[r].charAt(c);
            int col = ch == 'R' ? R : ch == 'Y' ? Y : ch == 'G' ? G : ch == 'B' ? B : 0;
            if (col != 0) set(px, W, 21 + c, 24 + r, col);
        }
        // руки: белые рукава до локтя, дальше кожа
        fill(px, W, 40, 16, 16, 16, skin);
        fill(px, W, 40, 20, 16, 5, white);
        fill(px, W, 44, 16, 4, 4, white);
        fill(px, W, 48, 16, 4, 4, skinSh);
        // ноги: джинсы, кроссовки
        fill(px, W, 0, 16, 16, 16, jeans);
        fill(px, W, 0, 20, 4, 12, jeansSh);
        fill(px, W, 0, 29, 16, 3, shoe);
        fill(px, W, 8, 16, 4, 4, shoe);
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
