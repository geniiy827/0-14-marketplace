package com.geniiy.walljumpers;

import android.graphics.Bitmap;

/** Original pixel-art characters, defined as text so every pixel is ours. */
final class Sprites {

    // K outline, B body, H headband/belt, S skin/belly, D eyes, I inner ear, N nose, E robot eyes, R robot red
    private static final String KEYS = "KBHSDINER";

    private static final String[] NINJA_AIR = {
            "...KKKKKK...",
            "..KBBBBBBK..",
            "HHHHHHHHHHK.",
            "..KBSSDSSDK.",
            "..KBBBBBBBK.",
            "...KBBBBBK..",
            "..KBBBBBBBSK",
            "..KHHHHHHHK.",
            "...KBBBBBK..",
            "...KBK.KBK..",
            "..KBBK.KBBK.",
            "..KKK...KKK.",
    };
    private static final String[] NINJA_CLING = {
            "...KKKKKK...",
            "..KBBBBBBK..",
            "HHHHHHHHHHK.",
            "..KBSSDSSDK.",
            "..KBBBBBBBK.",
            ".SKBBBBBBK..",
            "SKBBBBBBBK..",
            "..KHHHHHHHK.",
            "...KBBBBBK..",
            "..KBK..KBK..",
            ".KBBK.KBBK..",
            ".KKK..KKK...",
    };
    private static final String[] ANIMAL_AIR = {
            "..KK....KK..",
            ".KBIK..KIBK.",
            ".KBBBBBBBBK.",
            "KBBBBDBBBDBK",
            "KBBSSSSSSBBK",
            ".KBSSSNNSBK.",
            "..KBBBBBBK..",
            ".KBBSSSSBBK.",
            ".KBBSSSSBBK.",
            "..KBBBBBBK..",
            "..KBK..KBK..",
            "..KKK..KKK..",
    };
    private static final String[] ANIMAL_CLING = {
            "..KK....KK..",
            ".KBIK..KIBK.",
            ".KBBBBBBBBK.",
            "KBBBBDBBBDBK",
            "KBBSSSSSSBBK",
            ".KBSSSNNSBK.",
            ".BKBBBBBBK..",
            "BKBBSSSSBBK.",
            ".KBBSSSSBBK.",
            "..KBBBBBBK..",
            ".KBK..KBK...",
            ".KKK..KKK...",
    };
    private static final String[] ROBOT_AIR = {
            ".......KK...",
            ".......KR...",
            "..KKKKKKKK..",
            "..KBBBBBBK..",
            "..KBEEBEEK..",
            "..KBBBBBBK..",
            "..KKKKKKKK..",
            ".KBBBRRBBBK.",
            ".KBBBBBBBBK.",
            "..KKKKKKKK..",
            "..KBK..KBK..",
            "..KKK..KKK..",
    };
    private static final String[] ROBOT_CLING = {
            ".......KK...",
            ".......KR...",
            "..KKKKKKKK..",
            "..KBBBBBBK..",
            "..KBEEBEEK..",
            "..KBBBBBBK..",
            "..KKKKKKKK..",
            "BKBBBRRBBBK.",
            ".KBBBBBBBBK.",
            "..KKKKKKKK..",
            ".KBK..KBK...",
            ".KKK..KKK...",
    };

    static final class Skin {
        final String name;
        final int price;
        final int body;
        Bitmap air, cling;

        Skin(String name, int price, int body) {
            this.name = name;
            this.price = price;
            this.body = body;
        }
    }

    static Skin[] SKINS;

    private static final int K = 0xFF1B1226;

    static void init() {
        if (SKINS != null) return;
        SKINS = new Skin[]{
                make("Ниндзя", 0, NINJA_AIR, NINJA_CLING,
                        new int[]{K, 0xFF2E3A6B, 0xFFE63946, 0xFFF2C38A, 0xFF111111, 0, 0, 0, 0}),
                make("Лис", 40, ANIMAL_AIR, ANIMAL_CLING,
                        new int[]{K, 0xFFFF8A2A, 0, 0xFFFFF4E6, 0xFF111111, 0xFFFFD9B0, 0xFF2A1A12, 0, 0}),
                make("Лягушка", 80, ANIMAL_AIR, ANIMAL_CLING,
                        new int[]{K, 0xFF5DC452, 0, 0xFFD8F5A2, 0xFF111111, 0xFFFFFFFF, 0xFF2F6B28, 0, 0}),
                make("Робот", 150, ROBOT_AIR, ROBOT_CLING,
                        new int[]{K, 0xFFA7B1BD, 0, 0, 0, 0, 0, 0xFF4DF7FF, 0xFFFF4D4D}),
                make("Панда", 250, ANIMAL_AIR, ANIMAL_CLING,
                        new int[]{K, 0xFFF4F4F4, 0, 0xFFFFFFFF, 0xFF111111, 0xFF2A2A2A, 0xFF2A2A2A, 0, 0}),
                make("Тень", 400, NINJA_AIR, NINJA_CLING,
                        new int[]{K, 0xFF22222A, 0xFF8E44FF, 0xFFD9A877, 0xFFB388FF, 0, 0, 0, 0}),
                make("Золотой", 800, NINJA_AIR, NINJA_CLING,
                        new int[]{K, 0xFFE0A526, 0xFFFFFFFF, 0xFFF2C38A, 0xFF111111, 0, 0, 0, 0}),
        };
    }

    private static Skin make(String name, int price, String[] air, String[] cling, int[] cols) {
        Skin s = new Skin(name, price, cols[1]);
        s.air = bitmap(air, cols);
        s.cling = bitmap(cling, cols);
        return s;
    }

    private static Bitmap bitmap(String[] rows, int[] cols) {
        int h = rows.length, w = rows[0].length();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                char ch = x < rows[y].length() ? rows[y].charAt(x) : '.';
                int k = KEYS.indexOf(ch);
                b.setPixel(x, y, k >= 0 ? cols[k] : 0);
            }
        }
        return b;
    }
}
