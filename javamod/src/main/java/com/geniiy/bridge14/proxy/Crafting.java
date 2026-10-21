package com.geniiy.bridge14.proxy;

import com.geniiy.bridge14.pe.Buf;
import com.geniiy.bridge14.pe.Proto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Рецепты сервера 0.14 (CraftingDataPacket) и подбор рецепта по сетке Java 2×2 / 3×3. */
public final class Crafting {
    public static final class Recipe {
        public int type; public int w, h; public int[][] ing; public int[] result; public UUID id;
    }

    public static final class Match {
        public Recipe recipe; public int[][] input = new int[9][]; public boolean big;
    }

    private final List<Recipe> recipes = new ArrayList<>();

    public synchronized int size() { return recipes.size(); }

    public synchronized List<Recipe> all() { return new ArrayList<>(recipes); }

    public synchronized void parse(Buf b) {
        recipes.clear();
        int n = b.i32();
        for (int i = 0; i < n && b.left() >= 8; i++) {
            int type = b.i32(), len = b.i32();
            if (len < 0 || len > b.left()) break;
            Buf e = new Buf(b.b, b.pos, b.pos + len);
            b.skip(len);
            try {
                if (type == 0) {
                    Recipe r = new Recipe(); r.type = 0;
                    int c = e.i32(); r.ing = new int[c][];
                    for (int k = 0; k < c; k++) r.ing[k] = ingredient(Proto.readSlot(e));
                    e.i32(); r.result = Proto.readSlot(e);
                    r.id = new UUID(e.i64(), e.i64());
                    recipes.add(r);
                } else if (type == 1) {
                    Recipe r = new Recipe(); r.type = 1;
                    r.w = e.i32(); r.h = e.i32();
                    if (r.w < 1 || r.h < 1 || r.w > 3 || r.h > 3) continue;
                    r.ing = new int[r.w * r.h][];
                    for (int k = 0; k < r.ing.length; k++) r.ing[k] = ingredient(Proto.readSlot(e));
                    e.i32(); r.result = Proto.readSlot(e);
                    r.id = new UUID(e.i64(), e.i64());
                    recipes.add(r);
                }
            } catch (Exception ignored) {}
        }
    }

    /** Для журнала: рецепты, где встречаются все предметы из сетки (чтобы понять, чем формат сервера отличается). */
    public synchronized String similar(int[][] grid) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Recipe rec : recipes) {
            boolean all = true, any = false;
            for (int[] g : grid) {
                if (empty(g)) continue;
                any = true;
                boolean has = false;
                for (int[] in : rec.ing) if (!empty(in) && in[0] == g[0]) has = true;
                if (!has) all = false;
            }
            if (!any || !all) continue;
            sb.append(" | тип ").append(rec.type).append(' ').append(rec.w).append('x').append(rec.h).append(" ->")
              .append(empty(rec.result) ? "-" : rec.result[0] + ":" + rec.result[2] + "x" + rec.result[1]).append(" [");
            for (int[] in : rec.ing) sb.append(empty(in) ? "-" : in[0] + ":" + in[2] + "x" + in[1]).append(' ');
            sb.append(']');
            if (++shown >= 3) break;
        }
        return sb.toString();
    }

    /** Некоторые серверы пишут у ингредиента количество 0 — это всё равно «1 штука». */
    private static int[] ingredient(int[] it) { if (it != null && it[0] > 0 && it[1] <= 0) it[1] = 1; return it; }

    private static boolean empty(int[] it) { return it == null || it[0] == 0 || it[1] <= 0; }

    private static boolean fits(int[] ing, int[] it) {
        if (empty(ing)) return empty(it);
        if (empty(it)) return false;
        return ing[0] == it[0] && (ing[2] == -1 || ing[2] == 0x7fff || ing[2] == it[2]);
    }

    private static int[] one(int[] it) { int[] c = it.clone(); c[1] = 1; return c; }

    /** grid — size×size (строка*size+столбец). */
    public synchronized Match match(int[][] grid, int size) {
        int r0 = 9, r1 = -1, c0 = 9, c1 = -1, count = 0;
        for (int r = 0; r < size; r++) for (int c = 0; c < size; c++) if (!empty(grid[r * size + c])) {
            r0 = Math.min(r0, r); r1 = Math.max(r1, r); c0 = Math.min(c0, c); c1 = Math.max(c1, c); count++;
        }
        if (count == 0) return null;
        int bw = c1 - c0 + 1, bh = r1 - r0 + 1;
        for (Recipe rec : recipes) {
            if (rec.type == 1) {
                // обычно ширина×высота и строки подряд; у некоторых серверов размеры/порядок переставлены — пробуем и так
                for (int variant = 0; variant < 4; variant++) {
                    boolean swapDims = (variant & 1) != 0, colMajor = (variant & 2) != 0;
                    int rw = swapDims ? rec.h : rec.w, rh = swapDims ? rec.w : rec.h;
                    if (rw != bw || rh != bh) continue;
                    if (variant > 0 && bw == 1 && bh == 1) continue;
                    for (int mirror = 0; mirror < 2; mirror++) {
                        boolean ok = true;
                        for (int y = 0; y < bh && ok; y++) for (int x = 0; x < bw; x++) {
                            int gx = mirror == 0 ? x : bw - 1 - x;
                            int k = colMajor ? x * bh + y : y * bw + x;
                            if (!fits(rec.ing[k], grid[(r0 + y) * size + c0 + gx])) { ok = false; break; }
                        }
                        if (!ok) continue;
                        Match m = new Match(); m.recipe = rec; m.big = bw > 2 || bh > 2;
                        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
                            int[] it = null;
                            if (x < bw && y < bh) { int gx = mirror == 0 ? x : bw - 1 - x; it = grid[(r0 + y) * size + c0 + gx]; }
                            m.input[y * 3 + x] = empty(it) ? new int[4] : one(it);
                        }
                        return m;
                    }
                }
            } else {
                int need = 0;
                for (int[] in : rec.ing) need += empty(in) ? 0 : in[1];
                if (need != count) continue;
                int[] left = new int[rec.ing.length];
                for (int k = 0; k < left.length; k++) left[k] = empty(rec.ing[k]) ? 0 : rec.ing[k][1];
                boolean ok = true;
                List<int[]> used = new ArrayList<>();
                for (int i = 0; i < size * size && ok; i++) {
                    int[] it = grid[i];
                    if (empty(it)) continue;
                    boolean found = false;
                    for (int k = 0; k < left.length; k++) if (left[k] > 0 && fits(rec.ing[k], it)) { left[k]--; found = true; break; }
                    if (!found) ok = false; else used.add(one(it));
                }
                if (!ok) continue;
                Match m = new Match(); m.recipe = rec; m.big = bw > 2 || bh > 2 || count > 4;
                for (int i = 0; i < 9; i++) m.input[i] = i < used.size() ? used.get(i) : new int[4];
                return m;
            }
        }
        return null;
    }
}
