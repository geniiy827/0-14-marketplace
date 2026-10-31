package com.nova.engine.world;

/**
 * Chunk decoration of MCPE 0.14.3 (RandomLevelSource::postProcess -> Biome::decorate -> BiomeDecorator::decorate).
 * Ported exactly: the per-chunk Random seed, the order and counts of BiomeDecorator::decorate, decorateOres /
 * decorateDepthSpan / decorateDepthAverage, OreFeature, the oak TreeFeature (_prepareSpawn, leaves), tree count,
 * tree / height / flower positions. The other features follow the logic of the same era and are being replaced
 * one by one with their disassembled versions (marked "approx").
 * Runs on one thread; the 3x3 chunks around the decorated one have terrain (World.raw access).
 */
public final class Decorator {
    static final int AIR = 0, STONE = 1, GRASS = 2, DIRT = 3, SAND = 12, GRAVEL = 13, WATER = 9, FLOWING_WATER = 8,
            LOG = 17, LEAVES = 18, CLAY = 82, TALLGRASS = 31, DEADBUSH = 32, YELLOW_FLOWER = 37, RED_FLOWER = 38,
            BROWN_MUSHROOM = 39, RED_MUSHROOM = 40, CACTUS = 81, REEDS = 83, PUMPKIN = 86, WATERLILY = 111, PODZOL = 243,
            MYCELIUM = 110, SNOW_LAYER = 78, ICE = 79;

    // decorator values per biome (BiomeDecorator +0xcc.. as set by the biome constructors)
    static final float[] TREES = new float[256];
    static final int[] FLOWERS = new int[256], GRASSN = new int[256], DEADBUSH_N = new int[256], LILY = new int[256],
            MUSHROOMS = new int[256], REEDS_N = new int[256], CACTUS_N = new int[256], GRAVEL_N = new int[256],
            SAND_N = new int[256], CLAY_N = new int[256], BIGMUSH = new int[256];
    static {
        for (int i = 0; i < 256; i++) { SAND_N[i] = 3; GRASSN[i] = 1; GRAVEL_N[i] = 1; CLAY_N[i] = 1; FLOWERS[i] = 2; }
        v(Biomes.PLAINS, -999, 4, 10); v(Biomes.MUTATED_PLAINS, -999, 4, 10);
        for (int b : new int[]{2, 17, 130}) { v(b, -999, 2, 1); DEADBUSH_N[b] = 2; REEDS_N[b] = 50; CACTUS_N[b] = 10; }
        v(3, 0, 2, 1); v(131, 0, 2, 1); v(20, 3, 2, 1); v(34, 3, 2, 1); v(162, 3, 2, 1);
        for (int b : new int[]{4, 18, 27, 28, 29, 155, 156, 157}) v(b, 10, 2, 2);
        v(132, 6, 100, 1);
        for (int b : new int[]{5, 19, 30, 31, 133, 158}) { v(b, 10, 2, 1); MUSHROOMS[b] = 1; }
        for (int b : new int[]{32, 33, 160, 161}) { v(b, 10, 2, 7); DEADBUSH_N[b] = 1; MUSHROOMS[b] = 3; }
        for (int b : new int[]{6, 134}) { v(b, 2, 1, 5); MUSHROOMS[b] = 8; REEDS_N[b] = 10; LILY[b] = 4; SAND_N[b] = 0; GRAVEL_N[b] = 0; DEADBUSH_N[b] = 1; CLAY_N[b] = 1; }
        for (int b : new int[]{21, 22, 149}) v(b, 50, 4, 25);
        for (int b : new int[]{23, 151}) v(b, 2, 4, 25);
        for (int b : new int[]{35, 36}) v(b, 1, 4, 20);
        for (int b : new int[]{163, 164}) v(b, 2, 2, 5);
        for (int b : new int[]{37, 38, 39, 165, 166, 167}) { v(b, -999, 0, 1); DEADBUSH_N[b] = 20; REEDS_N[b] = 3; CACTUS_N[b] = 5; }
        TREES[38] = 5; TREES[166] = 5;
        for (int b : new int[]{14, 15}) { v(b, -100, -100, -100); MUSHROOMS[b] = 1; BIGMUSH[b] = 1; }
        for (int b : new int[]{16, 25, 26}) { v(b, -999, 2, 1); }
    }
    private static void v(int b, float trees, int flowers, int grass) { TREES[b] = trees; FLOWERS[b] = flowers; GRASSN[b] = grass; }

    private final World w;
    private final int seed;
    private McRandom r;
    private int ox, oz;

    public Decorator(World w, long seed) { this.w = w; this.seed = (int) seed; }

    // ------------------------------------------------------------------ world access (raw: chunks with terrain)
    int get(int x, int y, int z) { return w.rawGet(x, y, z); }
    void set(int x, int y, int z, int id, int data) { w.rawSet(x, y, z, id, data); }
    boolean air(int x, int y, int z) { return get(x, y, z) == AIR; }

    /** BlockSource::getHeightmap: above the highest light-blocking block */
    int heightmap(int x, int z) {
        for (int y = Chunk.H - 1; y >= 0; y--) {
            int id = get(x, y, z);
            if (id != AIR && Blocks.kind[id] != Blocks.CROSS && id != SNOW_LAYER) return y + 1;
        }
        return 0;
    }

    /** BlockSource::getTopSolidBlock: above the highest block that blocks movement (not leaves) */
    int topSolid(int x, int z) {
        for (int y = Chunk.H - 1; y >= 0; y--) {
            int id = get(x, y, z);
            if (id != AIR && id != LEAVES && Blocks.solid[id] && Blocks.kind[id] != Blocks.LIQUID) return y + 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ postProcess
    public void decorate(int cx, int cz) {
        McRandom s = new McRandom(seed);
        int a = (int) ((s.nextU32() >>> 2) << 1) + 1;
        int b = (int) ((s.nextU32() >>> 2) << 1) + 1;
        r = new McRandom(seed ^ (cx * a + cz * b));
        ox = cx << 4; oz = cz << 4;
        int biome = w.rawBiome(ox + 15, oz + 15);
        decorateBiome(biome);
    }

    // ------------------------------------------------------------------ BiomeDecorator::decorate
    private void decorateBiome(int bi) {
        ores();
        for (int i = 0; i < SAND_N[bi]; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); disk(x, topSolid(x, z), z, SAND, 7, 2); }
        for (int i = 0; i < CLAY_N[bi]; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); clay(x, topSolid(x, z), z); }
        for (int i = 0; i < GRAVEL_N[bi]; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); disk(x, topSolid(x, z), z, GRAVEL, 6, 2); }
        int grassCount = GRASSN[bi];
        float t = TREES[bi];
        int trees = 0;
        if (t > 0) {
            if (r.nextU32() % 10 == 0) {
                trees = (int) (r.nextFloat() * t);
                grassCount = GRASSN[Biomes.PLAINS];
            } else if (t < 1f) trees = t > r.nextFloat() ? 1 : 0;
            else {
                trees = (int) t;
                int e = (int) (r.nextU32() % 10) - 7;
                if (e >= 0) trees += e;
            }
        }
        for (int i = 0; i < trees; i++) {
            int kind = treeFeature(bi);
            if (kind < 0) continue;
            int x = ox + 1 + (int) (r.nextU32() % 14), z = oz + 1 + (int) (r.nextU32() % 14);
            int y = heightmap(x, z);
            tree(kind, x, y, z);
            tallGrass(x, y, z, 1, 23, 3);
        }
        for (int i = 0; i < BIGMUSH[bi]; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); topSolid(x, z); }   // approx: huge mushrooms not yet
        for (int i = 0; i < FLOWERS[bi]; i++) {
            int x = ox + r.nextInt(16), z = oz + r.nextInt(16);
            int h = heightmap(x, z) + 32;
            int y = h == 0 ? 0 : r.nextInt(h);
            int[] fl = flowerType(bi, x, y, z);
            if (!air(x, y, z)) flowers(x, y, z, fl[0], fl[1]);
        }
        for (int i = 0; i < grassCount; i++) {
            int x = r.nextInt(16), z = r.nextInt(16);
            int y = randomHeight(x, z);
            int[] g = grassType(bi);
            tallGrass(ox + x, y, oz + z, g[1], 128, 8);
        }
        for (int i = 0; i < DEADBUSH_N[bi]; i++) { int x = r.nextInt(16), z = r.nextInt(16); deadBush(ox + x, randomHeight(x, z), oz + z); }
        for (int i = 0; i < LILY[bi]; i++) {
            int x = r.nextInt(16), z = r.nextInt(16);
            int y = randomHeight(x, z);
            while (y > 0 && air(ox + x, y - 1, oz + z)) y--;
            waterlily(ox + x, y, oz + z);
        }
        for (int i = 0; i < MUSHROOMS[bi]; i++) {
            if ((r.nextU32() & 3) == 0) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); bush(x, heightmap(x, z), z, BROWN_MUSHROOM); }
            if ((r.nextU32() & 7) == 0) { int x = r.nextInt(16), z = r.nextInt(16); bush(ox + x, randomHeight(x, z), oz + z, RED_MUSHROOM); }
        }
        if ((r.nextU32() & 3) == 0) { int x = r.nextInt(16), z = r.nextInt(16); bush(ox + x, randomHeight(x, z), oz + z, BROWN_MUSHROOM); }
        if ((r.nextU32() & 7) == 0) { int x = r.nextInt(16), z = r.nextInt(16); bush(ox + x, randomHeight(x, z), oz + z, RED_MUSHROOM); }
        for (int i = 0; i < REEDS_N[bi]; i++) { int x = r.nextInt(16), z = r.nextInt(16); reeds(ox + x, randomHeight(x, z), oz + z); }
        for (int i = 0; i < 10; i++) { int x = r.nextInt(16), z = r.nextInt(16); reeds(ox + x, randomHeight(x, z), oz + z); }
        if ((r.nextU32() & 31) == 0) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); pumpkin(x, heightmap(x, z), z); }   // approx position
        for (int i = 0; i < CACTUS_N[bi]; i++) { int x = r.nextInt(16), z = r.nextInt(16); cactus(ox + x, randomHeight(x, z), oz + z); }
        // springs (+0xf8): positions drawn like the original, not placed yet (no flowing water)
        for (int i = 0; i < 50; i++) { r.nextU32(); long a = r.nextU32(), b = r.nextU32(); r.nextU32(); }
        for (int i = 0; i < 20; i++) { r.nextU32(); r.nextU32(); r.nextU32(); r.nextU32(); r.nextU32(); }
    }

    /** _getRandomHeight: r % (heightmap * 2), 0 when the column is empty */
    private int randomHeight(int x, int z) {
        int h = heightmap(ox + x, oz + z) * 2;
        return h == 0 ? 0 : (int) (r.nextU32() % h);
    }

    // ------------------------------------------------------------------ ores (decorateOres, OreFeature)
    private void ores() {
        span(10, DIRT, 0, 33, 0, 128);
        span(8, GRAVEL, 0, 33, 0, 128);
        if ((r.nextU32() & 15) == 0) span(80, GRAVEL, 0, 33, 0, 50);
        span(10, STONE, 3, 33, 0, 80);
        span(10, STONE, 1, 33, 0, 80);
        span(10, STONE, 5, 33, 0, 80);
        span(20, 16, 0, 17, 0, 128);
        span(20, 15, 0, 9, 0, 64);
        span(2, 14, 0, 9, 0, 32);
        span(8, 73, 0, 8, 0, 16);
        span(1, 56, 0, 8, 0, 16);
        for (int i = 0; i < 1; i++) {                       // decorateDepthAverage(1, lapis, 16, 16)
            int x = (int) (r.nextU32() & 15);
            int y = r.nextInt(16) + r.nextInt(16) + 16 - 16;
            int z = (int) (r.nextU32() & 15);
            ore(ox + x, y, oz + z, 21, 0, 7);
        }
    }

    private void span(int count, int id, int data, int size, int min, int max) {
        for (int i = 0; i < count; i++) {
            int x = (int) (r.nextU32() & 15);
            int y = max - min != 0 ? min + (int) (r.nextU32() % (max - min)) : min;
            int z = (int) (r.nextU32() & 15);
            ore(ox + x, y, oz + z, id, data, size);
        }
    }

    // Math::mSin
    static final float[] SIN = new float[65536];
    static final float PI = (float) Math.PI, SIN_SCALE = (float) (65536.0 / (Math.PI * 2.0));
    static { for (int i = 0; i < 65536; i++) SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0); }
    static float sinIdx(float scaled) { return SIN[(int) scaled & 0xffff]; }

    /** OreFeature::place */
    private void ore(int px, int py, int pz, int id, int data, int count) {
        float f = r.nextFloat();
        float ang = (SIN_SCALE * PI) * f;
        float c8 = count / 8f;
        float sn = c8 * sinIdx(ang), cs = c8 * sinIdx(ang + 16384f);
        float fx = px, fy = py, fz = pz;
        float xs = (fx + 8f) + sn, zs = (fz + 8f) + cs;
        float ys = (fy + -2f) + (float) (int) (r.nextU32() % 3);
        float ye = (fy + -2f) + (float) (int) (r.nextU32() % 3);
        float xe = (fx + 8f) - sn, ze = (fz + 8f) - cs;
        float dx = xe - xs, dy = ye - ys, dz = ze - zs;
        for (int i = 0; i < count; i++) {
            float tt = (float) i / (float) count;
            float x = xs + tt * dx, y = ys + tt * dy, z = zs + tt * dz;
            float rf = r.nextFloat();
            float si = sinIdx(((float) i * PI) / (float) count * SIN_SCALE);
            float s15 = ((float) count * 0.0625f) * rf;
            s15 = s15 + si * s15;
            s15 = s15 + 1f;
            float rad = s15 * 0.5f, inv = 2f / s15;
            int x0 = (int) Math.floor(x - rad), y0 = (int) Math.floor(y - rad), z0 = (int) Math.floor(z - rad);
            int x1 = (int) Math.floor(x + rad), y1 = (int) Math.floor(y + rad), z1 = (int) Math.floor(z + rad);
            float hx = 0.5f - x, hy = 0.5f - y;
            for (int bx = x0; bx <= x1; bx++) {
                float ax = ((float) bx + hx) * inv; ax = ax * ax;
                if (!(ax < 1f)) continue;
                for (int by = y0; by <= y1; by++) {
                    float ay = ((float) by + hy) * inv; float sxy = ax + ay * ay;
                    if (!(sxy < 1f)) continue;
                    for (int bz = z0; bz <= z1; bz++) {
                        float az = inv * (((float) bz + 0.5f) - z);
                        if (sxy + az * az < 1f && get(bx, by, bz) == STONE) set(bx, by, bz, id, data);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ trees
    static final int T_OAK = 0, T_FANCY = 1, T_BIRCH = 2, T_SPRUCE = 3, T_PINE = 4, T_JUNGLE = 5, T_SHRUB = 6,
            T_SWAMP = 7, T_ACACIA = 8, T_MEGA_SPRUCE = 9, T_TALL_BIRCH = 10;

    /** Biome::getTreeFeature (the random choices of each biome class) */
    private int treeFeature(int b) {
        int c = Biomes.CLASS[b];
        switch (c) {
            case Biomes.C_FOREST:
                if (b == 27 || b == 28) return T_BIRCH;
                if (r.nextInt(5) == 0) return T_BIRCH;
                return r.nextInt(10) == 0 ? T_FANCY : T_OAK;
            case Biomes.C_FOREST_MUT: return T_TALL_BIRCH;
            case Biomes.C_TAIGA:
                if (Biomes.TYPE[b] != 0 && r.nextInt(3) == 0) return T_MEGA_SPRUCE;
                return r.nextInt(3) == 0 ? T_PINE : T_SPRUCE;
            case Biomes.C_SNOW: return T_SPRUCE;
            case Biomes.C_JUNGLE:
                if (r.nextInt(10) == 0) return T_FANCY;
                if (r.nextInt(2) == 0) return T_SHRUB;
                return T_JUNGLE;
            case Biomes.C_SWAMP: return T_SWAMP;
            case Biomes.C_SAVANNA: case Biomes.C_SAVANNA_MUT: return r.nextInt(5) > 0 ? T_ACACIA : T_OAK;
            case Biomes.C_HILLS: if (r.nextInt(3) > 0) return T_SPRUCE; return r.nextInt(10) == 0 ? T_FANCY : T_OAK;
            case Biomes.C_MESA: return T_OAK;
            default: return r.nextInt(10) == 0 ? T_FANCY : T_OAK;
        }
    }

    private void tree(int kind, int x, int y, int z) {
        switch (kind) {
            case T_OAK: oak(x, y, z, (int) (r.nextU32() % 3) + 5, 0); break;
            case T_FANCY: oak(x, y, z, (int) (r.nextU32() % 3) + 5, 0); break;            // approx (big oak)
            case T_BIRCH: oak(x, y, z, r.nextInt(3) + 5, 2); break;                         // approx
            case T_TALL_BIRCH: oak(x, y, z, r.nextInt(3) + 5 + r.nextInt(7), 2); break;      // approx
            case T_JUNGLE: oak(x, y, z, r.nextInt(7) + 4, 3); break;                       // approx
            case T_SWAMP: case T_ACACIA: oak(x, y, z, r.nextInt(3) + 5, 0); break;          // approx
            case T_SHRUB: shrub(x, y, z); break;
            case T_SPRUCE: case T_MEGA_SPRUCE: spruce(x, y, z); break;
            case T_PINE: pine(x, y, z); break;
        }
    }

    private boolean soil(int id) { return id == GRASS || id == DIRT || id == PODZOL || id == 60; }
    private boolean free(int id) { return id == AIR || id == LEAVES || id == 161 || id == GRASS || id == DIRT || id == LOG || id == 162 || id == 6 || id == 106; }

    /** TreeFeature::_prepareSpawn: room for the tree, soil below (turned to dirt) */
    private boolean prepare(int x, int y, int z, int h) {
        if (y <= 0 || y + h > 127) return false;
        if (!soil(get(x, y - 1, z))) return false;
        for (int yy = y; yy <= y + 1 + h; yy++) {
            int rr = yy == y ? 0 : 1;
            if (yy >= y + h - 1) rr = 2;
            for (int xx = x - rr; xx <= x + rr; xx++) for (int zz = z - rr; zz <= z + rr; zz++) {
                if (yy < 0 || yy >= 128) return false;
                if (!free(get(xx, yy, zz))) return false;
            }
        }
        set(x, y - 1, z, DIRT, 0);
        return true;
    }

    private void leaf(int x, int y, int z, int data) {
        int id = get(x, y, z);
        if (id == AIR || id == LEAVES || id == 106) set(x, y, z, LEAVES, data);
    }

    /** TreeFeature::place(…, height): trunk, then the 4 leaf layers (corners: random bit, never on the top layer) */
    private void oak(int x, int y, int z, int h, int wood) {
        if (!prepare(x, y, z, h)) return;
        for (int i = 0; i < h; i++) {
            int id = get(x, y + i, z);
            if (id == AIR || id == LEAVES || id == 106) set(x, y + i, z, LOG, wood);
        }
        for (int yy = y - 3 + h; yy <= y + h; yy++) {
            int dy = yy - (y + h), rad = 1 - dy / 2;
            for (int xx = x - rad; xx <= x + rad; xx++) {
                int ax = Math.abs(xx - x);
                for (int zz = z - rad; zz <= z + rad; zz++) {
                    int az = Math.abs(zz - z);
                    if (ax == rad && az == rad) {
                        if ((r.nextU32() & 1) == 0 || dy == 0) continue;
                    }
                    leaf(xx, yy, zz, wood);
                }
            }
        }
    }

    /** approx (SpruceFeature) */
    private void spruce(int x, int y, int z) {
        int h = r.nextInt(4) + 6, top = 1 + r.nextInt(2), lh = h - top, maxR = 2 + r.nextInt(2);
        if (!prepare(x, y, z, h)) return;
        int rad = r.nextInt(2), lim = 1, start = 0;
        for (int i = 0; i <= lh; i++) {
            int yy = y + h - i;
            for (int xx = x - rad; xx <= x + rad; xx++) for (int zz = z - rad; zz <= z + rad; zz++)
                if (Math.abs(xx - x) != rad || Math.abs(zz - z) != rad || rad <= 0) leaf(xx, yy, zz, 1);
            if (rad >= lim) { rad = start; start = 1; lim++; if (lim > maxR) lim = maxR; } else rad++;
        }
        int th = h - r.nextInt(3);
        for (int i = 0; i < th; i++) { int id = get(x, y + i, z); if (id == AIR || id == LEAVES) set(x, y + i, z, LOG, 1); }
    }

    /** approx (PineFeature) */
    private void pine(int x, int y, int z) {
        int h = r.nextInt(5) + 7, bare = h - r.nextInt(2) - 3, lh = h - bare, maxR = 1 + r.nextInt(lh + 1);
        if (!prepare(x, y, z, h)) return;
        int rad = 0;
        for (int yy = y + h; yy >= y + bare; yy--) {
            for (int xx = x - rad; xx <= x + rad; xx++) for (int zz = z - rad; zz <= z + rad; zz++)
                if (Math.abs(xx - x) != rad || Math.abs(zz - z) != rad || rad <= 0) leaf(xx, yy, zz, 1);
            if (rad >= 1 && yy == y + bare + 1) rad--; else if (rad < maxR) rad++;
        }
        for (int i = 0; i < h - 1; i++) { int id = get(x, y + i, z); if (id == AIR || id == LEAVES) set(x, y + i, z, LOG, 1); }
    }

    /** approx (GroundBushFeature) */
    private void shrub(int x, int y, int z) {
        if (!soil(get(x, y - 1, z))) return;
        set(x, y, z, LOG, 3);
        for (int yy = y; yy <= y + 2; yy++) {
            int rad = 2 - (yy - y);
            for (int xx = x - rad; xx <= x + rad; xx++) for (int zz = z - rad; zz <= z + rad; zz++)
                if (Math.abs(xx - x) != rad || Math.abs(zz - z) != rad || r.nextInt(2) != 0) leaf(xx, yy, zz, 0);
        }
    }

    // ------------------------------------------------------------------ plants (approx)
    private int[] flowerType(int b, int x, int y, int z) {
        if (Biomes.CLASS[b] == Biomes.C_SWAMP) return new int[]{RED_FLOWER, 1};
        if (r.nextInt(3) > 0) return new int[]{RED_FLOWER, 0};
        return new int[]{YELLOW_FLOWER, 0};
    }

    private int[] grassType(int b) {
        int c = Biomes.CLASS[b];
        if (c == Biomes.C_TAIGA || c == Biomes.C_JUNGLE) return new int[]{TALLGRASS, r.nextInt(4) == 0 ? 1 : 2};
        return new int[]{TALLGRASS, 1};
    }

    private boolean grassSoil(int id) { return id == GRASS || id == DIRT || id == PODZOL || id == 60; }

    private void flowers(int x, int y, int z, int id, int data) {
        for (int i = 0; i < 64; i++) {
            int xx = x + r.nextInt(8) - r.nextInt(8), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(8) - r.nextInt(8);
            if (yy > 0 && yy < 127 && air(xx, yy, zz) && grassSoil(get(xx, yy - 1, zz))) set(xx, yy, zz, id, data);
        }
    }

    private void tallGrass(int x, int y, int z, int data, int tries, int spread) {
        int id;
        while (y > 0 && ((id = get(x, y, z)) == AIR || id == LEAVES)) y--;
        for (int i = 0; i < tries; i++) {
            int xx = x + r.nextInt(spread) - r.nextInt(spread), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(spread) - r.nextInt(spread);
            if (yy > 0 && yy < 127 && air(xx, yy, zz) && grassSoil(get(xx, yy - 1, zz))) set(xx, yy, zz, TALLGRASS, data);
        }
    }

    private void deadBush(int x, int y, int z) {
        int id;
        while (y > 0 && ((id = get(x, y, z)) == AIR || id == LEAVES)) y--;
        for (int i = 0; i < 4; i++) {
            int xx = x + r.nextInt(8) - r.nextInt(8), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(8) - r.nextInt(8);
            int below = get(xx, yy - 1, zz);
            if (yy > 0 && air(xx, yy, zz) && (below == SAND || below == 159 || below == 172 || below == DIRT)) set(xx, yy, zz, DEADBUSH, 0);
        }
    }

    private void bush(int x, int y, int z, int id) {
        for (int i = 0; i < 64; i++) {
            int xx = x + r.nextInt(8) - r.nextInt(8), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(8) - r.nextInt(8);
            int below = get(xx, yy - 1, zz);
            if (yy > 0 && yy < 127 && air(xx, yy, zz) && (below == MYCELIUM || below == PODZOL || (Blocks.opaque[below] && yy < 60))) set(xx, yy, zz, id, 0);
        }
    }

    private void waterlily(int x, int y, int z) {
        for (int i = 0; i < 10; i++) {
            int xx = x + r.nextInt(8) - r.nextInt(8), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(8) - r.nextInt(8);
            if (yy > 0 && air(xx, yy, zz) && get(xx, yy - 1, zz) == WATER) set(xx, yy, zz, WATERLILY, 0);
        }
    }

    private void reeds(int x, int y, int z) {
        for (int i = 0; i < 20; i++) {
            int xx = x + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(4) - r.nextInt(4);
            if (!air(xx, y, zz)) continue;
            int below = get(xx, y - 1, zz);
            boolean water = get(xx - 1, y - 1, zz) == WATER || get(xx + 1, y - 1, zz) == WATER || get(xx, y - 1, zz - 1) == WATER || get(xx, y - 1, zz + 1) == WATER;
            if (!water) continue;
            int h = 2 + r.nextInt(r.nextInt(3) + 1);
            if (below != GRASS && below != DIRT && below != SAND) continue;
            for (int k = 0; k < h && air(xx, y + k, zz); k++) set(xx, y + k, zz, REEDS, 0);
        }
    }

    private void pumpkin(int x, int y, int z) {
        for (int i = 0; i < 64; i++) {
            int xx = x + r.nextInt(8) - r.nextInt(8), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(8) - r.nextInt(8);
            int f = r.nextInt(4);
            if (yy > 0 && air(xx, yy, zz) && get(xx, yy - 1, zz) == GRASS && Blocks.exists[PUMPKIN]) set(xx, yy, zz, PUMPKIN, f);
        }
    }

    private void cactus(int x, int y, int z) {
        for (int i = 0; i < 10; i++) {
            int xx = x + r.nextInt(8) - r.nextInt(8), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(8) - r.nextInt(8);
            if (!air(xx, yy, zz)) continue;
            int h = 1 + r.nextInt(r.nextInt(3) + 1);
            for (int k = 0; k < h; k++) {
                int yk = yy + k;
                int below = get(xx, yk - 1, zz);
                if ((below == SAND || below == CACTUS) && air(xx + 1, yk, zz) && air(xx - 1, yk, zz) && air(xx, yk, zz + 1) && air(xx, yk, zz - 1))
                    set(xx, yk, zz, CACTUS, 0);
            }
        }
    }

    // ------------------------------------------------------------------ disks (approx SandFeature / ClayFeature)
    private void disk(int x, int y, int z, int id, int radius, int half) {
        int w0 = get(x, y, z);
        if (w0 != WATER && w0 != FLOWING_WATER) return;
        int rr = r.nextInt(radius - 2) + 2;
        for (int xx = x - rr; xx <= x + rr; xx++) for (int zz = z - rr; zz <= z + rr; zz++) {
            int dx = xx - x, dz = zz - z;
            if (dx * dx + dz * dz > rr * rr) continue;
            for (int yy = y - half; yy <= y + half; yy++) {
                int b = get(xx, yy, zz);
                if (b == DIRT || b == GRASS) set(xx, yy, zz, id, 0);
            }
        }
    }

    private void clay(int x, int y, int z) {
        int w0 = get(x, y, z);
        if (w0 != WATER && w0 != FLOWING_WATER) return;
        int rr = r.nextInt(2) + 2;
        for (int xx = x - rr; xx <= x + rr; xx++) for (int zz = z - rr; zz <= z + rr; zz++) {
            int dx = xx - x, dz = zz - z;
            if (dx * dx + dz * dz > rr * rr) continue;
            for (int yy = y - 1; yy <= y + 1; yy++) {
                int b = get(xx, yy, zz);
                if (b == DIRT || b == CLAY) set(xx, yy, zz, CLAY, 0);
            }
        }
    }
}
