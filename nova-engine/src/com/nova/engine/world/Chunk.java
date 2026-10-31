package com.nova.engine.world;

/** 16 x 128 x 16 column, 8 render sections of 16 blocks height. idx = (y << 8) | (z << 4) | x */
public final class Chunk {
    public static final int H = 128, SECTIONS = 8;
    public final int cx, cz;
    public final byte[] blocks = new byte[16 * 16 * H];
    public final byte[] data = new byte[16 * 16 * H];
    /** biome per column, z * 16 + x */
    public final byte[] biome = new byte[256];
    public volatile boolean generated;
    /** 1 = terrain done (in World.chunks), 2 = decorated */
    public volatile int stage;
    /** per section: bumped on every change; the renderer rebuilds when it differs from the built version */
    public final int[] version = new int[SECTIONS];

    public Chunk(int cx, int cz) { this.cx = cx; this.cz = cz; }

    public static int idx(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    public int get(int x, int y, int z) {
        if (y < 0 || y >= H) return 0;
        return blocks[idx(x, y, z)] & 255;
    }

    public void set(int x, int y, int z, int id) {
        if (y < 0 || y >= H) return;
        blocks[idx(x, y, z)] = (byte) id;
        data[idx(x, y, z)] = 0;
    }

    public void set(int x, int y, int z, int id, int d) {
        if (y < 0 || y >= H) return;
        blocks[idx(x, y, z)] = (byte) id;
        data[idx(x, y, z)] = (byte) d;
    }

    public int data(int x, int y, int z) { return y < 0 || y >= H ? 0 : data[idx(x, y, z)] & 15; }
}
