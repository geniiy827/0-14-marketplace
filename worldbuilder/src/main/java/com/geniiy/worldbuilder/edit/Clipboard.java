package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.blocks.Rotation;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.nbt.Tag.CompoundTag;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.pe.TileUtil;

import java.util.*;

/**
 * Буфер обмена: блоки (виртуальные состояния PE, &lt;0 — заглушки), тайлы с относительными координатами, биомы колонок.
 * Поворот и отражение перекодируют меты (раздел 21) и пересчитывают пары двойных сундуков.
 */
public class Clipboard {
    public static final int NONE = Integer.MIN_VALUE; // клетка не входит в выделение
    public int w, h, l;
    public int[] data;
    public int[] biomes; // w*l или null
    /** Тайлы: индекс → тайл (x,y,z — относительные; pairx/pairz — относительные). */
    public final Map<Integer, CompoundTag> tiles = new HashMap<>();
    public final List<String> stubPalette = new ArrayList<>();
    public String name = "Буфер";
    public long time = System.currentTimeMillis();
    public boolean pinned;
    /** Смещение опорной точки при копировании (мировые координаты минимального угла). */
    public int srcX, srcY, srcZ;

    public Clipboard(int w, int h, int l) { this.w = w; this.h = h; this.l = l; data = new int[w * h * l]; Arrays.fill(data, NONE); }

    public int idx(int x, int y, int z) { return (y * l + z) * w + x; }
    public int get(int x, int y, int z) { return data[idx(x, y, z)]; }
    public void set(int x, int y, int z, int v) { data[idx(x, y, z)] = v; }
    public long volume() { return (long) w * h * l; }
    public long blockCount() { long c = 0; for (int v : data) if (v != NONE && v != 0) c++; return c; }

    /** Скопировать выделение мира. */
    public static Clipboard copy(PeWorld world, Selection sel, boolean withBiomes) {
        int[] b = sel.bounds();
        if (b == null) return null;
        Clipboard c = new Clipboard(b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1);
        c.srcX = b[0]; c.srcY = b[1]; c.srcZ = b[2];
        Map<Integer, Integer> stubRemap = new HashMap<>();
        sel.forEach((x, y, z) -> {
            int v = world.getV(x, y, z);
            if (v < 0) {
                int nv = stubRemap.computeIfAbsent(v, k -> { c.stubPalette.add(world.stubName(k)); return -c.stubPalette.size(); });
                v = nv;
            }
            int i = c.idx(x - b[0], y - b[1], z - b[2]);
            c.data[i] = v;
            CompoundTag t = world.getTile(x, y, z);
            if (t != null) c.tiles.put(i, TileUtil.moved(t, x - b[0], y - b[1], z - b[2]));
        });
        if (withBiomes) {
            c.biomes = new int[c.w * c.l];
            for (int x = 0; x < c.w; x++) for (int z = 0; z < c.l; z++) c.biomes[z * c.w + x] = world.getBiomeColor(b[0] + x, b[2] + z);
        }
        return c;
    }

    /** Повернуть на 90° по часовой (вид сверху). */
    public Clipboard rotateCW() {
        Clipboard r = new Clipboard(l, h, w);
        copyMeta(r);
        for (int y = 0; y < h; y++) for (int z = 0; z < l; z++) for (int x = 0; x < w; x++) {
            int v = get(x, y, z);
            int nx = l - 1 - z, nz = x;
            int nv = v == NONE || v < 0 ? v : Rotation.rotateCW(v);
            r.set(nx, y, nz, nv);
            CompoundTag t = tiles.get(idx(x, y, z));
            if (t != null) r.tiles.put(r.idx(nx, y, nz), transformTile(t, nx, y, nz, Rotation.Op.CW, x, z));
        }
        if (biomes != null) { r.biomes = new int[r.w * r.l]; for (int x = 0; x < w; x++) for (int z = 0; z < l; z++) r.biomes[x * r.w + (l - 1 - z)] = biomes[z * w + x]; }
        return r;
    }

    public Clipboard mirror(Rotation.Op op) {
        Clipboard r = new Clipboard(w, h, l);
        copyMeta(r);
        for (int y = 0; y < h; y++) for (int z = 0; z < l; z++) for (int x = 0; x < w; x++) {
            int nx = op == Rotation.Op.MIRROR_X ? w - 1 - x : x;
            int ny = op == Rotation.Op.MIRROR_Y ? h - 1 - y : y;
            int nz = op == Rotation.Op.MIRROR_Z ? l - 1 - z : z;
            int v = get(x, y, z);
            r.set(nx, ny, nz, v == NONE || v < 0 ? v : Rotation.apply(v, op));
            CompoundTag t = tiles.get(idx(x, y, z));
            if (t != null) r.tiles.put(r.idx(nx, ny, nz), transformTile(t, nx, ny, nz, op, x, z));
        }
        if (biomes != null) {
            r.biomes = new int[w * l];
            for (int x = 0; x < w; x++) for (int z = 0; z < l; z++) {
                int nx = op == Rotation.Op.MIRROR_X ? w - 1 - x : x, nz = op == Rotation.Op.MIRROR_Z ? l - 1 - z : z;
                r.biomes[nz * w + nx] = biomes[z * w + x];
            }
        }
        return r;
    }

    private void copyMeta(Clipboard r) { r.stubPalette.addAll(stubPalette); r.name = name; r.time = time; r.pinned = pinned; }

    private static CompoundTag transformTile(CompoundTag t, int nx, int ny, int nz, Rotation.Op op, int ox, int oz) {
        CompoundTag c = t.copy();
        c.setNumberKeepType("x", nx, Tag.INT);
        c.setNumberKeepType("y", ny, Tag.INT);
        c.setNumberKeepType("z", nz, Tag.INT);
        if (c.has("pairx") && c.has("pairz")) {
            int dx = c.getInt("pairx", 0) - ox, dz = c.getInt("pairz", 0) - oz;
            int ndx = dx, ndz = dz;
            switch (op) {
                case CW -> { ndx = -dz; ndz = dx; }
                case MIRROR_X -> ndx = -dx;
                case MIRROR_Z -> ndz = -dz;
                default -> {}
            }
            c.setNumberKeepType("pairx", nx + ndx, Tag.INT);
            c.setNumberKeepType("pairz", nz + ndz, Tag.INT);
        }
        if ("Skull".equals(c.getString("id", "")) && c.has("Rot"))
            c.setNumberKeepType("Rot", Rotation.rotateSkullRot(c.getInt("Rot", 0), op), Tag.BYTE);
        return c;
    }

    /**
     * Вставить в мир так, что клетка (0,0,0) буфера встаёт в (ox, oy, oz).
     * @param skipAir не переносить воздух
     * @param onlyIntoAir не перезаписывать существующие блоки
     */
    public void paste(PeWorld world, int ox, int oy, int oz, boolean skipAir, boolean onlyIntoAir, Mask mask, boolean pasteBiomes, boolean pasteTiles, ChangeSet cs, Task t) {
        Map<Integer, Integer> stubMap = new HashMap<>();
        for (int i = 0; i < stubPalette.size(); i++) stubMap.put(-(i + 1), -(1 + world.stubIndex(stubPalette.get(i))));
        long total = volume(), done = 0;
        for (int x = 0; x < w; x++) {
            if (t != null) { t.check(); t.progress(done / (double) total); }
            for (int z = 0; z < l; z++) for (int y = 0; y < h; y++) {
                done++;
                int v = get(x, y, z);
                if (v == NONE) continue;
                if (skipAir && v == 0) continue;
                int wx = ox + x, wy = oy + y, wz = oz + z;
                if (!PeWorld.inHeight(wy)) continue;
                int cur = world.getV(wx, wy, wz);
                if (onlyIntoAir && cur != 0) continue;
                if (mask != null && !mask.isAny() && !mask.test(world, wx, wy, wz, cur)) continue;
                if (v < 0) v = stubMap.getOrDefault(v, v);
                world.set(wx, wy, wz, v, cs);
                CompoundTag tile = tiles.get(idx(x, y, z));
                if (tile != null && pasteTiles && v >= 0) world.setTile(wx, wy, wz, TileUtil.moved(tile, wx, wy, wz), cs);
            }
        }
        if (pasteBiomes && biomes != null)
            for (int x = 0; x < w; x++) for (int z = 0; z < l; z++) world.setBiomeColor(ox + x, oz + z, biomes[z * w + x], cs);
    }

    /** Мини-интерфейс прогресса для операций. */
    public interface Task { void check(); void progress(double p); }
}
