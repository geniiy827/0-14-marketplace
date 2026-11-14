package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.blocks.Rotation;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.util.LongIntMap;
import com.geniiy.worldbuilder.util.Pos;

import java.util.*;

/** Кисти (раздел 19). Один мазок (от нажатия до отпускания) — один шаг отмены. */
public class Brush {
    public enum Form { SPHERE("Шар"), CUBE("Куб"), CYLINDER("Цилиндр"), DISC("Диск"), NOISY("Случайная");
        public final String ru; Form(String ru) { this.ru = ru; } }
    public enum Mode { PLACE("Ставить"), ERASE("Стирать"), PAINT("Красить"), SMOOTH("Сгладить"), RAISE("Поднять/опустить"),
        FLATTEN("Выровнять"), NOISE("Шероховатость"), ERODE("Эрозия/наплыв"), TOPSOIL("Верхний слой"), VEGETATION("Растительность"),
        TREES("Деревья"), STAMP("Штамп"), BIOME("Биом"), GRAVITY("Гравитация");
        public final String ru; Mode(String ru) { this.ru = ru; } }
    public enum Falloff { LINEAR("Линейный"), SMOOTH("Гладкий"), SHARP("Острый"); public final String ru; Falloff(String ru) { this.ru = ru; } }

    public Mode mode = Mode.PLACE;
    public Form form = Form.SPHERE;
    public Falloff falloff = Falloff.SMOOTH;
    public int size = 4;
    public int strength = 50;
    public int depth = 1;
    public double density = 10;
    public boolean symX, symZ;
    public int symCX, symCZ;
    public int placement; // 0 поверхность, 1 в воздухе, 2 по плоскости Y
    public int planeY = 64;
    public Pattern pattern = Pattern.single(1 << 4);
    public Pattern topsoil = defaultTopsoil();
    public Mask mask = Mask.any();
    public int biomeId = 1, biomeColor = 0x92BC59; public boolean biomeAuto = true;
    public Generators.Params tree = new Generators.Params();
    public long seed = 1;
    /** Высота первого клика (для «Выровнять»). */
    public int flattenY = -1;

    static Pattern defaultTopsoil() { Pattern p = new Pattern(); p.kind = Pattern.Kind.SINGLE; p.entries.add(new int[]{2 << 4, 1}); p.entries.add(new int[]{3 << 4, 3}); return p; }

    /** Попадает ли смещение в форму кисти. normal — ось грани (для диска). */
    public boolean inForm(int dx, int dy, int dz, int r, int normalAxis) {
        double rr = r + 0.5;
        switch (form) {
            case CUBE: return Math.abs(dx) <= r && Math.abs(dy) <= r && Math.abs(dz) <= r;
            case CYLINDER: return dx * dx + dz * dz <= rr * rr && Math.abs(dy) <= r;
            case DISC: {
                int a = normalAxis == 0 ? dx : normalAxis == 2 ? dz : dy;
                if (a != 0) return false;
                return dx * dx + dy * dy + dz * dz <= rr * rr;
            }
            case NOISY: {
                double n = Pattern.Noise.perlin(dx * 0.35, dy * 0.35, dz * 0.35, seed) * 0.35 * r;
                return Math.sqrt(dx * dx + dy * dy + dz * dz) <= rr + n;
            }
            default: return dx * dx + dy * dy + dz * dz <= rr * rr;
        }
    }

    double fall(double t) { // t: 0 центр .. 1 край
        t = Math.max(0, Math.min(1, t));
        return switch (falloff) { case LINEAR -> 1 - t; case SHARP -> (1 - t) * (1 - t); default -> 0.5 + 0.5 * Math.cos(Math.PI * t); };
    }

    /** Нанести один «отпечаток» в точке (с учётом симметрии). inverse — Ctrl. */
    public void dab(PeWorld w, int cx, int cy, int cz, int normalAxis, boolean inverse, Clipboard clip, ChangeSet cs, Random rnd) {
        List<int[]> centers = new ArrayList<>();
        centers.add(new int[]{cx, cy, cz});
        if (symX) centers.add(new int[]{2 * symCX - cx, cy, cz});
        if (symZ) centers.add(new int[]{cx, cy, 2 * symCZ - cz});
        if (symX && symZ) centers.add(new int[]{2 * symCX - cx, cy, 2 * symCZ - cz});
        for (int[] c : centers) dabOne(w, c[0], c[1], c[2], normalAxis, inverse, clip, cs, rnd);
    }

    private void dabOne(PeWorld w, int cx, int cy, int cz, int na, boolean inv, Clipboard clip, ChangeSet cs, Random rnd) {
        int r = Math.max(0, size);
        final Mode m = (inv && mode == Mode.PLACE) ? Mode.ERASE : mode;
        switch (m) {
            case PLACE, ERASE -> forEach(r, na, (dx, dy, dz) -> {
                int x = cx + dx, y = cy + dy, z = cz + dz;
                if (!PeWorld.inHeight(y)) return;
                int cur = w.getV(x, y, z);
                if (!mask.isAny() && !mask.test(w, x, y, z, cur)) return;
                w.set(x, y, z, m == Mode.ERASE ? 0 : pattern.apply(x, y, z), cs);
            });
            case PAINT -> forEach(r, na, (dx, dy, dz) -> {
                int x = cx + dx, y = cy + dy, z = cz + dz;
                if (!PeWorld.inHeight(y)) return;
                int cur = w.getV(x, y, z);
                if (cur == 0 || !exposedWithin(w, x, y, z, depth)) return;
                if (!mask.isAny() && !mask.test(w, x, y, z, cur)) return;
                w.set(x, y, z, pattern.apply(x, y, z), cs);
            });
            case SMOOTH -> smooth3d(w, cx, cy, cz, r, na, cs);
            case RAISE, FLATTEN, NOISE -> heightBrush(w, cx, cy, cz, r, inv, m, cs);
            case ERODE -> erode(w, cx, cy, cz, r, na, inv, cs);
            case TOPSOIL -> columns(r, (dx, dz) -> {
                int x = cx + dx, z = cz + dz;
                int top = topSolid(w, x, z, Math.min(127, cy + r), Math.max(0, cy - r));
                if (top < 0) return;
                int d = 0;
                for (int[] e : topsoil.entries) for (int k = 0; k < Math.max(1, e[1]); k++) { int y = top - d; if (y >= 0 && w.getState(x, y, z) != 0) w.set(x, y, z, e[0], cs); d++; }
            });
            case VEGETATION -> columns(r, (dx, dz) -> {
                if (rnd.nextDouble() * 100 >= density) return;
                int x = cx + dx, z = cz + dz;
                int top = topSolid(w, x, z, Math.min(127, cy + r), Math.max(0, cy - r));
                if (top < 0 || top >= 127) return;
                if (inv) { int a = w.getState(x, top, z) >> 4; if (a == 31 || a == 37 || a == 38 || a == 32 || a == 175 || a == 6) w.set(x, top, z, 0, cs); return; }
                int pl = pattern.apply(x, top + 1, z);
                w.set(x, top + 1, z, pl, cs);
                if ((pl >> 4) == 175 && top + 2 < 128) w.set(x, top + 2, z, (175 << 4) | 8 | (pl & 7), cs);
            });
            case TREES -> columns(r, (dx, dz) -> {
                if (rnd.nextDouble() * 100 >= density / 4) return;
                int x = cx + dx, z = cz + dz;
                int top = topSolid(w, x, z, Math.min(127, cy + r), Math.max(0, cy - r));
                if (top < 0 || top >= 120) return;
                int id = w.getState(x, top, z) >> 4;
                if (id != 2 && id != 3 && id != 243) return;
                Generators.Params p = tree; p.seed = rnd.nextLong();
                Generators.place(w, x, top + 1, z, p, cs);
            });
            case STAMP -> {
                if (clip == null) return;
                Clipboard c = clip;
                int rot = rnd.nextInt(4);
                for (int i = 0; i < rot; i++) c = c.rotateCW();
                if (rnd.nextBoolean()) c = c.mirror(Rotation.Op.MIRROR_X);
                c.paste(w, cx - c.w / 2, cy, cz - c.l / 2, true, false, mask, false, true, cs, null);
            }
            case BIOME -> columns(r, (dx, dz) -> {
                int col = biomeAuto ? Biomes.grassColor(biomeId) : biomeColor;
                w.setBiomeColor(cx + dx, cz + dz, ((biomeId & 0xFF) << 24) | (col & 0xFFFFFF), cs);
            });
            case GRAVITY -> columns(r, (dx, dz) -> {
                int x = cx + dx, z = cz + dz;
                int lo = Math.max(0, cy - r), hi = Math.min(127, cy + r);
                List<Integer> stack = new ArrayList<>();
                int floor = lo;
                while (floor <= hi && w.getState(x, floor, z) != 0) floor++;
                for (int y = floor; y <= hi; y++) { int v = w.getState(x, y, z); if (v != 0) stack.add(v); }
                int y = floor;
                for (int v : stack) w.set(x, y++, z, v, cs);
                for (; y <= hi; y++) if (w.getState(x, y, z) != 0) w.set(x, y, z, 0, cs);
            });
        }
    }

    interface V3 { void v(int dx, int dy, int dz); }
    interface V2 { void v(int dx, int dz); }

    void forEach(int r, int na, V3 v) {
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) for (int dy = -r - 1; dy <= r + 1; dy++)
            if (inForm(dx, dy, dz, r, na)) v.v(dx, dy, dz);
    }
    void columns(int r, V2 v) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
            if (form == Form.CUBE || dx * dx + dz * dz <= (r + 0.5) * (r + 0.5)) v.v(dx, dz);
    }

    static boolean exposedWithin(PeWorld w, int x, int y, int z, int depth) {
        for (int d = 1; d <= Math.max(1, depth); d++) {
            if (w.getState(x + d, y, z) == 0 || w.getState(x - d, y, z) == 0 || w.getState(x, y + d, z) == 0 || w.getState(x, y - d, z) == 0
                    || w.getState(x, y, z + d) == 0 || w.getState(x, y, z - d) == 0) return true;
        }
        return false;
    }

    static int topSolid(PeWorld w, int x, int z, int from, int to) {
        for (int y = from; y >= to; y--) { int v = w.getState(x, y, z); if (v != 0 && !Ops.isLiquid(v)) return y; }
        return -1;
    }

    private void smooth3d(PeWorld w, int cx, int cy, int cz, int r, int na, ChangeSet cs) {
        List<long[]> ch = new ArrayList<>();
        forEach(r, na, (dx, dy, dz) -> {
            int x = cx + dx, y = cy + dy, z = cz + dz;
            if (!PeWorld.inHeight(y)) return;
            int solid = 0; Map<Integer, Integer> cnt = new HashMap<>();
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int c = -1; c <= 1; c++) {
                int v = w.getState(x + a, y + b, z + c);
                if (v != 0 && !Ops.isLiquid(v)) { solid++; cnt.merge(v, 1, Integer::sum); }
            }
            int cur = w.getState(x, y, z);
            if (cur == 0 && solid >= 15) ch.add(new long[]{Pos.pack(x, y, z), Collections.max(cnt.entrySet(), Map.Entry.comparingByValue()).getKey()});
            else if (cur != 0 && !Ops.isLiquid(cur) && solid <= 11) ch.add(new long[]{Pos.pack(x, y, z), 0});
        });
        for (long[] c : ch) w.set(Pos.x(c[0]), Pos.y(c[0]), Pos.z(c[0]), (int) c[1], cs);
    }

    private void erode(PeWorld w, int cx, int cy, int cz, int r, int na, boolean fill, ChangeSet cs) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        List<long[]> ch = new ArrayList<>();
        forEach(r, na, (dx, dy, dz) -> {
            int x = cx + dx, y = cy + dy, z = cz + dz;
            if (!PeWorld.inHeight(y)) return;
            int cur = w.getState(x, y, z);
            int air = 0; Map<Integer, Integer> cnt = new HashMap<>();
            for (int[] o : d) { int v = w.getState(x + o[0], y + o[1], z + o[2]); if (v == 0) air++; else cnt.merge(v, 1, Integer::sum); }
            if (!fill && cur != 0 && air >= 4) ch.add(new long[]{Pos.pack(x, y, z), 0});
            if (fill && cur == 0 && air <= 2 && !cnt.isEmpty()) ch.add(new long[]{Pos.pack(x, y, z), Collections.max(cnt.entrySet(), Map.Entry.comparingByValue()).getKey()});
        });
        for (long[] c : ch) w.set(Pos.x(c[0]), Pos.y(c[0]), Pos.z(c[0]), (int) c[1], cs);
    }

    private void heightBrush(PeWorld w, int cx, int cy, int cz, int r, boolean inv, Mode m, ChangeSet cs) {
        double str = strength / 100.0;
        columns(r, (dx, dz) -> {
            int x = cx + dx, z = cz + dz;
            int top = topSolid(w, x, z, 127, 0);
            if (top < 0) return;
            double t = Math.sqrt(dx * dx + dz * dz) / (r + 0.5);
            double f = fall(t);
            int target;
            if (m == Mode.RAISE) target = top + (int) Math.round((inv ? -1 : 1) * Math.max(1, r * 0.5) * str * f);
            else if (m == Mode.FLATTEN) { int fy = flattenY < 0 ? cy : flattenY; target = (int) Math.round(top + (fy - top) * Math.min(1, f * str * 2)); }
            else { double n = Pattern.Noise.perlin(x * 0.15, 0, z * 0.15, seed); target = top + (int) Math.round(n * r * str * f); }
            target = Math.max(0, Math.min(127, target));
            if (target == top) return;
            int surface = w.getState(x, top, z), under = top > 0 ? w.getState(x, top - 1, z) : surface;
            if (under == 0) under = surface;
            if (target > top) {
                for (int y = top; y < target; y++) w.set(x, y, z, under, cs);
                w.set(x, target, z, surface, cs);
            } else {
                for (int y = top; y > target; y--) w.set(x, y, z, 0, cs);
                w.set(x, target, z, surface, cs);
            }
        });
    }

    /** Воксели формы для предпросмотра. */
    public LongIntMap previewVoxels(int cx, int cy, int cz, int na) {
        LongIntMap m = new LongIntMap(256);
        int r = Math.min(size, 32);
        forEach(r, na, (dx, dy, dz) -> m.put(Pos.pack(cx + dx, cy + dy, cz + dz), 1));
        return m;
    }
}
