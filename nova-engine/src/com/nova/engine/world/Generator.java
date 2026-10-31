package com.nova.engine.world;

import java.util.Random;

/**
 * World generator of MCPE 0.14.3 (RandomLevelSource), ported from libminecraftpe:
 * - noises created from Random(seed) in the original order (lperlin1 16, lperlin2 16, perlin1 8, surface simplex 4,
 *   scale 10, depth 16, forest 8), float-exact ImprovedNoise / SimplexNoise;
 * - biomes from the layer stack (Layers), raw 1:4 biomes for the heights, Voronoi 1:1 per column;
 * - getHeights (5x17x5 density, 3x3 biome blend) + prepareHeights (trilinear fill: stone / water below 63);
 * - buildSurfaces: per column Biome::buildSurfaceAt with the chunk Random (MT seeded x*341873128 + z*132899541),
 *   bedrock 2..5 blocks.
 * Same seed = same terrain and biomes as the original. Caves, ores and plants are still the interim versions
 * (marked below) until LargeCaveFeature and the biome decorators are ported.
 */
public final class Generator {
    public static final int SEA = 63;
    static final int STONE = 1, GRASS = 2, DIRT = 3, BEDROCK = 7, WATER = 9, LAVA = 11, SAND = 12, GRAVEL = 13,
            GOLD = 14, IRON = 15, COAL = 16, LOG = 17, LEAVES = 18, LAPIS = 21, SANDSTONE = 24, TALLGRASS = 31,
            DEADBUSH = 32, FLOWER_Y = 37, FLOWER = 38, REDSTONE = 73, DIAMOND = 56, SNOW_LAYER = 78, ICE = 79, CACTUS = 81,
            CLAY = 82, REEDS = 83, PODZOL = 243, HARDENED_CLAY = 172, STAINED_CLAY = 159;
    private final long seed;
    private final int iseed;
    private final Noises.Perlin lperlin1, lperlin2, perlin1, scaleNoise, depthNoise, forestNoise;
    private final Noises.PerlinSimplex surfaceNoise;
    private final ThreadLocal<Layers> layers = new ThreadLocal<Layers>() {
        @Override protected Layers initialValue() { return new Layers(iseed); }
    };
    /** RandomLevelSource +0x3c..: 10/sqrt(dx^2+dz^2+0.2) as stored by the original (only the 3x3 middle is used) */
    static final float[] WEIGHT = new float[25];
    static {
        int[] bits = {0x405f7f69, 0x408c544c, 0x409c24de, 0x408c544c, 0x405f7f69,
                      0x408c544c, 0x40d7be73, 0x41120f31, 0x40d7be73, 0x408c544c,
                      0x409c24de, 0x41120f31, 0x41b2e2ac, 0x41120f31, 0x409c24de,
                      0x408c544c, 0x40d7be73, 0x41120f31, 0x40d7be73, 0x408c544c,
                      0x405f7f69, 0x408c544c, 0x409c24de, 0x408c544c, 0x405f7f69};
        for (int i = 0; i < 25; i++) WEIGHT[i] = Float.intBitsToFloat(bits[i]);
    }

    public Generator(long seed) {
        this.seed = seed;
        this.iseed = (int) seed;
        McRandom r = new McRandom(iseed);
        lperlin1 = new Noises.Perlin(r, 16);
        lperlin2 = new Noises.Perlin(r, 16);
        perlin1 = new Noises.Perlin(r, 8);
        surfaceNoise = new Noises.PerlinSimplex(r, 4);
        scaleNoise = new Noises.Perlin(r, 10);
        depthNoise = new Noises.Perlin(r, 16);
        forestNoise = new Noises.Perlin(r, 8);
    }

    // ------------------------------------------------------------------ biomes
    public int biomeAt(int x, int z) { return layers.get().biomes1.get(x, z, 1, 1)[0] & 255; }

    /** surface height (spawn search) */
    public int height(int x, int z) {
        Chunk c = new Chunk(x >> 4, z >> 4);
        prepareHeights(c);
        for (int y = Chunk.H - 1; y > 0; y--) if (c.get(x & 15, y, z & 15) != 0) return y;
        return SEA;
    }

    // ------------------------------------------------------------------ chunk
    /** phase 1 (worker threads): terrain, surface, caves */
    public void terrain(Chunk c) {
        McRandom rnd = new McRandom(c.cx * 0x14609048 + c.cz * 0x07ebe2d5);
        int[] b = layers.get().biomes1.get(c.cx << 4, c.cz << 4, 16, 16);
        for (int i = 0; i < 256; i++) c.biome[i] = (byte) b[i];
        prepareHeights(c);
        buildSurfaces(c, rnd);
        carveCaves(c);     // interim
    }

    /** old single pass (interim decoration), kept for tools */
    public void generate(Chunk c, World w) {
        w.begin(c);
        McRandom rnd = new McRandom(c.cx * 0x14609048 + c.cz * 0x07ebe2d5);
        int[] b = layers.get().biomes1.get(c.cx << 4, c.cz << 4, 16, 16);
        for (int i = 0; i < 256; i++) c.biome[i] = (byte) b[i];      // index x + z*16
        prepareHeights(c);
        buildSurfaces(c, rnd);
        // ---- interim (not yet the original): caves, ores, plants
        carveCaves(c);
        Random r = new Random(seed ^ (c.cx * 341873128712L + c.cz * 132897987541L));
        ore(c, r, COAL, 20, 127, 12);
        ore(c, r, IRON, 20, 63, 8);
        ore(c, r, GOLD, 2, 31, 8);
        ore(c, r, REDSTONE, 8, 15, 7);
        ore(c, r, DIAMOND, 1, 15, 7);
        ore(c, r, LAPIS, 1, 30, 6);
        decorate(c, w, r);
        c.generated = true;
        w.finish(c);
    }

    // ------------------------------------------------------------------ RandomLevelSource::getHeights
    private void getHeights(float[] out, int[] raw, int x, int y, int z) {
        float[] dn = new float[25], mn = new float[425], n1 = new float[425], n2 = new float[425];
        depthNoise.region2(dn, x, z, 5, 5, 200f, 200f);
        perlin1.region(mn, x, y, z, 5, 17, 5, 8.55515f, 4.277575f, 8.55515f);
        lperlin1.region(n1, x, y, z, 5, 17, 5, 684.412f, 855.515f, 684.412f);
        lperlin2.region(n2, x, y, z, 5, 17, 5, 684.412f, 684.412f, 684.412f);
        int o = 0;
        for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) {
            int center = raw[(j + 2) * 10 + i + 2];
            float cd = Biomes.DEPTH[center];
            float ds = 0f, ss = 0f, ws = 0f;
            for (int cc = 0; cc < 3; cc++) {
                float d0 = 0, d1 = 0, d2 = 0, s0 = 0, s1 = 0, s2 = 0, w0 = 0, w1 = 0, w2 = 0;
                for (int rr = 0; rr < 3; rr++) {
                    int nb = raw[(j + 1 + rr) * 10 + i + 1 + cc];
                    float nd = Biomes.DEPTH[nb], ns = Biomes.SCALE[nb];
                    float wt = WEIGHT[6 + rr * 5 + cc] / (nd + 2.0f);
                    if (nd > cd) wt = wt * 0.5f;
                    if (rr == 0) { d0 = nd; s0 = ns; w0 = wt; } else if (rr == 1) { d1 = nd; s1 = ns; w1 = wt; } else { d2 = nd; s2 = ns; w2 = wt; }
                }
                float td = w0 * d0; td = td + w1 * d1; td = td + w2 * d2;
                float ts = w0 * s0; ts = ts + w1 * s1; ts = ts + w2 * s2;
                float tw = w1 + w0; tw = tw + w2;
                ds = ds + td; ws = ws + tw; ss = ss + ts;
            }
            float avgD = ds / ws;
            float base = -1f + avgD * 4f;
            float dv = dn[i * 5 + j] * 1.25e-4f;
            float avgS = ss / ws;
            if (dv < 0f) dv = -(dv * 0.3f);
            base = base * 0.125f;
            float d3 = dv * 3f - 2f;
            float sc = 0.1f + avgS * 0.9f;
            float add;
            if (d3 < 0f) {
                add = d3 * 0.5f;
                if (add < -1f) add = -1f;
                add = add * 0.35714287f;
            } else {
                add = d3 > 1f ? 1f : d3;
                add = add * 0.125f;
            }
            base = base + add * 0.2f;
            float k = 12f / sc;
            float off = -8.5f - base * 4.25f;
            for (int yy = 0; yy < 17; yy++, o++) {
                float t = (1f + mn[o] * 0.1f) * 0.5f;
                float lo = n1[o] * 0.00390625f;
                float yo = k * ((float) yy + off);
                if (yo < 0f) yo = yo * 4f;
                float v;
                if (t < 0f) v = lo;
                else {
                    float hi = n2[o] * 0.001953125f;
                    v = t <= 1f ? lo + (hi - lo) * t : hi;
                }
                v = v - yo;
                if (yy > 13) {
                    float f = (float) (yy - 13) * 0.33333334f;
                    v = f * -10f + v * (1f - f);
                }
                out[o] = v;
            }
        }
    }

    // ------------------------------------------------------------------ RandomLevelSource::prepareHeights
    private void prepareHeights(Chunk c) {
        int[] raw = layers.get().biomes4.get(c.cx * 4 - 2, c.cz * 4 - 2, 10, 10);
        for (int i = 0; i < raw.length; i++) if (!Biomes.valid(raw[i])) raw[i] = 0;
        float[] h = new float[425];
        getHeights(h, raw, c.cx * 4, 0, c.cz * 4);
        byte[] bl = c.blocks;
        for (int xc = 0; xc < 4; xc++) for (int zc = 0; zc < 4; zc++) {
            int b00 = (xc * 5 + zc) * 17, b01 = (xc * 5 + zc + 1) * 17, b10 = ((xc + 1) * 5 + zc) * 17, b11 = ((xc + 1) * 5 + zc + 1) * 17;
            for (int yc = 0; yc < 16; yc++) {
                float s7 = h[b00 + yc], s8 = h[b01 + yc], s5 = h[b10 + yc], s6 = h[b11 + yc];
                float s1 = (h[b00 + yc + 1] - s7) * 0.125f, s2 = (h[b01 + yc + 1] - s8) * 0.125f;
                float s3 = (h[b10 + yc + 1] - s5) * 0.125f, s4 = (h[b11 + yc + 1] - s6) * 0.125f;
                for (int sy = 0; sy < 8; sy++) {
                    int y = yc * 8 + sy;
                    float s9 = (s5 - s7) * 0.25f, s10 = (s6 - s8) * 0.25f;
                    float s15 = s7, s13 = s8;
                    for (int sx = 0; sx < 4; sx++) {
                        int x = xc * 4 + sx;
                        float s14 = (s13 - s15) * 0.25f;
                        float v = s15;
                        for (int sz = 0; sz < 4; sz++) {
                            int z = zc * 4 + sz;
                            int id = v > 0f ? STONE : y < SEA ? WATER : 0;
                            if (id != 0) bl[Chunk.idx(x, y, z)] = (byte) id;
                            v = v + s14;
                        }
                        s15 = s15 + s9;
                        s13 = s13 + s10;
                    }
                    s7 = s7 + s1; s8 = s8 + s2; s5 = s5 + s3; s6 = s6 + s4;
                }
            }
        }
    }

    // ------------------------------------------------------------------ RandomLevelSource::buildSurfaces
    private void buildSurfaces(Chunk c, McRandom rnd) {
        float[] noise = new float[256];
        surfaceNoise.region(noise, c.cx * 16, c.cz * 16, 16, 16, 0.125f, 0.125f, 1f, 0.5f);
        int[] st = new int[3];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int b = c.biome[z * 16 + x] & 255;
            if (!Biomes.valid(b)) b = 0;
            surfaceAt(b, c, rnd, x, z, noise[x + z * 16], st);
        }
    }

    /** Biome::buildSurfaceAt (+ the overrides of ExtremeHills, Taiga, MutatedSavanna; Mesa simplified) */
    private void surfaceAt(int b, Chunk c, McRandom rnd, int x, int z, float noise, int[] st) {
        int top = Biomes.TOP[b], topData = Biomes.TOP_DATA[b], filler = Biomes.FILLER[b];
        int cls = Biomes.CLASS[b], type = Biomes.TYPE[b];
        if (cls == Biomes.C_HILLS) {
            top = GRASS; topData = 0; filler = DIRT;
            if ((noise > 0.2f || noise < -0.1f) && type == 2) { top = GRAVEL; filler = GRAVEL; }
            else if (noise > 1.0f && type != 1) { top = STONE; filler = STONE; }
        } else if (cls == Biomes.C_TAIGA && (type == 1 || type == 2)) {
            top = GRASS; topData = 0; filler = DIRT;
            if (noise > 1.75f) { top = DIRT; topData = 1; }
            else if ((double) noise > -0.95) top = PODZOL;
        } else if (cls == Biomes.C_SAVANNA_MUT) {
            top = GRASS; topData = 0; filler = DIRT;
            if (noise > 1.75f) { top = STONE; filler = STONE; }
            else if (noise > -0.5f) { top = DIRT; topData = 1; }
        }
        surfaceDefault(b, c, rnd, x, z, noise, top, topData, filler);
    }

    /** Biome::buildSurfaceAtDefault + Biome::_placeBedrock */
    private void surfaceDefault(int b, Chunk c, McRandom rnd, int x, int z, float noise, int top, int topData, int filler) {
        int depth = (int) (3.0f + noise * 0.33333334f + rnd.nextFloat() * 0.25f);
        int nb = (int) (rnd.nextU32() & 3) + 2;
        for (int y = 0; y < nb; y++) c.set(x, y, z, BEDROCK);
        boolean mesa = Biomes.CLASS[b] == Biomes.C_MESA;
        final int t0 = top, d0 = topData, f0 = filler;   // the biome fields (set by the override)
        int run = -1;
        for (int y = 127; y > 0; y--) {
            int id = c.get(x, y, z);
            if (id == BEDROCK) return;
            if (id == 0) { run = -1; continue; }
            if (id != STONE) continue;
            if (run == -1) {
                if (depth <= 0) { top = 0; topData = 0; filler = STONE; }
                else if (y >= 59 && y <= 64) { top = t0; topData = d0; filler = f0; }
                if (y <= 62 && top == 0) {
                    if (Biomes.TEMP[b] < 0.15f) top = ICE; else top = WATER;
                    topData = 0;
                }
                if (y >= 62) {
                    if (mesa) mesaColumn(c, x, y, z, depth);
                    else c.set(x, y, z, top, topData);
                    run = depth;
                } else if (y < 56 - depth) {
                    top = 0; filler = STONE;
                    c.set(x, y, z, GRAVEL);
                    run = depth;
                } else {
                    c.set(x, y, z, filler);
                    run = depth;
                }
            } else if (run > 0) {
                run--;
                c.set(x, y, z, filler);
                if (run == 0 && filler == SAND) {
                    run = (int) (rnd.nextU32() & 3) + Math.max(0, y - 63);
                    filler = SANDSTONE;
                }
            }
        }
    }

    /** Mesa: simplified until MesaBiome::buildSurfaceAt is ported (red sand near sea level, clay bands above) */
    private static final int[] BANDS = {0, 1, 0, 14, 0, 4, 0, -1, 0, 0, 12, 0, 1, -1, 0, 0};
    private void mesaColumn(Chunk c, int x, int y, int z, int depth) {
        if (y <= 66) { c.set(x, y, z, SAND, 1); return; }
        int d = BANDS[y & 15];
        if (d < 0) c.set(x, y, z, HARDENED_CLAY); else c.set(x, y, z, STAINED_CLAY, d);
    }

    // ------------------------------------------------------------------ caves: noise worms carved from every chunk in range
    private void carveCaves(Chunk c) {
        int range = 6;
        for (int ox = c.cx - range; ox <= c.cx + range; ox++) for (int oz = c.cz - range; oz <= c.cz + range; oz++) {
            Random r = new Random(seed ^ (ox * 341873128712L) ^ (oz * 132897987541L) ^ 0x5DEECE66DL);
            int n = r.nextInt(r.nextInt(r.nextInt(12) + 1) + 1);
            if (r.nextInt(6) != 0) n = 0;
            for (int i = 0; i < n; i++) {
                double x = ox * 16 + r.nextInt(16), y = r.nextInt(r.nextInt(100) + 8), z = oz * 16 + r.nextInt(16);
                int tunnels = 1;
                if (r.nextInt(4) == 0) { room(c, r, x, y, z); tunnels += r.nextInt(4); }
                for (int t = 0; t < tunnels; t++) {
                    float yaw = r.nextFloat() * (float) Math.PI * 2, pitch = (r.nextFloat() - 0.5f) * 2 / 8;
                    float w = r.nextFloat() * 2 + r.nextFloat();
                    worm(c, r.nextLong(), x, y, z, w, yaw, pitch, 0, 0, 1.0);
                }
            }
        }
    }

    private void room(Chunk c, Random r, double x, double y, double z) {
        worm(c, r.nextLong(), x, y, z, 1 + r.nextFloat() * 6, 0, 0, -1, -1, 0.5);
    }

    private void worm(Chunk c, long s, double x, double y, double z, float width, float yaw, float pitch, int step, int len, double vScale) {
        Random r = new Random(s);
        double cx = c.cx * 16 + 8, cz = c.cz * 16 + 8;
        float dYaw = 0, dPitch = 0;
        if (len <= 0) len = 112 - r.nextInt(28);
        boolean single = step == -1;
        if (single) step = len / 2;
        int split = r.nextInt(len / 2) + len / 4;
        boolean steep = r.nextInt(6) == 0;
        for (; step < len; step++) {
            double rh = 1.5 + Math.sin(step * Math.PI / len) * width;
            double rv = rh * vScale;
            float cp = (float) Math.cos(pitch);
            x += Math.cos(yaw) * cp; y += Math.sin(pitch); z += Math.sin(yaw) * cp;
            pitch *= steep ? 0.92f : 0.7f;
            pitch += dPitch * 0.1f; yaw += dYaw * 0.1f;
            dPitch *= 0.9f; dYaw *= 0.75f;
            dPitch += (r.nextFloat() - r.nextFloat()) * r.nextFloat() * 2;
            dYaw += (r.nextFloat() - r.nextFloat()) * r.nextFloat() * 4;
            if (!single && step == split && width > 1) {
                worm(c, r.nextLong(), x, y, z, r.nextFloat() * 0.5f + 0.5f, yaw - (float) Math.PI / 2, pitch / 3, step, len, 1);
                worm(c, r.nextLong(), x, y, z, r.nextFloat() * 0.5f + 0.5f, yaw + (float) Math.PI / 2, pitch / 3, step, len, 1);
                return;
            }
            if (!single && r.nextInt(4) == 0) continue;
            double ddx = x - cx, ddz = z - cz, left = len - step, maxR = width + 18;
            if (ddx * ddx + ddz * ddz - left * left > maxR * maxR) return;
            if (x < cx - 16 - rh * 2 || z < cz - 16 - rh * 2 || x > cx + 16 + rh * 2 || z > cz + 16 + rh * 2) continue;
            int x0 = (int) Math.floor(x - rh) - c.cx * 16 - 1, x1 = (int) Math.floor(x + rh) - c.cx * 16 + 1;
            int y0 = (int) Math.floor(y - rv) - 1, y1 = (int) Math.floor(y + rv) + 1;
            int z0 = (int) Math.floor(z - rh) - c.cz * 16 - 1, z1 = (int) Math.floor(z + rh) - c.cz * 16 + 1;
            x0 = Math.max(0, x0); x1 = Math.min(16, x1); y0 = Math.max(1, y0); y1 = Math.min(120, y1); z0 = Math.max(0, z0); z1 = Math.min(16, z1);
            boolean water = false;
            for (int bx = x0; bx < x1 && !water; bx++) for (int bz = z0; bz < z1 && !water; bz++)
                for (int by = y1 + 1; by >= y0 - 1 && !water; by--) {
                    if (by < 0 || by >= Chunk.H) continue;
                    int id = c.get(bx, by, bz);
                    if (id == WATER) water = true;
                }
            if (water) continue;
            for (int bx = x0; bx < x1; bx++) {
                double fx = (bx + c.cx * 16 + 0.5 - x) / rh;
                for (int bz = z0; bz < z1; bz++) {
                    double fz = (bz + c.cz * 16 + 0.5 - z) / rh;
                    if (fx * fx + fz * fz >= 1) continue;
                    boolean grassTop = false;
                    for (int by = y1 - 1; by >= y0; by--) {
                        double fy = (by + 0.5 - y) / rv;
                        if (fy > -0.7 && fx * fx + fy * fy + fz * fz < 1) {
                            int id = c.get(bx, by, bz);
                            if (id == GRASS) grassTop = true;
                            if (id == STONE || id == DIRT || id == GRASS || id == SAND || id == GRAVEL || id == SANDSTONE) {
                                if (by < 11) c.set(bx, by, bz, LAVA);
                                else {
                                    c.set(bx, by, bz, 0);
                                    if (grassTop && c.get(bx, by - 1, bz) == DIRT) c.set(bx, by - 1, bz, GRASS);
                                }
                            }
                        }
                    }
                }
            }
            if (single) break;
        }
    }

    private void ore(Chunk c, Random r, int id, int tries, int maxY, int size) {
        for (int i = 0; i < tries; i++) {
            int x = r.nextInt(16), y = 1 + r.nextInt(maxY), z = r.nextInt(16);
            for (int k = 0; k < size; k++) {
                if (c.get(x, y, z) == STONE) c.set(x, y, z, id);
                x = Math.max(0, Math.min(15, x + r.nextInt(3) - 1));
                y = Math.max(1, Math.min(Chunk.H - 1, y + r.nextInt(3) - 1));
                z = Math.max(0, Math.min(15, z + r.nextInt(3) - 1));
            }
        }
    }


    // ------------------------------------------------------------------ interim plants and trees (not the original yet)
    private int top(Chunk c, int x, int z) {
        for (int y = Chunk.H - 2; y > 0; y--) {
            int id = c.get(x, y, z);
            if (id != 0 && id != SNOW_LAYER) return y;
        }
        return 0;
    }

    private void decorate(Chunk c, World w, Random r) {
        int bx = c.cx << 4, bz = c.cz << 4;
        int b = c.biome[8 * 16 + 8] & 255;
        int cls = Biomes.CLASS[b];
        float tf = Biomes.TREES[b];
        int trees = (int) tf + (r.nextFloat() < tf - (int) tf ? 1 : 0);
        for (int t = 0; t < trees; t++) {
            int x = r.nextInt(16), z = r.nextInt(16), y = top(c, x, z);
            int g = c.get(x, y, z);
            if (g != GRASS && g != DIRT && g != PODZOL) continue;
            int tb = c.biome[z * 16 + x] & 255, tc = Biomes.CLASS[tb];
            if (tc == Biomes.C_TAIGA || tc == Biomes.C_SNOW) spruce(w, r, bx + x, y + 1, bz + z);
            else if (tb == 27 || tb == 28 || tc == Biomes.C_FOREST_MUT || (tc == Biomes.C_FOREST && r.nextInt(5) == 0)) oak(w, r, bx + x, y + 1, bz + z, 2, 5 + r.nextInt(2));
            else if (tc == Biomes.C_JUNGLE) oak(w, r, bx + x, y + 1, bz + z, 3, 6 + r.nextInt(6));
            else oak(w, r, bx + x, y + 1, bz + z, 0, 4 + r.nextInt(3));
        }
        int grass = cls == Biomes.C_PLAINS || cls == Biomes.C_SAVANNA || cls == Biomes.C_SAVANNA_MUT ? 30 : cls == Biomes.C_JUNGLE ? 40
                : cls == Biomes.C_FOREST || cls == Biomes.C_TAIGA || cls == Biomes.C_FOREST_MUT ? 15 : cls == Biomes.C_SWAMP ? 10 : cls == Biomes.C_HILLS ? 5 : 2;
        for (int i = 0; i < grass; i++) {
            int x = r.nextInt(16), z = r.nextInt(16), y = top(c, x, z);
            if (c.get(x, y, z) != GRASS || c.get(x, y + 1, z) != 0) continue;
            int tc = Biomes.CLASS[c.biome[z * 16 + x] & 255];
            c.set(x, y + 1, z, TALLGRASS, tc == Biomes.C_TAIGA || tc == Biomes.C_JUNGLE ? (r.nextInt(3) == 0 ? 2 : 1) : 1);
        }
        int flowers = cls == Biomes.C_PLAINS ? 4 : cls == Biomes.C_FOREST || cls == Biomes.C_FOREST_MUT ? 3 : cls == Biomes.C_SWAMP || cls == Biomes.C_SAVANNA ? 1 : 1;
        for (int i = 0; i < flowers; i++) {
            int x = r.nextInt(16), z = r.nextInt(16), y = top(c, x, z);
            if (c.get(x, y, z) != GRASS || c.get(x, y + 1, z) != 0) continue;
            if (r.nextInt(3) == 0) c.set(x, y + 1, z, FLOWER_Y);
            else c.set(x, y + 1, z, FLOWER, cls == Biomes.C_SWAMP ? 1 : r.nextInt(9) == 0 ? 2 + r.nextInt(7) : 0);
        }
        if (cls == Biomes.C_DESERT || cls == Biomes.C_MESA) {
            for (int i = 0; i < 6; i++) {
                int x = 1 + r.nextInt(14), z = 1 + r.nextInt(14), y = top(c, x, z);
                if (c.get(x, y, z) != SAND) continue;
                if (cls == Biomes.C_DESERT && r.nextInt(3) == 0) {
                    if (c.get(x + 1, y + 1, z) == 0 && c.get(x - 1, y + 1, z) == 0 && c.get(x, y + 1, z + 1) == 0 && c.get(x, y + 1, z - 1) == 0) {
                        int hgt = 1 + r.nextInt(3);
                        for (int k = 1; k <= hgt; k++) c.set(x, y + k, z, CACTUS);
                    }
                } else if (c.get(x, y + 1, z) == 0) c.set(x, y + 1, z, DEADBUSH);
            }
        }
        for (int i = 0; i < 10; i++) {
            int x = 1 + r.nextInt(14), z = 1 + r.nextInt(14), y = top(c, x, z);
            int g = c.get(x, y, z);
            if ((g != GRASS && g != SAND && g != DIRT) || y != SEA - 1 && y != SEA) continue;
            if (c.get(x + 1, y, z) == WATER || c.get(x - 1, y, z) == WATER || c.get(x, y, z + 1) == WATER || c.get(x, y, z - 1) == WATER) {
                int hgt = 1 + r.nextInt(3);
                for (int k = 1; k <= hgt; k++) if (c.get(x, y + k, z) == 0) c.set(x, y + k, z, REEDS);
            }
        }
        // snow and ice on snowy biomes (the original freezes in postProcess)
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int tb = c.biome[z * 16 + x] & 255;
            if (!Biomes.SNOWY[tb] && Biomes.TEMP[tb] >= 0.15f) continue;
            int y = top(c, x, z);
            int id = c.get(x, y, z);
            if (id == WATER && y == SEA - 1) c.set(x, y, z, ICE);
            else if (id != 0 && id != WATER && id != ICE && Blocks.solid[id] && Blocks.opaque[id] && c.get(x, y + 1, z) == 0) c.set(x, y + 1, z, SNOW_LAYER);
        }
    }

    /** kind: 0 oak, 2 birch, 3 jungle (log/leaves data) */
    private void oak(World w, Random r, int x, int y, int z, int kind, int h) {
        if (y + h + 2 >= Chunk.H) return;
        for (int yy = y + h - 3; yy <= y + h; yy++) {
            int dy = yy - (y + h), rad = 1 - dy / 2;
            for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                if (Math.abs(dx) == rad && Math.abs(dz) == rad && (r.nextInt(2) == 0 || dy == 0)) continue;
                w.placeIfAir(x + dx, yy, z + dz, LEAVES, kind);
            }
        }
        for (int k = 0; k < h; k++) w.place(x, y + k, z, LOG, kind);
        w.place(x, y - 1, z, DIRT, 0);
    }

    private void spruce(World w, Random r, int x, int y, int z) {
        int h = 6 + r.nextInt(4), bare = 1 + r.nextInt(2);
        if (y + h + 2 >= Chunk.H) return;
        int rad = 0, maxR = 2 + r.nextInt(2);
        for (int yy = y + h; yy >= y + bare; yy--) {
            for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                if (Math.abs(dx) == rad && Math.abs(dz) == rad && rad > 0) continue;
                w.placeIfAir(x + dx, yy, z + dz, LEAVES, 1);
            }
            rad = rad >= maxR ? 1 : rad + 1;
        }
        w.placeIfAir(x, y + h + 1, z, LEAVES, 1);
        for (int k = 0; k < h; k++) w.place(x, y + k, z, LOG, 1);
    }

}
