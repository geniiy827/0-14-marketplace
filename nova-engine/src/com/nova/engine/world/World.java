package com.nova.engine.world;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** The loaded world: chunk columns by position. Generation runs on worker threads (see game/Game). */
public final class World {
    public final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<Long, Chunk>();
    public final Generator gen;
    public long seed;

    public World(long seed) {
        this.seed = seed;
        this.gen = new Generator(seed);
    }

    public static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }

    public Chunk chunk(int cx, int cz) { return chunks.get(key(cx, cz)); }

    /** generated chunk or null */
    public Chunk ready(int cx, int cz) {
        Chunk c = chunks.get(key(cx, cz));
        return c != null && c.generated ? c : null;
    }

    public int get(int x, int y, int z) {
        if (y < 0 || y >= Chunk.H) return 0;
        Chunk c = ready(x >> 4, z >> 4);
        return c == null ? 0 : c.get(x & 15, y, z & 15);
    }

    /** true = unknown (not generated yet): treated as solid for physics */
    public boolean loaded(int x, int z) { return ready(x >> 4, z >> 4) != null; }

    public void set(int x, int y, int z, int id, int data) {
        set(x, y, z, id);
        Chunk c = ready(x >> 4, z >> 4);
        if (c != null && y >= 0 && y < Chunk.H) c.data[Chunk.idx(x & 15, y, z & 15)] = (byte) data;
    }

    public void set(int x, int y, int z, int id) {
        if (y < 0 || y >= Chunk.H) return;
        Chunk c = ready(x >> 4, z >> 4);
        if (c == null) return;
        int lx = x & 15, lz = z & 15;
        c.set(lx, y, lz, id);
        int s = y >> 4;
        touch(c.cx, c.cz, s);
        if ((y & 15) == 0 && s > 0) touch(c.cx, c.cz, s - 1);
        if ((y & 15) == 15 && s < Chunk.SECTIONS - 1) touch(c.cx, c.cz, s + 1);
        if (lx == 0) touch(c.cx - 1, c.cz, s);
        if (lx == 15) touch(c.cx + 1, c.cz, s);
        if (lz == 0) touch(c.cx, c.cz - 1, s);
        if (lz == 15) touch(c.cx, c.cz + 1, s);
    }

    // ------------------------------------------------------------------ generation writes across chunk borders
    /** blocks for chunks that are not generated yet: x y z id data (world coordinates) */
    private final java.util.HashMap<Long, List<int[]>> pending = new java.util.HashMap<Long, List<int[]>>();
    private final ThreadLocal<Chunk> current = new ThreadLocal<Chunk>();

    /** generator: the chunk being generated on this thread (its own writes go straight in) */
    public void begin(Chunk c) { current.set(c); }

    public void place(int x, int y, int z, int id, int data) { put(x, y, z, id, data, false); }
    public void placeIfAir(int x, int y, int z, int id, int data) { put(x, y, z, id, data, true); }

    private void put(int x, int y, int z, int id, int data, boolean ifAir) {
        if (y < 0 || y >= Chunk.H) return;
        int cx = x >> 4, cz = z >> 4;
        Chunk own = current.get();
        if (own != null && own.cx == cx && own.cz == cz) {
            if (!ifAir || own.get(x & 15, y, z & 15) == 0) own.set(x & 15, y, z & 15, id, data);
            return;
        }
        synchronized (pending) {
            Chunk c = ready(cx, cz);
            if (c != null) {
                if (!ifAir || c.get(x & 15, y, z & 15) == 0) { c.set(x & 15, y, z & 15, id, data); touchAround(c, x & 15, y, z & 15); }
                return;
            }
            Long k = key(cx, cz);
            List<int[]> l = pending.get(k);
            if (l == null) { l = new ArrayList<int[]>(); pending.put(k, l); }
            l.add(new int[]{x & 15, y, z & 15, id, data, ifAir ? 1 : 0});
        }
    }

    /** after generation: apply what neighbours left for this chunk, then publish it */
    public void finish(Chunk c) {
        current.remove();
        synchronized (pending) {
            List<int[]> l = pending.remove(key(c.cx, c.cz));
            if (l != null) for (int[] b : l) if (b[5] == 0 || c.get(b[0], b[1], b[2]) == 0) c.set(b[0], b[1], b[2], b[3], b[4]);
            chunks.put(key(c.cx, c.cz), c);
        }
    }

    private void touchAround(Chunk c, int lx, int y, int lz) {
        int s = y >> 4;
        c.version[s]++;
        if (lx == 0) touch(c.cx - 1, c.cz, s);
        if (lx == 15) touch(c.cx + 1, c.cz, s);
        if (lz == 0) touch(c.cx, c.cz - 1, s);
        if (lz == 15) touch(c.cx, c.cz + 1, s);
        if ((y & 15) == 15 && s < Chunk.SECTIONS - 1) c.version[s + 1]++;
        if ((y & 15) == 0 && s > 0) c.version[s - 1]++;
    }

    // ------------------------------------------------------------------ decoration access (any chunk with terrain)
    public int rawGet(int x, int y, int z) {
        if (y < 0 || y >= Chunk.H) return 0;
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        return c == null ? 0 : c.get(x & 15, y, z & 15);
    }

    public void rawSet(int x, int y, int z, int id, int data) {
        if (y < 0 || y >= Chunk.H) return;
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        if (c == null) return;
        c.set(x & 15, y, z & 15, id, data);
        if (c.generated) touchAround(c, x & 15, y, z & 15);
    }

    public int rawBiome(int x, int z) {
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        return c == null ? 0 : c.biome[(z & 15) * 16 + (x & 15)] & 255;
    }

    /** terrain finished on a worker thread: visible to decoration, not yet to the renderer */
    public void addTerrain(Chunk c) { c.stage = 1; chunks.put(key(c.cx, c.cz), c); }

    public int data(int x, int y, int z) {
        Chunk c = ready(x >> 4, z >> 4);
        return c == null ? 0 : c.data(x & 15, y, z & 15);
    }

    public int biome(int x, int z) {
        Chunk c = ready(x >> 4, z >> 4);
        return c == null ? 0 : c.biome[(z & 15) * 16 + (x & 15)] & 255;
    }

    private void touch(int cx, int cz, int s) {
        Chunk c = chunk(cx, cz);
        if (c != null) c.version[s]++;
    }
}
