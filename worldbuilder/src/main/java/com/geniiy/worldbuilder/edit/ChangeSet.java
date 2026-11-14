package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.nbt.Tag.CompoundTag;
import com.geniiy.worldbuilder.util.LongIntMap;

import java.util.*;

/**
 * Один шаг отмены: «было/стало» для блоков, тайлов, биомов и заглушек.
 * Позиции хранятся упакованными (см. Pos), состояния — (id<<4|meta), заглушки — отрицательные числа −(1+индекс).
 */
public final class ChangeSet {
    public final String name;
    public final String icon;
    public final long time = System.currentTimeMillis();
    private final LongIntMap index = new LongIntMap(256);
    private long[] pos = new long[256];
    private int[] before = new int[256];
    private int[] after = new int[256];
    private int n;
    public final HashMap<Long, CompoundTag> tilesBefore = new HashMap<>();
    public final HashMap<Long, CompoundTag> tilesAfter = new HashMap<>();
    public final HashSet<Long> tileTouched = new HashSet<>();
    public final LinkedHashMap<Long, int[]> biomes = new LinkedHashMap<>(); // colKey → {before, after}
    /** Произвольные действия отмены (например, изменение level.dat). */
    public final List<Runnable[]> extra = new ArrayList<>();
    public boolean selectionChanged;

    public ChangeSet(String name, String icon) { this.name = name; this.icon = icon; }

    public void recordBlock(long p, int b, int a) {
        int i = index.get(p, -1);
        if (i >= 0) { after[i] = a; return; }
        if (n == pos.length) {
            int c = n * 2;
            pos = Arrays.copyOf(pos, c); before = Arrays.copyOf(before, c); after = Arrays.copyOf(after, c);
        }
        pos[n] = p; before[n] = b; after[n] = a;
        index.put(p, n);
        n++;
    }

    public void recordTile(long p, CompoundTag b, CompoundTag a) {
        if (tileTouched.add(p)) tilesBefore.put(p, b == null ? null : b.copy());
        tilesAfter.put(p, a == null ? null : a.copy());
    }

    public void recordBiome(long col, int b, int a) {
        int[] e = biomes.get(col);
        if (e == null) biomes.put(col, new int[]{b, a}); else e[1] = a;
    }

    public int size() { return n; }
    public long pos(int i) { return pos[i]; }
    public int before(int i) { return before[i]; }
    public int after(int i) { return after[i]; }
    /** Число реально изменившихся блоков. */
    public int changedCount() { int c = 0; for (int i = 0; i < n; i++) if (before[i] != after[i]) c++; return c; }
    public boolean isEmpty() { return n == 0 && tileTouched.isEmpty() && biomes.isEmpty() && extra.isEmpty() && !selectionChanged; }
    public long memoryBytes() { return n * 16L + index.memoryBytes() + tileTouched.size() * 200L + biomes.size() * 48L; }
}
