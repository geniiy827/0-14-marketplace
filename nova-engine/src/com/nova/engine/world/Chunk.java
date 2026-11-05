package com.nova.engine.world;

/** 16 x 128 x 16 column, 8 render sections of 16 blocks height. idx = (y << 8) | (z << 4) | x */
public final class Chunk {
    public static final int H = 128, SECTIONS = 8;
    public final int cx, cz;
    public final byte[] blocks = new byte[16 * 16 * H];
    public final byte[] data = new byte[16 * 16 * H];
    /** biome per column, z * 16 + x */
    public final byte[] biome = new byte[256];
    /** LevelChunk grass colour per column (0xRRGGBB), z * 16 + x: FoliageColor::buildGrassColor */
    public final int[] grass = new int[256];
    public volatile boolean generated;
    /** 1 = terrain done (in World.chunks), 2 = decorated */
    public volatile int stage;
    /** per section: bumped on every change; the renderer rebuilds when it differs from the built version */
    public final int[] version = new int[SECTIONS];

    /** LightLayer brightness per block, idx order like blocks: SKY << 4 | BLOCK */
    public final byte[] light = new byte[16 * 16 * H];

    public int light(int layer, int i) { return layer == 0 ? (light[i] >> 4) & 15 : light[i] & 15; }

    public void light(int layer, int i, int v) {
        light[i] = (byte) (layer == 0 ? (light[i] & 15) | v << 4 : (light[i] & 0xF0) | v);
    }
    /** LevelChunk heightmap: 1 + the top block with mLightBlock != 0, z * 16 + x (0..128) */
    public final byte[] height = new byte[256];
    /** 0 = no light yet, 1 = filled (heightmap, sources), 2 = spread into / from the neighbours */
    public volatile int lit;

    public Chunk(int cx, int cz) { this.cx = cx; this.cz = cz; }

    /** a light value changed: the sections that sample it get rebuilt (batched by Light, bumped once at the end) */
    void touchLight(int x, int y, int z, World w) {
        java.util.HashSet<Long> t = Light.TOUCHED.get();
        int s = y >> 4;
        t.add(Light.skey(cx, cz, s));
        if ((y & 15) == 0 && s > 0) t.add(Light.skey(cx, cz, s - 1));
        if ((y & 15) == 15 && s < SECTIONS - 1) t.add(Light.skey(cx, cz, s + 1));
        if (x == 0) t.add(Light.skey(cx - 1, cz, s));
        if (x == 15) t.add(Light.skey(cx + 1, cz, s));
        if (z == 0) t.add(Light.skey(cx, cz - 1, s));
        if (z == 15) t.add(Light.skey(cx, cz + 1, s));
    }

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
