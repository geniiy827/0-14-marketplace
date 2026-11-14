package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.blocks.Rotation;
import com.geniiy.worldbuilder.pe.PeChunk;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.util.LongIntMap;
import com.geniiy.worldbuilder.util.Pos;

import java.util.*;

/** Действия над выделением (раздел 18 ТЗ). Все — один шаг отмены через ChangeSet. */
public final class Ops {
    private Ops() {}

    public interface P extends Clipboard.Task {}
    static final P NOP = new P() { public void check() {} public void progress(double p) {} };

    private static void prog(P p, int[] b, int x) { if (p != null) { p.check(); p.progress((x - b[0]) / (double) Math.max(1, b[3] - b[0] + 1)); } }

    public static boolean isLiquid(int st) { int id = st >> 4; return id >= 8 && id <= 11; }

    // ---------- заполнить / заменить / очистить ----------
    public static void fill(PeWorld w, Selection s, Pattern pat, boolean onlyAir, Mask mask, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        prepPattern(pat, s);
        int[] lastX = {Integer.MIN_VALUE};
        s.forEach((x, y, z) -> {
            if (x != lastX[0]) { lastX[0] = x; prog(p, b, x); }
            int cur = w.getV(x, y, z);
            if (onlyAir && cur != 0) return;
            if (mask != null && !mask.isAny() && !mask.test(w, x, y, z, cur)) return;
            w.set(x, y, z, pat.apply(x, y, z), cs);
        });
    }

    static void prepPattern(Pattern pat, Selection s) {
        int[] b = s.bounds();
        if (b != null) { pat.gradMin = pat.axis == 0 ? b[0] : pat.axis == 2 ? b[2] : b[1]; pat.gradMax = pat.axis == 0 ? b[3] : pat.axis == 2 ? b[5] : b[4]; }
    }

    public static void replace(PeWorld w, Selection s, Mask what, Pattern to, boolean keepOrientation, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        prepPattern(to, s);
        int[] lastX = {Integer.MIN_VALUE};
        s.forEach((x, y, z) -> {
            if (x != lastX[0]) { lastX[0] = x; prog(p, b, x); }
            int cur = w.getV(x, y, z);
            if (!what.test(w, x, y, z, cur)) return;
            int nv = to.apply(x, y, z);
            if (keepOrientation && cur > 0 && nv > 0) nv = transferOrientation(cur, nv);
            w.set(x, y, z, nv, cs);
        });
    }

    /** Перенести биты направления/половины от src к dst, если семейства совпадают. */
    public static int transferOrientation(int src, int dst) {
        PeBlocks t = PeBlocks.get();
        PeBlocks.BlockInfo a = t.block(src >> 4), b = t.block(dst >> 4);
        if (a == null || b == null || !a.family.equals(b.family)) return dst;
        int sm = src & 15, dm = dst & 15;
        int nm = switch (a.family) {
            case "stairs", "door", "trapdoor", "fence_gate", "torch", "button", "lever", "facing4_face", "facing4_h", "facing6", "chest", "rail", "repeater", "sign_post", "bed", "vine", "hopper", "anvil" -> sm;
            case "slab" -> (dm & 7) | (sm & 8);
            case "log" -> (dm & 3) | (sm & 0xC);
            case "axis" -> sm;
            default -> dm;
        };
        return (dst & ~15) | nm;
    }

    public static void clear(PeWorld w, Selection s, boolean keepLiquids, boolean removeEntities, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        int[] lastX = {Integer.MIN_VALUE};
        s.forEach((x, y, z) -> {
            if (x != lastX[0]) { lastX[0] = x; prog(p, b, x); }
            int cur = w.getV(x, y, z);
            if (cur == 0 || (keepLiquids && isLiquid(cur))) return;
            w.set(x, y, z, 0, cs);
        });
        if (removeEntities) removeEntities(w, s);
    }

    /** Удалить сущности, чьи координаты внутри выделения (без отмены — с предупреждением в интерфейсе). */
    public static int removeEntities(PeWorld w, Selection s) {
        int[] b = s.bounds(); if (b == null) return 0;
        int n = 0;
        for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) {
            PeChunk c = w.getChunk(cx, cz);
            if (c == null || c.corrupt || c.entities() == null) continue;
            var list = c.entities().list;
            for (Iterator<com.geniiy.worldbuilder.nbt.Tag> it = list.iterator(); it.hasNext(); ) {
                var t = it.next();
                if (!(t instanceof com.geniiy.worldbuilder.nbt.Tag.CompoundTag ct)) continue;
                var posL = ct.getList("Pos");
                if (posL == null || posL.size() < 3) continue;
                int ex = (int) Math.floor(num(posL.get(0))), ey = (int) Math.floor(num(posL.get(1))), ez = (int) Math.floor(num(posL.get(2)));
                if (s.contains(ex, ey, ez)) { it.remove(); n++; c.markDirty(); }
            }
        }
        return n;
    }
    private static double num(com.geniiy.worldbuilder.nbt.Tag t) {
        if (t instanceof com.geniiy.worldbuilder.nbt.Tag.DoubleTag d) return d.v;
        if (t instanceof com.geniiy.worldbuilder.nbt.Tag.FloatTag f) return f.v;
        return 0;
    }

    // ---------- стены, пол, оболочка ----------
    public static void walls(PeWorld w, Selection s, int thick, Pattern pat, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        prepPattern(pat, s);
        s.forEach((x, y, z) -> {
            boolean wall = false;
            for (int t = 1; t <= thick && !wall; t++)
                wall = !s.contains(x + t, y, z) || !s.contains(x - t, y, z) || !s.contains(x, y, z + t) || !s.contains(x, y, z - t);
            if (wall) w.set(x, y, z, pat.apply(x, y, z), cs);
        });
    }

    public static void floorCeiling(PeWorld w, Selection s, int thick, boolean ceiling, Pattern pat, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        prepPattern(pat, s);
        s.forEach((x, y, z) -> {
            boolean edge = false;
            for (int t = 1; t <= thick && !edge; t++) edge = !s.contains(x, ceiling ? y + t : y - t, z);
            if (edge) w.set(x, y, z, pat.apply(x, y, z), cs);
        });
    }

    public static void shell(PeWorld w, Selection s, int thick, Pattern inside, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        s.forEach((x, y, z) -> {
            boolean edge = false;
            for (int t = 1; t <= thick && !edge; t++)
                edge = !s.contains(x + t, y, z) || !s.contains(x - t, y, z) || !s.contains(x, y + t, z) || !s.contains(x, y - t, z) || !s.contains(x, y, z + t) || !s.contains(x, y, z - t);
            if (!edge) w.set(x, y, z, inside == null ? 0 : inside.apply(x, y, z), cs);
        });
    }

    public static void edges(PeWorld w, Selection s, Pattern pat, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        s.forEach((x, y, z) -> {
            int c = 0;
            if (x == b[0] || x == b[3]) c++;
            if (y == b[1] || y == b[4]) c++;
            if (z == b[2] || z == b[5]) c++;
            if (c >= 2) w.set(x, y, z, pat.apply(x, y, z), cs);
        });
    }

    public static void outline(PeWorld w, Selection s, Pattern pat, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        LongIntMap todo = new LongIntMap(1024);
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        s.forEach((x, y, z) -> { for (int[] o : d) if (!s.contains(x + o[0], y + o[1], z + o[2])) todo.put(Pos.pack(x + o[0], y + o[1], z + o[2]), 1); });
        todo.forEach((k, v) -> { int x = Pos.x(k), y = Pos.y(k), z = Pos.z(k); if (y >= 0 && y < 128) w.set(x, y, z, pat.apply(x, y, z), cs); });
    }

    // ---------- поверхность ----------
    /** Накрыть сверху: на каждую верхнюю открытую поверхность в колонке положить N слоёв. */
    public static void overlay(PeWorld w, Selection s, Pattern pat, int layers, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        for (int x = b[0]; x <= b[3]; x++) { prog(p, b, x); for (int z = b[2]; z <= b[5]; z++) {
            for (int y = b[4]; y >= b[1]; y--) {
                if (!s.contains(x, y, z)) continue;
                int v = w.getState(x, y, z);
                if (v != 0 && !isLiquid(v)) {
                    for (int k = 1; k <= layers; k++) if (y + k < 128 && w.getState(x, y + k, z) == 0) w.set(x, y + k, z, pat.apply(x, y + k, z), cs);
                    break;
                }
            }
        } }
    }

    static boolean natural(int st) {
        int id = st >> 4;
        return id == 1 || id == 2 || id == 3 || id == 12 || id == 13 || id == 24 || id == 87 || id == 82 || id == 110 || id == 243 || id == 198 || id == 60 || id == 4;
    }

    public static void naturalize(PeWorld w, Selection s, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        for (int x = b[0]; x <= b[3]; x++) { prog(p, b, x); for (int z = b[2]; z <= b[5]; z++) {
            int depth = -1;
            for (int y = 127; y >= b[1]; y--) {
                int v = w.getState(x, y, z);
                if (v == 0 || !natural(v)) { if (v == 0 || isLiquid(v)) depth = -1; else depth = 99; continue; }
                depth = depth < 0 ? 0 : depth + 1;
                if (!s.contains(x, y, z)) continue;
                int nv = depth == 0 ? 2 << 4 : depth <= 3 ? 3 << 4 : 1 << 4;
                w.set(x, y, z, nv, cs);
            }
        } }
    }

    public static void decorate(PeWorld w, Selection s, Pattern plants, double density, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        Random r = new Random(plants.seed);
        for (int x = b[0]; x <= b[3]; x++) { prog(p, b, x); for (int z = b[2]; z <= b[5]; z++) {
            for (int y = b[4]; y >= b[1]; y--) {
                int v = w.getState(x, y, z);
                if (v == 0) continue;
                int id = v >> 4;
                if ((id == 2 || id == 3 || id == 110 || id == 243 || id == 12) && y + 1 < 128 && w.getState(x, y + 1, z) == 0 && s.contains(x, y + 1, z) && r.nextDouble() * 100 < density) {
                    int pl = plants.apply(x, y + 1, z);
                    w.set(x, y + 1, z, pl, cs);
                    if ((pl >> 4) == 175 && y + 2 < 128 && w.getState(x, y + 2, z) == 0) w.set(x, y + 2, z, (175 << 4) | 8 | (pl & 7), cs);
                }
                break;
            }
        } }
    }

    /** Сгладить рельеф по карте высот колонок с сохранением верхнего слоя. */
    public static void smoothHeight(PeWorld w, Selection s, int strength, int iterations, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return;
        int sw = b[3] - b[0] + 1, sl = b[5] - b[2] + 1;
        double[] h = new double[sw * sl];
        int[] top = new int[sw * sl];
        for (int x = 0; x < sw; x++) for (int z = 0; z < sl; z++) {
            int y = b[4];
            while (y >= b[1] && (w.getState(b[0] + x, y, b[2] + z) == 0 || isLiquid(w.getState(b[0] + x, y, b[2] + z)))) y--;
            h[z * sw + x] = y; top[z * sw + x] = y;
        }
        int rad = Math.max(1, strength);
        for (int it = 0; it < Math.max(1, iterations); it++) {
            double[] n = new double[h.length];
            for (int x = 0; x < sw; x++) for (int z = 0; z < sl; z++) {
                double sum = 0, wt = 0;
                for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                    int xx = x + dx, zz = z + dz;
                    if (xx < 0 || zz < 0 || xx >= sw || zz >= sl) continue;
                    double ww = Math.exp(-(dx * dx + dz * dz) / (2.0 * rad * rad / 2));
                    sum += h[zz * sw + xx] * ww; wt += ww;
                }
                n[z * sw + x] = sum / wt;
            }
            h = n;
        }
        for (int x = 0; x < sw; x++) { if (p != null) { p.check(); p.progress(x / (double) sw); } for (int z = 0; z < sl; z++) {
            int wx = b[0] + x, wz = b[2] + z;
            int old = top[z * sw + x], nt = (int) Math.round(h[z * sw + x]);
            if (old < b[1]) continue;
            nt = Math.max(b[1], Math.min(b[4], nt));
            int surface = w.getState(wx, old, wz), under = old > 0 ? w.getState(wx, old - 1, wz) : surface;
            if (under == 0) under = surface;
            if (nt > old) {
                for (int y = old; y < nt; y++) if (s.contains(wx, y, wz)) w.set(wx, y, wz, under, cs);
                if (s.contains(wx, nt, wz)) w.set(wx, nt, wz, surface, cs);
            } else if (nt < old) {
                for (int y = old; y > nt; y--) if (s.contains(wx, y, wz)) w.set(wx, y, wz, 0, cs);
                if (s.contains(wx, nt, wz)) w.set(wx, nt, wz, surface, cs);
            }
        } }
    }

    // ---------- жидкости ----------
    public static void waterBelow(PeWorld w, Selection s, int level, boolean lava, ChangeSet cs, P p) {
        int st = (lava ? 11 : 9) << 4;
        int[] b = s.bounds(); if (b == null) return;
        s.forEach((x, y, z) -> { if (y <= level && w.getV(x, y, z) == 0) w.set(x, y, z, st, cs); });
    }
    public static void drain(PeWorld w, Selection s, ChangeSet cs, P p) {
        s.forEach((x, y, z) -> { if (isLiquid(w.getState(x, y, z))) w.set(x, y, z, 0, cs); });
    }
    public static void fixLiquids(PeWorld w, Selection s, ChangeSet cs, P p) {
        s.forEach((x, y, z) -> {
            int v = w.getState(x, y, z), id = v >> 4;
            if (id == 8 || id == 9) w.set(x, y, z, 9 << 4, cs);
            else if (id == 10 || id == 11) w.set(x, y, z, 11 << 4, cs);
        });
    }

    // ---------- парящие блоки, расширение/сжатие ----------
    public static int removeFloating(PeWorld w, Selection s, int minSize, ChangeSet cs, P p) {
        int[] b = s.bounds(); if (b == null) return 0;
        LongIntMap seen = new LongIntMap(1024);
        int[] removed = {0};
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        s.forEach((x, y, z) -> {
            long k = Pos.pack(x, y, z);
            if (seen.get(k, 0) != 0 || w.getState(x, y, z) == 0) return;
            ArrayDeque<Long> q = new ArrayDeque<>();
            List<Long> comp = new ArrayList<>();
            q.add(k); seen.put(k, 1);
            boolean grounded = false;
            while (!q.isEmpty() && comp.size() <= minSize) {
                long c = q.poll(); comp.add(c);
                int cx = Pos.x(c), cy = Pos.y(c), cz = Pos.z(c);
                if (cy == 0 || cy == b[1]) grounded = true;
                for (int[] o : d) {
                    int nx = cx + o[0], ny = cy + o[1], nz = cz + o[2];
                    if (ny < 0 || ny > 127) continue;
                    if (w.getState(nx, ny, nz) == 0) continue;
                    if (!s.contains(nx, ny, nz)) { grounded = true; continue; }
                    long nk = Pos.pack(nx, ny, nz);
                    if (seen.get(nk, 0) != 0) continue;
                    seen.put(nk, 1); q.add(nk);
                }
            }
            if (!grounded && comp.size() < minSize && q.isEmpty()) {
                for (long c : comp) { w.set(Pos.x(c), Pos.y(c), Pos.z(c), 0, cs); removed[0]++; }
            }
        });
        return removed[0];
    }

    public static void dilateErode(PeWorld w, Selection s, boolean dilate, int threshold, ChangeSet cs, P p) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        List<long[]> changes = new ArrayList<>();
        s.forEach((x, y, z) -> {
            int v = w.getState(x, y, z);
            if (dilate && v == 0) {
                Map<Integer, Integer> cnt = new HashMap<>(); int solid = 0;
                for (int[] o : d) { int n = w.getState(x + o[0], y + o[1], z + o[2]); if (n != 0 && !isLiquid(n)) { solid++; cnt.merge(n, 1, Integer::sum); } }
                if (solid >= threshold) changes.add(new long[]{Pos.pack(x, y, z), Collections.max(cnt.entrySet(), Map.Entry.comparingByValue()).getKey()});
            } else if (!dilate && v != 0) {
                int air = 0;
                for (int[] o : d) if (w.getState(x + o[0], y + o[1], z + o[2]) == 0) air++;
                if (air >= threshold) changes.add(new long[]{Pos.pack(x, y, z), 0});
            }
        });
        for (long[] c : changes) w.set(Pos.x(c[0]), Pos.y(c[0]), Pos.z(c[0]), (int) c[1], cs);
    }

    // ---------- поворот, отражение, размножение ----------
    /** Повернуть/отразить содержимое выделения вокруг его центра. */
    public static Selection transform(PeWorld w, Selection s, int quarterTurns, Rotation.Op mirror, ChangeSet cs, P p) {
        Clipboard c = Clipboard.copy(w, s, true);
        if (c == null) return s;
        int[] b = s.bounds();
        Ops.clear(w, s, false, false, cs, p);
        Clipboard r = c;
        for (int i = 0; i < ((quarterTurns % 4) + 4) % 4; i++) r = r.rotateCW();
        if (mirror != null) r = r.mirror(mirror);
        int cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
        int ox = cx - (r.w - 1) / 2, oz = cz - (r.l - 1) / 2;
        r.paste(w, ox, b[1], oz, true, false, null, true, true, cs, p);
        Selection ns = new Selection();
        if (s.shapes.size() == 1 && s.shapes.get(0).type != Shape.Type.VOXELS && quarterTurns % 2 == 0) { ns = s.copy(); }
        else {
            Shape sh = new Shape(Shape.Type.CUBOID, ox, b[1], oz, ox + r.w - 1, b[1] + r.h - 1, oz + r.l - 1);
            ns.add(sh, Shape.Mode.NEW);
        }
        return ns;
    }

    public static void stack(PeWorld w, Selection s, int count, int dx, int dy, int dz, int gap, boolean skipAir, ChangeSet cs, P p) {
        Clipboard c = Clipboard.copy(w, s, false);
        if (c == null) return;
        int sx = dx != 0 ? (c.w + gap) * dx : 0, sy = dy != 0 ? (c.h + gap) * dy : 0, sz = dz != 0 ? (c.l + gap) * dz : 0;
        for (int i = 1; i <= count; i++) {
            if (p != null) { p.check(); p.progress((i - 1) / (double) count); }
            c.paste(w, c.srcX + sx * i, c.srcY + sy * i, c.srcZ + sz * i, skipAir, false, null, false, true, cs, null);
        }
    }

    // ---------- статистика, биомы ----------
    public static LinkedHashMap<Integer, Long> stats(PeWorld w, Selection s) {
        HashMap<Integer, Long> m = new HashMap<>();
        s.forEach((x, y, z) -> m.merge(w.getV(x, y, z), 1L, Long::sum));
        LinkedHashMap<Integer, Long> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted((a, b2) -> Long.compare(b2.getValue(), a.getValue())).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public static void setBiome(PeWorld w, Selection s, int biomeId, int color, ChangeSet cs) {
        int[] b = s.bounds(); if (b == null) return;
        Set<Long> cols = new HashSet<>();
        s.forEach((x, y, z) -> cols.add(Pos.colKey(x, z)));
        int v = ((biomeId & 0xFF) << 24) | (color & 0xFFFFFF);
        for (long k : cols) w.setBiomeColor(Pos.ckX(k), Pos.ckZ(k), v, cs);
    }
}
