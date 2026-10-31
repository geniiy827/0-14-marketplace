package com.nova.engine.world;

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

    private void touch(int cx, int cz, int s) {
        Chunk c = chunk(cx, cz);
        if (c != null) c.version[s]++;
    }
}
