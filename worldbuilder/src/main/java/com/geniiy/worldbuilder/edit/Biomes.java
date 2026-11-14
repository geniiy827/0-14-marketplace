package com.geniiy.worldbuilder.edit;

import java.util.*;

/** Биомы: ID (как в Java 1.7), русские имена и цвет травы по формуле Genisys (раздел 3.6). */
public final class Biomes {
    private Biomes() {}
    public record B(int id, String ru, double temp, double rain, boolean genisys) {}
    public static final Map<Integer, B> ALL = new LinkedHashMap<>();
    private static final Map<Integer, Integer> FIXED = new HashMap<>();

    static void a(int id, String ru, double t, double r) { ALL.put(id, new B(id, ru, t, r, false)); }
    static void g(int id, String ru, double t, double r, int color) { ALL.put(id, new B(id, ru, t, r, true)); FIXED.put(id, color); }

    static {
        g(0, "Океан", 0.5, 0.5, 0x8DB771);
        g(1, "Равнины", 0.8, 0.4, 0x92BC59);
        g(2, "Пустыня", 2.0, 0.0, 0xFCB813);
        g(3, "Горы", 0.4, 0.5, 0x8DB679);
        g(4, "Лес", 0.8, 0.5, 0x8ABC56);
        g(5, "Тайга", 0.05, 0.8, 0x82B493);
        g(6, "Болото", 0.8, 0.9, 0x6AC44D);
        g(7, "Река", 0.5, 0.7, 0x86B96F);
        g(8, "Ад (Нижний мир)", 2.0, 0.0, 0x000000);
        a(9, "Край", 0.5, 0.5);
        a(10, "Замёрзший океан", 0.0, 0.5);
        a(11, "Замёрзшая река", 0.0, 0.5);
        g(12, "Ледяные равнины", 0.05, 0.8, 0x82B493);
        a(13, "Ледяные горы", 0.0, 0.5);
        a(14, "Грибной остров", 0.9, 1.0);
        a(15, "Берег грибного острова", 0.9, 1.0);
        a(16, "Пляж", 0.8, 0.4);
        a(17, "Холмы пустыни", 2.0, 0.0);
        a(18, "Лесистые холмы", 0.7, 0.8);
        a(19, "Холмы тайги", 0.25, 0.8);
        g(20, "Малые горы", 0.4, 0.5, 0x8DB679);
        a(21, "Джунгли", 0.95, 0.9);
        a(22, "Холмы джунглей", 0.95, 0.9);
        a(23, "Окраина джунглей", 0.95, 0.8);
        a(24, "Глубокий океан", 0.5, 0.5);
        a(25, "Каменистый берег", 0.2, 0.3);
        a(26, "Заснеженный пляж", 0.05, 0.3);
        g(27, "Березняк", 0.5, 0.5, 0x8DB771);
        a(28, "Холмы березняка", 0.6, 0.6);
        a(29, "Тёмный лес", 0.7, 0.8);
        a(30, "Заснеженная тайга", -0.5, 0.4);
        a(31, "Холмы заснеженной тайги", -0.5, 0.4);
        a(32, "Мегатайга", 0.3, 0.8);
        a(33, "Холмы мегатайги", 0.3, 0.8);
        a(34, "Горы+", 0.2, 0.3);
        a(35, "Саванна", 1.2, 0.0);
        a(36, "Плато саванны", 1.0, 0.0);
        a(37, "Плато (Меса)", 2.0, 0.0);
        a(38, "Лесное плато мезы", 2.0, 0.0);
        a(39, "Плато мезы", 2.0, 0.0);
        a(129, "Подсолнечные равнины", 0.8, 0.4);
        a(130, "Пустыня M", 2.0, 0.0);
        a(131, "Горы M", 0.2, 0.3);
        a(132, "Цветочный лес", 0.7, 0.8);
        a(133, "Тайга M", 0.25, 0.8);
        a(134, "Болото M", 0.8, 0.9);
        a(140, "Ледяные шипы", 0.0, 0.5);
        a(149, "Джунгли M", 0.95, 0.9);
        a(151, "Окраина джунглей M", 0.95, 0.8);
        a(155, "Березняк M", 0.6, 0.6);
        a(156, "Холмы березняка M", 0.6, 0.6);
        a(157, "Тёмный лес M", 0.7, 0.8);
        a(158, "Заснеженная тайга M", -0.5, 0.4);
        a(160, "Еловая мегатайга", 0.25, 0.8);
        a(161, "Холмы еловой мегатайги", 0.25, 0.8);
        a(162, "Горы+ M", 0.2, 0.3);
        a(163, "Саванна M", 1.1, 0.0);
        a(164, "Плато саванны M", 1.0, 0.0);
        a(165, "Меса (Брайс)", 2.0, 0.0);
        a(166, "Лесное плато мезы M", 2.0, 0.0);
        a(167, "Плато мезы M", 2.0, 0.0);
    }

    public static String name(int id) { B b = ALL.get(id); return b == null ? "Биом " + id : b.ru; }

    /** Цвет травы «авто»: таблица Genisys, иначе формула Biome::generateBiomeColor. */
    public static int grassColor(int id) {
        Integer f = FIXED.get(id);
        if (f != null) return f;
        B b = ALL.get(id);
        if (b == null) return 0x92BC59;
        return formula(Math.max(-0.5, Math.min(2.0, b.temp)), Math.max(0, Math.min(1, b.rain)));
    }

    public static int formula(double temperature, double rainfall) {
        double x = (1 - temperature) * 255, z = (1 - rainfall * temperature) * 255;
        int[] l1 = lerp(0x47D033, 0x6CB493, x / 256), l2 = lerp(0xBFB655, 0x80B497, x / 256);
        int r = clamp(l1[0] + (l2[0] - l1[0]) * z / 256), g = clamp(l1[1] + (l2[1] - l1[1]) * z / 256), bb = clamp(l1[2] + (l2[2] - l1[2]) * z / 256);
        return (r << 16) | (g << 8) | bb;
    }
    private static int[] lerp(int a, int b, double t) {
        return new int[]{(int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t), (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t), (int) ((a & 255) + ((b & 255) - (a & 255)) * t)};
    }
    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }

    public static int value(int id) { return ((id & 0xFF) << 24) | grassColor(id); }

    /** Цвет для слоя «Показать биомы». */
    public static int debugColor(int id) {
        int h = id * 0x9E3779B1;
        return 0xFF000000 | ((h >>> 8) & 0xFFFFFF);
    }
}
