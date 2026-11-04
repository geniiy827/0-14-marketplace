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

    private final Village village;
    private final Scattered scattered;

    public Decorator(World w, long seed) { this.w = w; this.seed = (int) seed; this.village = new Village(w.gen, (int) seed); this.scattered = new Scattered(w.gen, (int) seed); }

    /** world access for the structures (raw: decoration stage) */
    private final Village.Blocks4 structureAccess = new Village.Blocks4() {
        public int get(int x, int y, int z) { return Decorator.this.get(x, y, z); }
        public int data(int x, int y, int z) { return w.data(x, y, z); }
        public void set(int x, int y, int z, int id, int data) { Decorator.this.set(x, y, z, id, data); }
        public int topSolid(int x, int z, boolean liquids) {
            for (int y = Chunk.H - 1; y >= 0; y--) {
                int id = Decorator.this.get(x, y, z);
                if (id == AIR || id == LEAVES || id == LEAVES2) continue;
                if (Blocks.kind[id] == Blocks.LIQUID) { if (liquids) return y + 1; continue; }
                if (Blocks.solid[id]) return y + 1;
            }
            return 0;
        }
    };

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
        int chunkSeed = seed ^ (cx * a + cz * b);
        r = new McRandom(chunkSeed);
        ox = cx << 4; oz = cz << 4;
        int biome = w.rawBiome(ox + 15, oz + 15);
        lakesAndDungeons(biome, chunkSeed);
        freeze();
        r = new McRandom(chunkSeed);                                   // reseeded for Biome::decorate
        flowerN = FLOWERS[biome]; grassN = GRASSN[biome];
        preDecorate(biome);
        decorateBiome(biome);
        postDecorate(biome);
        snow();                                                         // Seasons::postProcess
    }

    // ------------------------------------------------------------------ BiomeDecorator::decorate
    private int flowerN, grassN;

    private void decorateBiome(int bi) {
        ores();
        for (int i = 0; i < SAND_N[bi]; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); disk(x, topSolid(x, z), z, SAND, 7, 2); }
        for (int i = 0; i < CLAY_N[bi]; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); clay(x, topSolid(x, z), z); }
        for (int i = 0; i < GRAVEL_N[bi]; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); disk(x, topSolid(x, z), z, GRAVEL, 6, 2); }
        int grassCount = grassN;
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
        }
        for (int i = 0; i < BIGMUSH[bi]; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); hugeMushroom(x, heightmap(x, z), z, -1); }
        for (int i = 0; i < flowerN; i++) {
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
            tallGrass(ox + x, y, oz + z, g[1], 90, 8);     // TallGrassFeature::place(): 90 tries, spread 8
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
            T_SWAMP = 7, T_ACACIA = 8, T_MEGA_SPRUCE = 9, T_TALL_BIRCH = 10,
            T_MEGA_PINE = 11, T_MEGA_JUNGLE = 12, T_CANOPY = 13;

    /** Biome::getTreeFeature (the random choices of each biome class) */
    private int treeFeature(int b) {
        int c = Biomes.CLASS[b];
        switch (c) {
            case Biomes.C_FOREST:
                if ((b == 29 || b == 157) && r.nextInt(3) > 0) return T_CANOPY;
                if (b == 27 || b == 28) return T_BIRCH;
                if (r.nextInt(5) == 0) return T_BIRCH;
                return r.nextInt(10) == 0 ? T_FANCY : T_OAK;
            case Biomes.C_FOREST_MUT: return T_TALL_BIRCH;
            case Biomes.C_TAIGA:
                if ((b == 32 || b == 33 || b == 160 || b == 161) && r.nextInt(3) == 0)
                    return (b == 160 || b == 161 || r.nextInt(13) == 0) ? T_MEGA_SPRUCE : T_MEGA_PINE;
                return r.nextInt(3) == 0 ? T_PINE : T_SPRUCE;
            case Biomes.C_SNOW: return T_SPRUCE;
            case Biomes.C_JUNGLE:
                if (r.nextInt(10) == 0) return T_FANCY;
                if (r.nextInt(2) == 0) return T_SHRUB;
                if (b != 23 && b != 151 && r.nextInt(3) == 0) return T_MEGA_JUNGLE;
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
            case T_FANCY: new BigTree().place(x, y, z); break;
            case T_BIRCH: oak(x, y, z, r.nextInt(3) + 5, 2); break;
            case T_TALL_BIRCH: { int h = r.nextInt(3) + 5; h += r.nextInt(7); oak(x, y, z, h, 2); break; }
            case T_JUNGLE: { int base = 4 + r.nextInt(7); oak(x, y, z, r.nextInt(3) + base, 3); break; }
            case T_SWAMP: swamp(x, y, z); break;
            case T_ACACIA: acacia(x, y, z); break;
            case T_SHRUB: shrub(x, y, z); break;
            case T_SPRUCE: spruce(x, y, z); break;
            case T_PINE: pine(x, y, z); break;
            case T_MEGA_SPRUCE: megaPine(x, y, z, false); break;
            case T_MEGA_PINE: megaPine(x, y, z, true); break;
            case T_MEGA_JUNGLE: megaJungle(x, y, z); break;
            case T_CANOPY: canopy(x, y, z); break;
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

    /** SpruceFeature (TaigaTree2) */
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

    /** PineFeature (TaigaTree1) */
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

    /** GroundBushFeature */
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
        if (b == 1 || b == 129) {                                   // PlainsBiome: tulips where the info noise < -0.8
            if (infoNoise(x, z) < -0.8f) return new int[]{RED_FLOWER, new int[]{5, 4, 7, 6}[r.nextInt(4)]};
            if (r.nextInt(3) > 0) return new int[]{RED_FLOWER, new int[]{0, 3, 8}[r.nextInt(3)]};
            return new int[]{YELLOW_FLOWER, 0};
        }
        if (b == 132) {                                             // flower forest
            int k = r.nextInt(9); return k == 0 ? new int[]{YELLOW_FLOWER, 0} : new int[]{RED_FLOWER, k == 1 ? 0 : k};
        }
        return r.nextInt(3) > 0 ? new int[]{YELLOW_FLOWER, 0} : new int[]{RED_FLOWER, 0};
    }

    /** getGrassFeature: taiga 4/5 fern, jungle 1/4 fern, else grass (tallgrass data 1 grass, 2 fern) */
    private int[] grassType(int b) {
        int c = Biomes.CLASS[b];
        if (c == Biomes.C_TAIGA) return new int[]{TALLGRASS, r.nextInt(5) > 0 ? 2 : 1};
        if (c == Biomes.C_JUNGLE) return new int[]{TALLGRASS, r.nextInt(4) == 0 ? 2 : 1};
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

    // ================================================================== biome-specific decoration
    static final int LOG2 = 162, LEAVES2 = 161, DOUBLE_PLANT = 175, MOSSY = 48, SANDSTONE = 24, SLAB = 44, MELON = 103,
            EMERALD = 129, HUGE_BROWN = 99, HUGE_RED = 100;
    private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};   // north, east, south, west
    private Noises.Simplex info;

    /** Biome::BIOME_INFO_NOISE (PerlinSimplexNoise(Random(2345), 1)).getValue */
    private float infoNoise(int x, int z) {
        if (info == null) info = new Noises.Simplex(new McRandom(2345));
        return info.value((x + 8) / 200f, (z + 8) / 200f);
    }

    private int randHeight32(int x, int z) { int h = heightmap(x, z) + 32; return (int) (r.nextU32() % h); }

    /** what the <Biome>::decorate overrides do before BiomeDecorator::decorate */
    private void preDecorate(int b) {
        if (b == 1 || b == 129) {                                         // PlainsBiome
            if (infoNoise(ox, oz) < -0.8f) { flowerN = 15; grassN = 5; }
            else {
                flowerN = 4; grassN = 10;
                for (int i = 0; i < 7; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); doublePlant(x, randHeight32(x, z), z, 2); }
            }
            if (b == 129) for (int i = 0; i < 10; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); doublePlant(x, randHeight32(x, z), z, 0); }
        } else if (Biomes.CLASS[b] == Biomes.C_FOREST || Biomes.CLASS[b] == Biomes.C_FOREST_MUT) {   // ForestBiome
            if (b == 29 || b == 157) {
                for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
                    int x = ox + i * 4 + 1 + r.nextInt(3), z = oz + j * 4 + 1 + r.nextInt(3);
                    int y = heightmap(x, z);
                    if (r.nextInt(20) == 0) hugeMushroom(x, y, z, -1);
                    else tree(treeFeature(b), x, y, z);
                }
            }
            int n = r.nextInt(5) - 3;
            if (b == 132) n += 2;
            for (int i = 0; i < n; i++) {
                int k = r.nextInt(3), type = k == 0 ? 1 : k == 1 ? 4 : 5;
                for (int t = 0; t < 5; t++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); if (doublePlant(x, randHeight32(x, z), z, type)) break; }
            }
        } else if (Biomes.CLASS[b] == Biomes.C_TAIGA) {                  // TaigaBiome
            if (b == 32 || b == 33 || b == 160 || b == 161) {
                int n = r.nextInt(3);
                for (int i = 0; i < n; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); boulder(x, heightmap(x, z), z); }
            }
            for (int i = 0; i < 7; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); doublePlant(x, randHeight32(x, z), z, 3); }
        } else if (b == 35 || b == 36) {                                  // SavannaBiome (not mutated)
            for (int i = 0; i < 7; i++) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); doublePlant(x, randHeight32(x, z), z, 2); }
        }
    }

    /** what the <Biome>::decorate overrides do after BiomeDecorator::decorate */
    private void postDecorate(int b) {
        if (b == 2 || b == 17 || b == 130) {                              // DesertBiome: well
            if (r.nextInt(1000) == 0) { int x = ox + r.nextInt(16), z = oz + r.nextInt(16); well(x, heightmap(x, z) + 1, z); }
        } else if (Biomes.CLASS[b] == Biomes.C_JUNGLE) {                 // JungleBiome: melons
            int x = ox + r.nextInt(16), z = oz + r.nextInt(16);
            int h = heightmap(x, z) * 2, y = h == 0 ? 0 : r.nextInt(h);
            for (int i = 0; i < 64; i++) {
                int xx = x + r.nextInt(8) - r.nextInt(8), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(8) - r.nextInt(8);
                if (yy > 0 && yy < 127 && air(xx, yy, zz) && get(xx, yy - 1, zz) == GRASS) set(xx, yy, zz, MELON, 0);
            }
        } else if (b == 3 || b == 34 || b == 131 || b == 162 || b == 20) { // ExtremeHillsBiome: emerald
            int n = 3 + r.nextInt(6);
            for (int i = 0; i < n; i++) {
                int x = ox + r.nextInt(16), y = r.nextInt(28) + 4, z = oz + r.nextInt(16);
                if (get(x, y, z) == STONE) set(x, y, z, EMERALD, 0);
            }
        }
    }

    // ================================================================== plants
    /** DoublePlantFeature::place: 64 tries, the lower half gets the type, the upper half 8 | type */
    private boolean doublePlant(int x, int y, int z, int type) {
        boolean placed = false;
        for (int i = 0; i < 64; i++) {
            int xx = x + r.nextInt(8) - r.nextInt(8), yy = y + r.nextInt(4) - r.nextInt(4), zz = z + r.nextInt(8) - r.nextInt(8);
            if (yy > 0 && yy < 126 && air(xx, yy, zz) && air(xx, yy + 1, zz) && grassSoil(get(xx, yy - 1, zz))) {
                set(xx, yy, zz, DOUBLE_PLANT, type); set(xx, yy + 1, zz, DOUBLE_PLANT, 8 | type); placed = true;
            }
        }
        return placed;
    }

    /** BlockBlob (mossy boulders of mega taiga), radius 0 */
    private void boulder(int x, int y, int z) {
        while (y > 3) {
            int b = get(x, y - 1, z);
            if (b != AIR && (b == GRASS || b == DIRT || b == STONE)) break;
            y--;
        }
        if (y <= 3) return;
        int rad = 0;
        for (int i = 0; i < 3; i++) {
            int j = rad + r.nextInt(2), k = rad + r.nextInt(2), l = rad + r.nextInt(2);
            float f = (j + k + l) * 0.333f + 0.5f;
            for (int xx = x - j; xx <= x + j; xx++) for (int yy = y - k; yy <= y + k; yy++) for (int zz = z - l; zz <= z + l; zz++) {
                int dx = xx - x, dy = yy - y, dz = zz - z;
                if (dx * dx + dy * dy + dz * dz <= f * f) set(xx, yy, zz, MOSSY, 0);
            }
            x += -(rad + 1) + r.nextInt(2 + rad * 2);
            y -= r.nextInt(2);
            z += -(rad + 1) + r.nextInt(2 + rad * 2);
        }
    }

    /** DesertWell */
    private void well(int x, int y, int z) {
        while (y > 2 && air(x, y, z)) y--;
        if (get(x, y, z) != SAND) return;
        for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) if (air(x + i, y - 1, z + j) && air(x + i, y - 2, z + j)) return;
        for (int k = -1; k <= 0; k++) for (int l = -2; l <= 2; l++) for (int m = -2; m <= 2; m++) set(x + l, y + k, z + m, SANDSTONE, 0);
        set(x, y, z, WATER, 0); set(x - 1, y, z, WATER, 0); set(x + 1, y, z, WATER, 0); set(x, y, z - 1, WATER, 0); set(x, y, z + 1, WATER, 0);
        for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) if (i == -2 || i == 2 || j == -2 || j == 2) set(x + i, y + 1, z + j, SANDSTONE, 0);
        set(x + 2, y + 1, z, SLAB, 1); set(x - 2, y + 1, z, SLAB, 1); set(x, y + 1, z + 2, SLAB, 1); set(x, y + 1, z - 2, SLAB, 1);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) set(x + i, y + 4, z + j, i == 0 && j == 0 ? SANDSTONE : SLAB, i == 0 && j == 0 ? 0 : 1);
        for (int k = 1; k <= 3; k++) { set(x - 1, y + k, z - 1, SANDSTONE, 0); set(x - 1, y + k, z + 1, SANDSTONE, 0); set(x + 1, y + k, z - 1, SANDSTONE, 0); set(x + 1, y + k, z + 1, SANDSTONE, 0); }
    }

    /** HugeMushroomFeature: type -1 random, 0 brown, 1 red */
    private void hugeMushroom(int x, int y, int z, int type) {
        int block = type < 0 ? (r.nextInt(2) == 0 ? HUGE_BROWN : HUGE_RED) : (type == 0 ? HUGE_BROWN : HUGE_RED);
        int h = r.nextInt(3) + 4;
        if (r.nextInt(12) == 0) h *= 2;
        if (y < 1 || y + h + 1 >= 128) return;
        int below = get(x, y - 1, z);
        if (below != DIRT && below != GRASS && below != MYCELIUM) return;
        boolean brown = block == HUGE_BROWN;
        int top = y + h, start = brown ? top : top - 3;
        for (int yy = start; yy <= top; yy++) {
            int rad = 1;
            if (yy < top) rad++;
            if (brown) rad = 3;
            int x0 = x - rad, x1 = x + rad, z0 = z - rad, z1 = z + rad;
            for (int xx = x0; xx <= x1; xx++) for (int zz = z0; zz <= z1; zz++) {
                int v = 5;
                if (xx == x0) v--; else if (xx == x1) v++;
                if (zz == z0) v -= 3; else if (zz == z1) v += 3;
                if (brown || yy < top) {
                    if ((xx == x0 || xx == x1) && (zz == z0 || zz == z1)) continue;
                    if (xx == x - (rad - 1) && zz == z0) v = 1;
                    if (xx == x0 && zz == z - (rad - 1)) v = 1;
                    if (xx == x + (rad - 1) && zz == z0) v = 3;
                    if (xx == x1 && zz == z - (rad - 1)) v = 3;
                    if (xx == x - (rad - 1) && zz == z1) v = 7;
                    if (xx == x0 && zz == z + (rad - 1)) v = 7;
                    if (xx == x + (rad - 1) && zz == z1) v = 9;
                    if (xx == x1 && zz == z + (rad - 1)) v = 9;
                }
                if (v == 5 && yy < top) v = 0;
                if ((v != 0 || y >= top - 1) && !Blocks.opaque[get(xx, yy, zz)]) set(xx, yy, zz, block, v);
            }
        }
        for (int i = 0; i < h; i++) if (!Blocks.opaque[get(x, y + i, z)]) set(x, y + i, z, block, 10);
    }

    // ================================================================== trees
    private boolean replaceable(int id) { return id == AIR || id == LEAVES || id == LEAVES2 || id == GRASS || id == DIRT || id == LOG || id == LOG2 || id == 6 || id == 106; }
    private void log(int x, int y, int z, int id, int data) { int b = get(x, y, z); if (b == AIR || b == LEAVES || b == LEAVES2 || b == 106) set(x, y, z, id, data); }
    private void leafAny(int x, int y, int z, int id, int data) { int b = get(x, y, z); if (b == AIR || b == 106) set(x, y, z, id, data); }

    private boolean roomFor(int x, int y, int z, int h, int r0, int r1, int rTop) {
        if (y < 1 || y + h + 1 > 127) return false;
        for (int yy = y; yy <= y + 1 + h; yy++) {
            int rr = yy == y ? r0 : r1;
            if (yy >= y + 1 + h - 2) rr = rTop;
            for (int xx = x - rr; xx <= x + rr; xx++) for (int zz = z - rr; zz <= z + rr; zz++) if (!free(get(xx, yy, zz))) return false;
        }
        return true;
    }

    /** SwampTreeFeature */
    private void swamp(int x, int y, int z) {
        int h = r.nextInt(4) + 5;
        while (y > 1 && (get(x, y - 1, z) == WATER || get(x, y - 1, z) == FLOWING_WATER)) y--;
        int below = get(x, y - 1, z);
        if (below != GRASS && below != DIRT) return;
        if (y < 1 || y + h + 1 > 127) return;
        set(x, y - 1, z, DIRT, 0);
        for (int yy = y - 3 + h; yy <= y + h; yy++) {
            int dy = yy - (y + h), rad = 2 - dy / 2;
            for (int xx = x - rad; xx <= x + rad; xx++) for (int zz = z - rad; zz <= z + rad; zz++) {
                if (Math.abs(xx - x) == rad && Math.abs(zz - z) == rad && (r.nextInt(2) == 0 || dy == 0)) continue;
                if (!Blocks.opaque[get(xx, yy, zz)]) leafAny(xx, yy, zz, LEAVES, 0);
            }
        }
        for (int i = 0; i < h; i++) {
            int b = get(x, y + i, z);
            if (b == AIR || b == LEAVES || b == WATER || b == FLOWING_WATER) set(x, y + i, z, LOG, 0);
        }
    }

    /** SavannaTreeFeature (acacia) */
    private void acacia(int x, int y, int z) {
        int h = r.nextInt(3) + r.nextInt(3) + 5;
        if (!roomFor(x, y, z, h, 0, 1, 2)) return;
        int below = get(x, y - 1, z);
        if ((below != GRASS && below != DIRT) || y >= 127 - h - 1) return;
        set(x, y - 1, z, DIRT, 0);
        int[] f = DIRS[r.nextInt(4)];
        int bend = h - r.nextInt(4) - 1, len = 3 - r.nextInt(3);
        int tx = x, tz = z, top = 0;
        for (int i = 0; i < h; i++) {
            int yy = y + i;
            if (i >= bend && len > 0) { tx += f[0]; tz += f[1]; len--; }
            int b = get(tx, yy, tz);
            if (b == AIR || b == LEAVES || b == LEAVES2) { set(tx, yy, tz, LOG2, 0); top = yy; }
        }
        for (int i = -3; i <= 3; i++) for (int j = -3; j <= 3; j++) if (Math.abs(i) != 3 || Math.abs(j) != 3) leafAny(tx + i, top, tz + j, LEAVES2, 0);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) leafAny(tx + i, top + 1, tz + j, LEAVES2, 0);
        leafAny(tx + 2, top + 1, tz, LEAVES2, 0); leafAny(tx - 2, top + 1, tz, LEAVES2, 0);
        leafAny(tx, top + 1, tz + 2, LEAVES2, 0); leafAny(tx, top + 1, tz - 2, LEAVES2, 0);
        tx = x; tz = z;
        int[] f2 = DIRS[r.nextInt(4)];
        if (f2 != f) {
            int start = bend - r.nextInt(2) - 1, n = 1 + r.nextInt(3);
            top = 0;
            for (int i = start; i < h && n > 0; i++, n--) {
                if (i < 1) continue;
                int yy = y + i;
                tx += f2[0]; tz += f2[1];
                int b = get(tx, yy, tz);
                if (b == AIR || b == LEAVES || b == LEAVES2) { set(tx, yy, tz, LOG2, 0); top = yy; }
            }
            if (top > 0) {
                for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) if (Math.abs(i) != 2 || Math.abs(j) != 2) leafAny(tx + i, top, tz + j, LEAVES2, 0);
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) leafAny(tx + i, top + 1, tz + j, LEAVES2, 0);
            }
        }
    }

    /** RoofTreeFeature (dark oak) */
    private void canopy(int x, int y, int z) {
        int h = r.nextInt(3) + r.nextInt(2) + 6;
        if (y < 1 || y + h + 1 >= 127) return;
        int below = get(x, y - 1, z);
        if (below != GRASS && below != DIRT) return;
        for (int i = -1; i <= h + 1; i++) {
            int rr = i == -1 || i >= h - 1 ? 2 : 1;
            for (int a = -rr; a <= rr + 1; a++) for (int c = -rr; c <= rr + 1; c++) if (i >= 0 && !free(get(x + a, y + i, z + c))) return;
        }
        set(x, y - 1, z, DIRT, 0); set(x + 1, y - 1, z, DIRT, 0); set(x, y - 1, z + 1, DIRT, 0); set(x + 1, y - 1, z + 1, DIRT, 0);
        int[] f = DIRS[r.nextInt(4)];
        int bend = h - r.nextInt(4), len = 2 - r.nextInt(3);
        int tx = x, tz = z, ty = y + h - 1;
        for (int i = 0; i < h; i++) {
            if (i >= bend && len > 0) { tx += f[0]; tz += f[1]; len--; }
            int yy = y + i;
            int b = get(tx, yy, tz);
            if (b == AIR || b == LEAVES || b == LEAVES2) {
                log(tx, yy, tz, LOG2, 1); log(tx + 1, yy, tz, LOG2, 1); log(tx, yy, tz + 1, LOG2, 1); log(tx + 1, yy, tz + 1, LOG2, 1);
            }
        }
        for (int i = -2; i <= 0; i++) for (int j = -2; j <= 0; j++) {
            int k = -1;
            leafAny(tx + i, ty + k, tz + j, LEAVES2, 1); leafAny(1 + tx - i, ty + k, tz + j, LEAVES2, 1);
            leafAny(tx + i, ty + k, 1 + tz - j, LEAVES2, 1); leafAny(1 + tx - i, ty + k, 1 + tz - j, LEAVES2, 1);
            if ((i > -2 || j > -1) && (i != -1 || j != -2)) {
                k = 1;
                leafAny(tx + i, ty + k, tz + j, LEAVES2, 1); leafAny(1 + tx - i, ty + k, tz + j, LEAVES2, 1);
                leafAny(tx + i, ty + k, 1 + tz - j, LEAVES2, 1); leafAny(1 + tx - i, ty + k, 1 + tz - j, LEAVES2, 1);
            }
        }
        if (r.nextInt(2) == 0) {
            leafAny(tx, ty + 2, tz, LEAVES2, 1); leafAny(tx + 1, ty + 2, tz, LEAVES2, 1);
            leafAny(tx + 1, ty + 2, tz + 1, LEAVES2, 1); leafAny(tx, ty + 2, tz + 1, LEAVES2, 1);
        }
        for (int i = -3; i <= 4; i++) for (int j = -3; j <= 4; j++)
            if ((i != -3 || j != -3) && (i != -3 || j != 4) && (i != 4 || j != -3) && (i != 4 || j != 4) && (Math.abs(i) < 3 || Math.abs(j) < 3))
                leafAny(tx + i, ty, tz + j, LEAVES2, 1);
        for (int i = -1; i <= 2; i++) for (int j = -1; j <= 2; j++) {
            if ((i < 0 || i > 1 || j < 0 || j > 1) && r.nextInt(3) <= 0) {
                int n = r.nextInt(3) + 2;
                for (int k = 0; k < n; k++) log(x + i, ty - k - 1, z + j, LOG2, 1);
                for (int a = -1; a <= 1; a++) for (int c = -1; c <= 1; c++) leafAny(tx + i + a, ty, tz + j + c, LEAVES2, 1);
                for (int a = -2; a <= 2; a++) for (int c = -2; c <= 2; c++) if (Math.abs(a) != 2 || Math.abs(c) != 2) leafAny(tx + i + a, ty - 1, tz + j + c, LEAVES2, 1);
            }
        }
    }

    // ---- mega trees (MegaTreeFeature base: height = base + r%3 + r%extra, 2x2 trunk)
    private boolean megaRoom(int x, int y, int z, int h) {
        if (y < 1 || y + h + 1 > 127) return false;
        for (int i = y; i <= y + 1 + h; i++) {
            int rr = i == y ? 1 : 2;
            for (int a = -rr; a <= rr && a + x >= x - rr; a++) for (int c = -rr; c <= rr; c++)
                if (!free(get(x + a, i, z + c))) return false;
        }
        int b = get(x, y - 1, z);
        if (b != GRASS && b != DIRT && b != PODZOL) return false;
        set(x, y - 1, z, DIRT, 0); set(x + 1, y - 1, z, DIRT, 0); set(x, y - 1, z + 1, DIRT, 0); set(x + 1, y - 1, z + 1, DIRT, 0);
        return true;
    }

    private void leavesLayerStrict(int x, int y, int z, int rad, int id, int data) {
        int r2 = rad * rad;
        for (int j = -rad; j <= rad + 1; j++) for (int k = -rad; k <= rad + 1; k++) {
            int l = j - 1, m = k - 1;
            if (j * j + k * k <= r2 || l * l + m * m <= r2 || j * j + m * m <= r2 || l * l + k * k <= r2) leafAny(x + j, y, z + k, id, data);
        }
    }

    private void leavesLayer(int x, int y, int z, int rad, int id, int data) {
        for (int j = -rad; j <= rad; j++) for (int k = -rad; k <= rad; k++) if (j * j + k * k <= rad * rad) leafAny(x + j, y, z + k, id, data);
    }

    /** MegaPineTreeFeature: pine = true uses the base height for the crown (mega taiga), false = mega spruce */
    private void megaPine(int x, int y, int z, boolean pine) {
        int h = r.nextInt(3) + 13; h += r.nextInt(15);
        if (!megaRoom(x, y, z, h)) return;
        int top = y + h;
        int len = r.nextInt(5) + (pine ? 13 : 3), last = 0;
        for (int k = top - len; k <= top; k++) {
            int l = top - k;
            int rad = (int) Math.floor((float) l / (float) len * 3.5f);
            leavesLayerStrict(x, k, z, rad + (l > 0 && rad == last && (k & 1) == 0 ? 1 : 0), LEAVES, 1);
            last = rad;
        }
        for (int i = 0; i < h; i++) { log(x, y + i, z, LOG, 1); log(x + 1, y + i, z, LOG, 1); log(x, y + i, z + 1, LOG, 1); log(x + 1, y + i, z + 1, LOG, 1); }
        for (int a = -2; a <= 2; a++) for (int c = -2; c <= 2; c++)     // podzol around the trunk
            if ((Math.abs(a) != 2 || Math.abs(c) != 2) && r.nextInt(2) == 0) {
                for (int yy = y + 2; yy >= y - 3; yy--) {
                    int b = get(x + a, yy, z + c);
                    if (b == GRASS || b == DIRT) { set(x + a, yy, z + c, PODZOL, 0); break; }
                    if (b != AIR && yy < y) break;
                }
            }
    }

    /** MegaJungleTreeFeature (base 10, extra 20) */
    private void megaJungle(int x, int y, int z) {
        int h = r.nextInt(3) + 10; h += r.nextInt(20);
        if (!megaRoom(x, y, z, h)) return;
        for (int j = -2; j <= 0; j++) leavesLayerStrict(x, y + h + j, z, 2 + 1 - j, LEAVES, 3);
        for (int j = y + h - 2 - r.nextInt(4); j > y + h / 2; j -= 2 + r.nextInt(4)) {
            float f = r.nextFloat() * (float) Math.PI * 2f;
            int k = x + (int) (0.5f + (float) Math.cos(f) * 4f), l = z + (int) (0.5f + (float) Math.sin(f) * 4f);
            for (int i = 0; i < 5; i++) {
                k = x + (int) (1.5f + (float) Math.cos(f) * i); l = z + (int) (1.5f + (float) Math.sin(f) * i);
                set(k, j - 3 + i / 2, l, LOG, 3);
            }
            int n = 1 + r.nextInt(2);
            for (int k1 = j - n; k1 <= j; k1++) leavesLayer(k, k1, l, 1 - (k1 - j), LEAVES, 3);
        }
        for (int i = 0; i < h; i++) { log(x, y + i, z, LOG, 3); log(x + 1, y + i, z, LOG, 3); log(x, y + i, z + 1, LOG, 3); log(x + 1, y + i, z + 1, LOG, 3); }
    }

    /** FancyTreeFeature (big oak) */
    private final class BigTree {
        int bx, by, bz, heightLimit, height;
        final int leafDist = 4;
        McRandom rr;
        final java.util.ArrayList<int[]> nodes = new java.util.ArrayList<>();

        float layerSize(int y) {
            if (y < heightLimit * 0.3f) return -1f;
            float f = heightLimit / 2f, f1 = f - y;
            float f2 = (float) Math.sqrt(f * f - f1 * f1);
            if (f1 == 0f) f2 = f; else if (Math.abs(f1) >= f) return 0f;
            return f2 * 0.5f;
        }
        float leafSize(int y) { return y >= 0 && y < leafDist ? (y != 0 && y != leafDist - 1 ? 3f : 2f) : -1f; }

        void cross(int x, int y, int z, float rad) {
            int i = (int) (rad + 0.618f);
            for (int j = -i; j <= i; j++) for (int k = -i; k <= i; k++)
                if (Math.pow(Math.abs(j) + 0.5, 2) + Math.pow(Math.abs(k) + 0.5, 2) <= rad * rad) {
                    int b = get(x + j, y, z + k);
                    if (b == AIR || b == LEAVES) set(x + j, y, z + k, LEAVES, 0);
                }
        }

        /** -1 when the line is free, else the step that hit (place = false), or draws logs (place = true) */
        int line(int x0, int y0, int z0, int x1, int y1, int z1, boolean place) {
            int dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
            int n = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            float fx = n == 0 ? 0 : dx / (float) n, fy = n == 0 ? 0 : dy / (float) n, fz = n == 0 ? 0 : dz / (float) n;
            for (int j = 0; j <= n; j++) {
                int x = x0 + (int) Math.floor(0.5f + j * fx), y = y0 + (int) Math.floor(0.5f + j * fy), z = z0 + (int) Math.floor(0.5f + j * fz);
                if (place) set(x, y, z, LOG, 0);
                else if (!replaceable(get(x, y, z))) return j;
            }
            return -1;
        }

        void place(int x, int y, int z) {
            bx = x; by = y; bz = z;
            rr = new McRandom((int) r.nextU32());
            heightLimit = 5 + rr.nextInt(12);
            int below = get(x, y - 1, z);
            if (below != GRASS && below != DIRT) return;
            int j = line(x, y, z, x, y + heightLimit - 1, z, false);
            if (j != -1) { if (j < 6) return; heightLimit = j; }
            height = (int) (heightLimit * 0.618);
            if (height >= heightLimit) height = heightLimit - 1;
            int per = (int) (1.382 + Math.pow(heightLimit / 13.0, 2.0));
            if (per < 1) per = 1;
            int top = by + height;
            int k = heightLimit - leafDist;
            nodes.add(new int[]{bx, by + k, bz, top});
            for (; k >= 0; k--) {
                float f = layerSize(k);
                if (f < 0f) continue;
                for (int l = 0; l < per; l++) {
                    double d0 = f * (rr.nextFloat() + 0.328);
                    double d1 = rr.nextFloat() * 2f * Math.PI;
                    int px = (int) Math.floor(bx + d0 * Math.sin(d1) + 0.5), py = by + k - 1, pz = (int) Math.floor(bz + d0 * Math.cos(d1) + 0.5);
                    if (line(px, py, pz, px, py + leafDist, pz, false) == -1) {
                        int i1 = bx - px, j1 = bz - pz;
                        double d4 = py - Math.sqrt(i1 * i1 + j1 * j1) * 0.381;
                        int k1 = d4 > top ? top : (int) d4;
                        if (line(bx, k1, bz, px, py, pz, false) == -1) nodes.add(new int[]{px, py, pz, k1});
                    }
                }
            }
            for (int[] n : nodes) for (int i = 0; i < leafDist; i++) cross(n[0], n[1] + i, n[2], leafSize(i));
            line(bx, by, bz, bx, by + height, bz, true);
            for (int[] n : nodes) {
                if (n[0] == bx && n[1] == n[3] && n[2] == bz) continue;
                if (n[3] - by >= heightLimit * 0.2) line(bx, n[3], bz, n[0], n[1], n[2], true);
            }
            set(x, y - 1, z, DIRT, 0);
        }
    }

    // ================================================================== RandomLevelSource::postProcess (before decorate)
    static final int LAVA = 11, COBBLE = 4, CHEST = 54, SPAWNER = 52;

    /** lakes (water, lava) with the first state of the chunk random, then the dungeons with a fresh one */
    private void lakesAndDungeons(int biome, int chunkSeed) {
        if (biome != 2 && biome != 17) {                               // not desert / desert hills
            if ((r.nextU32() & 3) == 0) {
                long u1 = r.nextU32(), u2 = r.nextU32(), u3 = r.nextU32();
                lake(ox + (int) (u1 % 9) + 3, (int) (u2 & 127), oz + (int) (u3 % 9) + 3, WATER);
            }
        }
        if ((r.nextU32() & 7) == 0) {
            int x = ox + (int) (r.nextU32() % 9) + 3;
            long u2 = r.nextU32(), u3 = r.nextU32();
            int y = (int) (u3 % (u2 % 120 + 8));
            int z = oz + (int) (r.nextU32() % 9) + 3;
            if (y > 59 && (y <= 63 || r.nextU32() % 10 == 0)) lake(x, y, z, LAVA);
        }
        r = new McRandom(chunkSeed);                                   // reseeded before the structures
        try { village.postProcess(structureAccess, r, ox >> 4, oz >> 4); }        // +0xc4
        catch (Throwable t) { t.printStackTrace(); }
        try { scattered.postProcess(structureAccess, r, ox >> 4, oz >> 4); }     // +0x13c (after mineshafts/strongholds)
        catch (Throwable t) { t.printStackTrace(); }
        for (int i = 0; i < 8; i++) {
            long a = r.nextU32(), b = r.nextU32(), c = r.nextU32();
            monsterRoom(ox + (int) (a & 15) + 8, (int) (b & 127), oz + (int) (c & 15) + 8);
        }
    }

    private boolean solidM(int id) { return id != AIR && Blocks.solid[id] && Blocks.kind[id] != Blocks.LIQUID && Blocks.kind[id] != Blocks.CROSS; }
    private static boolean liquidM(int id) { return id == WATER || id == FLOWING_WATER || id == 10 || id == LAVA; }

    /** LakeFeature::place */
    private void lake(int px, int py, int pz, int liquid) {
        px -= 8; pz -= 8;
        while (py > 0 && air(px, py, pz)) py--;
        py -= 4;
        boolean[] g = new boolean[2048];
        int n = (int) (r.nextU32() & 3) + 4;
        for (int k = 0; k < n; k++) {
            float xs = 3f + r.nextFloat() * 6f;
            float ys = 2f + r.nextFloat() * 4f;
            float xh = xs * 0.5f;
            float cx = (xh - xs) + 15f;
            float zs = 3f + r.nextFloat() * 6f;
            float yh = ys * 0.5f;
            float cy = (yh - ys) + 6f;
            cx = cx * r.nextFloat();
            cy = cy * r.nextFloat();
            float f6 = r.nextFloat();
            float zh = zs * 0.5f;
            float cz = ((zh - zs) + 15f) * f6;
            float iy = 1f / yh, ix = 1f / xh, iz = 1f / zh;
            float[] dy = new float[7];
            for (int j = 1; j <= 6; j++) { float v = ((float) j - cy) * iy; dy[j] = v * v; }
            for (int x = 1; x < 15; x++) {
                float dx = ((float) x - cx) * ix; dx = dx * dx;
                for (int z = 1; z < 15; z++) {
                    float dz = ((float) z - cz) * iz; dz = dz * dz;
                    for (int j = 1; j <= 6; j++) if (dz + (dx + dy[j]) < 1f) g[(x * 16 + z) * 8 + j] = true;
                }
            }
        }
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 8; y++) {
            if (!check(g, x, y, z)) continue;
            int id = get(px + x, py + y, pz + z);
            if (y >= 4) { if (liquidM(id)) return; }
            else if (!solidM(id) && id != liquid) return;
        }
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 8; y++)
            if (g[(x * 16 + z) * 8 + y]) set(px + x, py + y, pz + z, y <= 3 ? liquid : AIR, 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 4; y < 8; y++) {
            if (!g[(x * 16 + z) * 8 + y]) continue;
            if (get(px + x, py + y - 1, pz + z) == DIRT && skyOpen(px + x, py + y, pz + z)) set(px + x, py + y - 1, pz + z, GRASS, 0);
        }
        if (liquid == LAVA) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 8; y++) {
                if (!check(g, x, y, z)) continue;
                if (y > 3 && (r.nextU32() & 1) == 0) continue;
                if (solidM(get(px + x, py + y, pz + z))) set(px + x, py + y, pz + z, STONE, 0);
            }
        }
    }

    /** sky light > 0 (no light engine at generation time: nothing opaque above) */
    private boolean skyOpen(int x, int y, int z) {
        for (int yy = y; yy < Chunk.H; yy++) { int id = get(x, yy, z); if (id != AIR && Blocks.opaque[id]) return false; }
        return true;
    }

    /** LakeFeature::_check: outside the lake with a lake neighbour */
    private static boolean check(boolean[] g, int x, int y, int z) {
        int i = (x * 16 + z) * 8 + y;
        if (g[i]) return false;
        return (x < 15 && g[i + 128]) || (x > 0 && g[i - 128]) || (z < 15 && g[i + 8]) || (z > 0 && g[i - 8])
                || (y < 7 && g[i + 1]) || (y > 0 && g[i - 1]);
    }

    /** MonsterRoomFeature::place (chest loot and the spawner mob are not generated yet) */
    private void monsterRoom(int px, int py, int pz) {
        int xr = (int) (r.nextU32() & 1) + 2, zr = (int) (r.nextU32() & 1) + 2;
        int x0 = px - xr - 1, x1 = px + xr + 1, z0 = pz - zr - 1, z1 = pz + zr + 1;
        int holes = 0;
        for (int x = x0; x <= x1; x++) for (int y = py - 1; y <= py + 4; y++) for (int z = z0; z <= z1; z++) {
            boolean solid = solidM(get(x, y, z));
            if (y == py - 1 && !solid) return;
            if (y == py + 4 && !solid) return;
            if ((x == x0 || x == x1 || z == z0 || z == z1) && y == py && air(x, y, z) && air(x, y + 1, z)) holes++;
        }
        if (holes < 1 || holes > 5) return;
        for (int x = x0; x <= x1; x++) for (int y = py + 3; y >= py - 1; y--) for (int z = z0; z <= z1; z++) {
            if (x != x0 && y != py - 1 && z != z0 && x != x1 && y != py + 4 && z != z1) { set(x, y, z, AIR, 0); continue; }
            if (y >= 0 && !solidM(get(x, y - 1, z))) { set(x, y, z, AIR, 0); continue; }
            if (solidM(get(x, y, z))) {
                if (y == py - 1) set(x, y, z, (r.nextU32() & 3) != 0 ? MOSSY : COBBLE, 0);
                else set(x, y, z, COBBLE, 0);
            }
        }
        for (int t = 0; t < 2; t++) {
            for (int k = 0; k < 3; k++) {
                int x = px + (int) (r.nextU32() % (2 * xr + 1)) - xr;
                int z = pz + (int) (r.nextU32() % (2 * zr + 1)) - zr;
                if (!air(x, py, z)) continue;
                int s = 0;
                if (solidM(get(x - 1, py, z))) s++;
                if (solidM(get(x + 1, py, z))) s++;
                if (solidM(get(x, py, z - 1))) s++;
                if (solidM(get(x, py, z + 1))) s++;
                if (s != 1) continue;
                set(x, py, z, CHEST, 2);
                break;
            }
        }
        set(px, py, pz, SPAWNER, 0);
    }

    // ================================================================== ice and top snow
    static final int SNOW = 78;
    static final Noises.PerlinSimplex SNOW_NOISE = new Noises.PerlinSimplex(new McRandom(0x15cf0), 5);
    static final float[] SNOW_MIN = new float[256], SNOW_MAX = new float[256];
    static {
        for (int b : new int[]{10, 11, 26}) { SNOW_MIN[b] = 0.125f; SNOW_MAX[b] = 0.25f; }
        SNOW_MIN[12] = 0.25f; SNOW_MAX[12] = 1f;
        SNOW_MIN[13] = 0.375f; SNOW_MAX[13] = 1.5f;
        for (int b : new int[]{30, 31, 158, 140}) { SNOW_MIN[b] = 0.125f; SNOW_MAX[b] = 0.5f; }
    }

    /** BlockSource::getTopRainBlockPos: above the highest block that stops rain (anything but air and plants) */
    private int topRain(int x, int z) {
        for (int y = Chunk.H - 1; y >= 0; y--) {
            int id = get(x, y, z);
            if (id != AIR && Blocks.kind[id] != Blocks.CROSS && id != SNOW) return y + 1;
        }
        return 0;
    }

    /** postProcess freeze loop: still water at the top of cold columns becomes ice */
    private void freeze() {
        for (int x = ox; x < ox + 16; x++) for (int z = oz; z < oz + 16; z++) {
            int y = topRain(x, z) - 1;
            if (y < 0 || y > 127) continue;
            if (Biomes.TEMP[w.rawBiome(x, z) & 255] > 0.15f) continue;
            int id = get(x, y, z);
            if ((id == WATER || id == FLOWING_WATER) && w.data(x, y, z) == 0) set(x, y, z, ICE, 0);
        }
    }

    /** Seasons::postProcess: top snow of a noise based depth, deeper in hollows (approx canPlaceTopSnow) */
    private void snow() {
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int z = oz; z < oz + 16; z++) for (int x = ox; x < ox + 16; x++) {
            int y = topRain(x, z);
            int b = w.rawBiome(x, z) & 255;
            if (!(SNOW_MAX[b] > 0f) || !(Biomes.TEMP[b] < 0.15f) || y <= 0 || y > 127) continue;
            if (!air(x, y, z)) continue;
            int below = get(x, y - 1, z);
            if (below == ICE || below == 174 || Blocks.kind[below] == Blocks.LIQUID || (!Blocks.opaque[below] && below != LEAVES && below != LEAVES2)) continue;
            float n = SNOW_NOISE.value3((float) x, (float) y, (float) z);
            float f = -0.17f + (n + 26f) * 0.01923077f;
            int depth = (int) Math.ceil(f * (float) (int) (SNOW_MIN[b] * 8f));
            for (int[] d : dirs) {
                int ny = topRain(x + d[0], z + d[1]);
                if (ny > y) depth++;
                else if (ny < y && depth > 1) depth--;
            }
            if (depth <= 0) continue;
            set(x, y, z, SNOW, Math.min(depth, 8) - 1);
        }
    }
}
