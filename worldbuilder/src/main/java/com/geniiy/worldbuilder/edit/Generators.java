package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.pe.PeWorld;

import java.util.*;

/** Генератор объектов (раздел 16.13): деревья, кусты, валуны, сферы, арки, колонны, винтовые лестницы. */
public final class Generators {
    private Generators() {}

    public enum Kind {
        OAK("Дуб"), SPRUCE("Ель"), BIRCH("Берёза"), JUNGLE("Тропическое"), ACACIA("Акация"), DARK_OAK("Тёмный дуб"),
        BUSH("Куст"), BOULDER("Валун"), PLANET("Сфера-«планета»"), ARCH("Арка"), COLUMN("Колонна"), SPIRAL("Винтовая лестница");
        public final String ru; Kind(String ru) { this.ru = ru; }
    }

    public static class Params {
        public Kind kind = Kind.OAK;
        public int height = 6, crown = 3, randomness = 30;
        public long seed = 1;
        public int rotation; // 0..3
        public double scale = 1.0;
        public int material = 1 << 4;
        public int material2 = 4 << 4;
    }

    /** Поставить объект с основанием в (x,y,z). */
    public static void place(PeWorld w, int x, int y, int z, Params p, ChangeSet cs) {
        Map<Long, Integer> m = build(p, new Random(p.seed));
        for (var e : m.entrySet()) {
            long k = e.getKey();
            int dx = (int) (k >> 40) - 1000, dy = (int) ((k >> 20) & 0xFFFFF) - 1000, dz = (int) (k & 0xFFFFF) - 1000;
            int rx = dx, rz = dz;
            for (int i = 0; i < p.rotation; i++) { int t = rx; rx = -rz; rz = t; }
            int wy = y + dy;
            if (!PeWorld.inHeight(wy)) continue;
            int cur = w.getState(x + rx, wy, z + rz);
            int v = e.getValue();
            if (v >= 0) {
                if (isLeaves(v) && cur != 0) continue; // листва не затирает существующее
                int s = v;
                for (int i = 0; i < p.rotation; i++) s = com.geniiy.worldbuilder.blocks.Rotation.rotateCW(s);
                w.set(x + rx, wy, z + rz, s, cs);
            }
        }
    }

    static boolean isLeaves(int v) { int id = v >> 4; return id == 18 || id == 161; }

    private static long key(int dx, int dy, int dz) { return ((long) (dx + 1000) << 40) | ((long) (dy + 1000) << 20) | (dz + 1000); }

    /** Список блоков объекта (относительно основания) — для предпросмотра и установки. */
    public static Map<Long, Integer> build(Params p, Random r) {
        Map<Long, Integer> m = new LinkedHashMap<>();
        double rnd = p.randomness / 100.0;
        int h = Math.max(1, (int) Math.round(p.height * p.scale * (1 + (r.nextDouble() - 0.5) * rnd)));
        int cr = Math.max(1, (int) Math.round(p.crown * p.scale));
        switch (p.kind) {
            case OAK, BIRCH, DARK_OAK, JUNGLE -> {
                int log = p.kind == Kind.OAK ? 17 << 4 : p.kind == Kind.BIRCH ? 17 << 4 | 2 : p.kind == Kind.JUNGLE ? 17 << 4 | 3 : 162 << 4 | 1;
                int leaves = p.kind == Kind.OAK ? 18 << 4 | 4 : p.kind == Kind.BIRCH ? 18 << 4 | 6 : p.kind == Kind.JUNGLE ? 18 << 4 | 7 : 161 << 4 | 5;
                boolean thick = p.kind == Kind.DARK_OAK;
                int crownTop = h + 1;
                for (int yy = h - cr; yy <= crownTop; yy++) {
                    int rr = yy >= h ? Math.max(1, cr - 1) : cr;
                    for (int dx = -rr; dx <= rr + (thick ? 1 : 0); dx++) for (int dz = -rr; dz <= rr + (thick ? 1 : 0); dz++) {
                        double d = Math.sqrt(dx * dx + dz * dz + Math.pow((yy - h + 0.5) * 0.9, 2) * 0.3);
                        if (d <= rr + 0.3 + (r.nextDouble() - 0.5) * rnd) m.put(key(dx, yy, dz), leaves);
                    }
                }
                for (int yy = 0; yy < h; yy++) {
                    m.put(key(0, yy, 0), log);
                    if (thick) { m.put(key(1, yy, 0), log); m.put(key(0, yy, 1), log); m.put(key(1, yy, 1), log); }
                }
            }
            case SPRUCE -> {
                int log = 17 << 4 | 1, leaves = 18 << 4 | 5;
                for (int yy = 2; yy <= h + 1; yy++) {
                    int rr = Math.max(0, (int) Math.round(cr * (1 - (yy - 2) / (double) (h)) + ((yy % 2 == 0) ? 0 : -0.6)));
                    for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++)
                        if (Math.abs(dx) + Math.abs(dz) <= rr + (rr > 1 ? 1 : 0)) m.put(key(dx, yy, dz), leaves);
                }
                m.put(key(0, h + 1, 0), leaves);
                for (int yy = 0; yy < h; yy++) m.put(key(0, yy, 0), log);
            }
            case ACACIA -> {
                int log = 162 << 4, leaves = 161 << 4 | 4;
                int bend = h / 2, dx = 0, dz = 0;
                int dir = r.nextInt(4);
                for (int yy = 0; yy < h; yy++) {
                    if (yy > bend) { dx += dir == 0 ? 1 : dir == 1 ? -1 : 0; dz += dir == 2 ? 1 : dir == 3 ? -1 : 0; }
                    m.put(key(dx, yy, dz), log);
                }
                for (int ox = -cr; ox <= cr; ox++) for (int oz = -cr; oz <= cr; oz++) {
                    if (ox * ox + oz * oz <= cr * cr + 1) m.put(key(dx + ox, h, dz + oz), leaves);
                    if (ox * ox + oz * oz <= (cr - 1) * (cr - 1)) m.put(key(dx + ox, h + 1, dz + oz), leaves);
                }
            }
            case BUSH -> {
                int leaves = 18 << 4 | 4;
                m.put(key(0, 0, 0), 17 << 4);
                for (int dx = -cr; dx <= cr; dx++) for (int dy = 0; dy <= cr; dy++) for (int dz = -cr; dz <= cr; dz++)
                    if (dx * dx + dy * dy * 2 + dz * dz <= cr * cr + r.nextInt(2) && !(dx == 0 && dy == 0 && dz == 0)) m.put(key(dx, dy, dz), leaves);
            }
            case BOULDER -> {
                for (int dx = -cr; dx <= cr; dx++) for (int dy = 0; dy <= cr * 2; dy++) for (int dz = -cr; dz <= cr; dz++) {
                    double d = Math.sqrt(dx * dx + (dy - cr) * (dy - cr) * 1.4 + dz * dz) + (r.nextDouble() - 0.5) * rnd * 2;
                    if (d <= cr) m.put(key(dx, dy, dz), r.nextDouble() < 0.3 ? p.material2 : p.material);
                }
            }
            case PLANET -> {
                int R = cr;
                for (int dx = -R; dx <= R; dx++) for (int dy = 0; dy <= 2 * R; dy++) for (int dz = -R; dz <= R; dz++) {
                    double d = Math.sqrt(dx * dx + (dy - R) * (dy - R) + dz * dz);
                    if (d <= R) m.put(key(dx, dy, dz), d > R - 1.5 ? (dy > R ? 2 << 4 : p.material) : d > R - 3 ? 3 << 4 : p.material2);
                }
            }
            case ARCH -> {
                int R = cr + 2, th = Math.max(1, cr / 2);
                for (int dx = -R; dx <= R; dx++) for (int dy = 0; dy <= R; dy++) {
                    double d = Math.sqrt(dx * dx + dy * dy);
                    if (d <= R && d > R - th - 0.5) for (int dz = 0; dz < Math.max(1, th); dz++) m.put(key(dx, dy, dz), p.material);
                }
            }
            case COLUMN -> {
                int R = Math.max(1, cr / 2);
                for (int dy = 0; dy < h; dy++) for (int dx = -R - 1; dx <= R + 1; dx++) for (int dz = -R - 1; dz <= R + 1; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    boolean base = dy == 0 || dy == h - 1;
                    if (d <= R + (base ? 1 : 0) + 0.3) m.put(key(dx, dy, dz), base ? p.material2 : p.material);
                }
            }
            case SPIRAL -> {
                int R = Math.max(2, cr);
                for (int dy = 0; dy < h; dy++) {
                    m.put(key(0, dy, 0), p.material2);
                    double a = dy * Math.PI / 4;
                    for (int rr = 1; rr <= R; rr++) {
                        int dx = (int) Math.round(Math.cos(a) * rr), dz = (int) Math.round(Math.sin(a) * rr);
                        m.put(key(dx, dy, dz), p.material);
                    }
                }
            }
        }
        return m;
    }

    public static int dxOf(long k) { return (int) (k >> 40) - 1000; }
    public static int dyOf(long k) { return (int) ((k >> 20) & 0xFFFFF) - 1000; }
    public static int dzOf(long k) { return (int) (k & 0xFFFFF) - 1000; }
}
