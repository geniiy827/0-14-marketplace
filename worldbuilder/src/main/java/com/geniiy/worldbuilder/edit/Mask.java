package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.pe.PeWorld;

import java.util.*;

/** Ограничение «к каким блокам применять». Простой конструктор: список условий, И/ИЛИ, «НЕ» у каждого. */
public class Mask {
    public enum Kind { ANY("Любые блоки"), BLOCKS("Список блоков"), AIR("Только воздух"), NOT_AIR("Не воздух"), SURFACE("Поверхность"),
        VISIBLE("Видимые"), YRANGE("Диапазон Y"), BIOME("Биом"), STUB("Заглушка импорта");
        public final String ru; Kind(String ru) { this.ru = ru; } }

    public static class Cond {
        public Kind kind = Kind.BLOCKS;
        public boolean not;
        public final List<int[]> blocks = new ArrayList<>(); // {state, anyMeta(0/1)}
        public int yMin = 0, yMax = 127, biome = 1;
        public int stub = -1; // −1 — любая заглушка, иначе виртуальное значение
        public Cond copy() { Cond c = new Cond(); c.kind = kind; c.not = not; for (int[] b : blocks) c.blocks.add(b.clone()); c.yMin = yMin; c.yMax = yMax; c.biome = biome; c.stub = stub; return c; }
    }

    public String name = "";
    public boolean or; // false — все условия (И), true — любое (ИЛИ)
    public final List<Cond> conds = new ArrayList<>();

    public static Mask any() { return new Mask(); }
    public static Mask of(Kind k) { Mask m = new Mask(); Cond c = new Cond(); c.kind = k; m.conds.add(c); return m; }
    public static Mask blocks(int state, boolean anyMeta) { Mask m = new Mask(); Cond c = new Cond(); c.kind = Kind.BLOCKS; c.blocks.add(new int[]{state, anyMeta ? 1 : 0}); m.conds.add(c); return m; }

    public boolean isAny() { return conds.isEmpty() || (conds.size() == 1 && conds.get(0).kind == Kind.ANY && !conds.get(0).not); }

    public boolean test(PeWorld w, int x, int y, int z, int v) {
        if (conds.isEmpty()) return true;
        for (Cond c : conds) {
            boolean r = testOne(c, w, x, y, z, v) != c.not;
            if (or && r) return true;
            if (!or && !r) return false;
        }
        return !or;
    }

    private static boolean testOne(Cond c, PeWorld w, int x, int y, int z, int v) {
        switch (c.kind) {
            case ANY: return true;
            case AIR: return v == 0;
            case NOT_AIR: return v != 0;
            case BLOCKS:
                for (int[] b : c.blocks) {
                    if (v < 0) continue;
                    if (b[1] != 0 ? (b[0] >> 4) == (v >> 4) : b[0] == v) return true;
                }
                return false;
            case STUB: return v < 0 && (c.stub == -1 || c.stub == v);
            case SURFACE: return v != 0 && w.getState(x, y + 1, z) == 0;
            case VISIBLE: {
                if (v == 0) return false;
                int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                for (int[] o : d) {
                    int n = w.getState(x + o[0], y + o[1], z + o[2]);
                    if (n == 0) return true;
                    PeBlocks.BlockInfo b = PeBlocks.get().block(n >> 4);
                    if (b != null && b.transparent) return true;
                }
                return false;
            }
            case YRANGE: return y >= c.yMin && y <= c.yMax;
            case BIOME: return ((w.getBiomeColor(x, z) >>> 24) & 0xFF) == c.biome;
        }
        return true;
    }

    public String describe() {
        if (isAny()) return "любые";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conds.size(); i++) {
            Cond c = conds.get(i);
            if (i > 0) sb.append(or ? " ИЛИ " : " И ");
            if (c.not) sb.append("НЕ ");
            if (c.kind == Kind.BLOCKS) {
                List<String> n = new ArrayList<>();
                for (int[] b : c.blocks) n.add(b[1] != 0 ? PeBlocks.get().block(b[0] >> 4) != null ? PeBlocks.get().block(b[0] >> 4).nameRu : "#" + (b[0] >> 4) : PeBlocks.get().displayName(b[0]));
                sb.append(n.isEmpty() ? "(пусто)" : String.join(", ", n));
            } else if (c.kind == Kind.YRANGE) sb.append("Y ").append(c.yMin).append("…").append(c.yMax);
            else sb.append(c.kind.ru.toLowerCase());
        }
        return sb.toString();
    }

    public Mask copy() { Mask m = new Mask(); m.name = name; m.or = or; for (Cond c : conds) m.conds.add(c.copy()); return m; }
}
