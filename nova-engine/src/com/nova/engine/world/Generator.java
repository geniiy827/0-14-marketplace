package com.nova.engine.world;

import java.util.Random;

/**
 * Terrain like the original of that era (the classic Java-edition algorithm the Pocket Edition generator follows):
 * 3D density from octave Perlin noise on a 5 x 17 x 5 grid per chunk, biome depth/scale blended over 5x5 columns
 * with the parabolic weights, trilinear interpolation, then surface blocks per biome, caves (worms), ores,
 * lava pools deep down, trees and plants (decorations may cross chunk borders through World.place).
 * World height 128, sea level 63.
 */
public final class Generator {
    public static final int SEA = 63;
    static final int STONE = 1, GRASS = 2, DIRT = 3, BEDROCK = 7, WATER = 9, LAVA = 11, SAND = 12, GRAVEL = 13,
            GOLD = 14, IRON = 15, COAL = 16, LOG = 17, LEAVES = 18, LAPIS = 21, SANDSTONE = 24, TALLGRASS = 31,
            DEADBUSH = 32, FLOWER_Y = 37, FLOWER = 38, REDSTONE = 73, DIAMOND = 56, SNOW_LAYER = 78, ICE = 79, CACTUS = 81,
            CLAY = 82, REEDS = 83;
    private final long seed;
    private final Octaves minLimit, maxLimit, main, depth, surface;
    private final Noise temp, humid, cont, weird, caves;
    private static final float[] PARABOLIC = new float[25];
    static {
        for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++)
            PARABOLIC[i + 2 + (j + 2) * 5] = (float) (10.0 / Math.sqrt(i * i + j * j + 0.2));
    }

    public Generator(long seed) {
        this.seed = seed;
        Random r = new Random(seed);
        minLimit = new Octaves(r, 16);
        maxLimit = new Octaves(r, 16);
        main = new Octaves(r, 8);
        surface = new Octaves(r, 4);
        depth = new Octaves(r, 16);
        temp = new Noise(seed * 9871 + 1);
        humid = new Noise(seed * 39811 + 2);
        cont = new Noise(seed * 7 + 3);
        weird = new Noise(seed * 101 + 4);
        caves = new Noise(seed * 1013 + 5);
    }

    // ------------------------------------------------------------------ biomes
    public int biomeAt(int x, int z) {
        // noise quantiles (fbm): p10 -0.24, p25 -0.13, p50 0, p75 0.12, p90 0.24
        double c = cont.fbm(x / 900.0, z / 900.0, 4);
        if (c < -0.12) return c < -0.25 ? Biomes.DEEP_OCEAN : Biomes.OCEAN;
        double t = temp.fbm(x / 700.0, z / 700.0, 3), h = humid.fbm(x / 500.0, z / 500.0, 3), w = weird.fbm(x / 400.0, z / 400.0, 3);
        if (c < -0.095) return t < -0.17 ? Biomes.ICE : Biomes.BEACH;
        if (t < -0.17) return h > 0.0 ? Biomes.TAIGA : Biomes.ICE;
        if (t > 0.17) return h > 0.1 ? Biomes.JUNGLE : h < -0.05 ? Biomes.DESERT : Biomes.SAVANNA;
        if (w > 0.2) return Biomes.HILLS;
        if (h > 0.2) return Biomes.SWAMP;
        if (h > 0.03) return Biomes.FOREST;
        if (w < -0.15 && h > -0.1) return Biomes.BIRCH;
        return Biomes.PLAINS;
    }

    /** approximate surface height (spawn search) */
    public int height(int x, int z) {
        Chunk c = new Chunk(x >> 4, z >> 4);
        terrain(c);
        for (int y = Chunk.H - 1; y > 0; y--) if (c.get(x & 15, y, z & 15) != 0) return y;
        return SEA;
    }

    // ------------------------------------------------------------------ chunk
    public void generate(Chunk c, World w) {
        w.begin(c);
        terrain(c);
        int bx = c.cx << 4, bz = c.cz << 4;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) c.biome[z * 16 + x] = (byte) biomeAt(bx + x, bz + z);
        surface(c);
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

    private void terrain(Chunk c) {
        double[] d = new double[5 * 5 * 17];
        int gx0 = c.cx * 4, gz0 = c.cz * 4;
        int[] biomes = new int[9 * 9];
        for (int j = 0; j < 9; j++) for (int i = 0; i < 9; i++) biomes[j * 9 + i] = biomeAt((gx0 + i - 2) * 4, (gz0 + j - 2) * 4);
        for (int gz = 0; gz < 5; gz++) for (int gx = 0; gx < 5; gx++) {
            int b0 = biomes[(gz + 2) * 9 + gx + 2];
            float sScale = 0, sDepth = 0, sW = 0;
            for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
                int b = biomes[(gz + 2 + j) * 9 + gx + 2 + i];
                float bd = Biomes.DEPTH[b], bs = Biomes.SCALE[b];
                float wgt = PARABOLIC[i + 2 + (j + 2) * 5] / (bd + 2);
                if (bd > Biomes.DEPTH[b0]) wgt /= 2;
                sScale += bs * wgt; sDepth += bd * wgt; sW += wgt;
            }
            double scale = sScale / sW * 0.9 + 0.1;
            double dep = (sDepth / sW * 4 - 1) / 8.0;
            double dn = depth.get2((gx0 + gx) * 200.0, (gz0 + gz) * 200.0) / 8000.0;
            if (dn < 0) dn = -dn * 0.3;
            dn = dn * 3 - 2;
            if (dn < 0) { dn /= 2; if (dn < -1) dn = -1; dn /= 1.4; dn /= 2; }
            else { if (dn > 1) dn = 1; dn /= 8; }
            dep += dn * 0.2;
            dep = dep * 8.5 / 8.0;
            double base = 8.5 + dep * 4;
            for (int gy = 0; gy < 17; gy++) {
                double yo = (gy - base) * 12.0 * 128.0 / 256.0 / scale;
                if (yo < 0) yo *= 4;
                double px = (gx0 + gx) * 684.412, py = gy * 684.412, pz = (gz0 + gz) * 684.412;
                double mn = minLimit.get(px, py, pz) / 512.0, mx = maxLimit.get(px, py, pz) / 512.0;
                double mi = (main.get(px / 80.0, py / 160.0, pz / 80.0) / 10.0 + 1) / 2.0;
                double v = mi < 0 ? mn : mi > 1 ? mx : mn + (mx - mn) * mi;
                v -= yo;
                if (gy > 13) { double t = (gy - 13) / 3.0; v = v * (1 - t) - 10 * t; }
                d[(gx * 5 + gz) * 17 + gy] = v;
            }
        }
        for (int gx = 0; gx < 4; gx++) for (int gz = 0; gz < 4; gz++) for (int gy = 0; gy < 16; gy++) {
            double v000 = d[(gx * 5 + gz) * 17 + gy], v010 = d[(gx * 5 + gz + 1) * 17 + gy];
            double v100 = d[((gx + 1) * 5 + gz) * 17 + gy], v110 = d[((gx + 1) * 5 + gz + 1) * 17 + gy];
            double s000 = (d[(gx * 5 + gz) * 17 + gy + 1] - v000) / 8, s010 = (d[(gx * 5 + gz + 1) * 17 + gy + 1] - v010) / 8;
            double s100 = (d[((gx + 1) * 5 + gz) * 17 + gy + 1] - v100) / 8, s110 = (d[((gx + 1) * 5 + gz + 1) * 17 + gy + 1] - v110) / 8;
            for (int yy = 0; yy < 8; yy++) {
                double a0 = v000, a1 = v010, ax0 = (v100 - v000) / 4, ax1 = (v110 - v010) / 4;
                for (int xx = 0; xx < 4; xx++) {
                    double v = a0, dz = (a1 - a0) / 4;
                    for (int zz = 0; zz < 4; zz++) {
                        int y = gy * 8 + yy, x = gx * 4 + xx, z = gz * 4 + zz;
                        int id = 0;
                        if (v > 0) id = STONE;
                        else if (y < SEA) id = WATER;
                        if (id != 0) c.set(x, y, z, id);
                        v += dz;
                    }
                    a0 += ax0; a1 += ax1;
                }
                v000 += s000; v010 += s010; v100 += s100; v110 += s110;
            }
        }
    }

    /** grass / dirt / sand per biome, beaches, sea floor, bedrock, snow and ice */
    private void surface(Chunk c) {
        int bx = c.cx << 4, bz = c.cz << 4;
        Random r = new Random(seed * 3 + c.cx * 7919L + c.cz * 104729L);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int b = c.biome[z * 16 + x];
            double sn = surface.get2((bx + x) * 0.0625, (bz + z) * 0.0625);
            int depthN = (int) (sn / 3.0 + 3.0 + r.nextDouble() * 0.25);
            int top = b == Biomes.DESERT || b == Biomes.BEACH ? SAND : GRASS;
            int fill = b == Biomes.DESERT || b == Biomes.BEACH ? SAND : DIRT;
            int left = -1;
            for (int y = Chunk.H - 1; y >= 0; y--) {
                if (y <= r.nextInt(5)) { c.set(x, y, z, BEDROCK); continue; }
                int id = c.get(x, y, z);
                if (id == 0) { left = -1; continue; }
                if (id != STONE) continue;
                if (left == -1) {
                    int t = top, f = fill;
                    if (depthN <= 0) { t = 0; f = STONE; }
                    if (y < SEA && t == 0) t = WATER;
                    if (y < SEA - 1 && t == GRASS) t = b == Biomes.OCEAN || b == Biomes.DEEP_OCEAN ? GRAVEL : DIRT;
                    if ((b == Biomes.OCEAN || b == Biomes.DEEP_OCEAN) && y < SEA - 2) { t = r.nextInt(4) == 0 ? CLAY : GRAVEL; f = GRAVEL; }
                    left = depthN;
                    c.set(x, y, z, y >= SEA - 1 ? t : (t == GRASS ? DIRT : t));
                    if (t == SAND && b == Biomes.DESERT) left = depthN + r.nextInt(2);
                } else if (left > 0) {
                    left--;
                    c.set(x, y, z, fill);
                    if (left == 0 && fill == SAND) { left = r.nextInt(4); fill = SANDSTONE; }
                }
            }
            if (b == Biomes.ICE || (b == Biomes.TAIGA && r.nextInt(3) == 0)) {
                for (int y = Chunk.H - 2; y > 0; y--) {
                    int id = c.get(x, y, z);
                    if (id == 0) continue;
                    if (id == WATER && y == SEA - 1) c.set(x, y, z, ICE);
                    else if (id == GRASS || id == DIRT || id == SAND || id == STONE) c.set(x, y + 1, z, SNOW_LAYER);
                    break;
                }
            }
        }
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

    // ------------------------------------------------------------------ trees and plants
    private int top(Chunk c, int x, int z) {
        for (int y = Chunk.H - 2; y > 0; y--) {
            int id = c.get(x, y, z);
            if (id != 0 && id != SNOW_LAYER) return y;
        }
        return 0;
    }

    private void decorate(Chunk c, World w, Random r) {
        int bx = c.cx << 4, bz = c.cz << 4;
        int b = c.biome[8 * 16 + 8];
        float tf = Biomes.TREES[b];
        int trees = (int) tf + (r.nextFloat() < tf - (int) tf ? 1 : 0);
        if (r.nextInt(10) == 0 && b != Biomes.DESERT && b != Biomes.OCEAN && b != Biomes.DEEP_OCEAN) trees++;
        for (int t = 0; t < trees; t++) {
            int x = r.nextInt(16), z = r.nextInt(16), y = top(c, x, z);
            int g = c.get(x, y, z);
            if (g != GRASS && g != DIRT) continue;
            int tb = c.biome[z * 16 + x];
            if (tb == Biomes.TAIGA || tb == Biomes.ICE) spruce(w, r, bx + x, y + 1, bz + z);
            else if (tb == Biomes.BIRCH || (tb == Biomes.FOREST && r.nextInt(5) == 0)) oak(w, r, bx + x, y + 1, bz + z, 2, 5 + r.nextInt(2));
            else if (tb == Biomes.JUNGLE) oak(w, r, bx + x, y + 1, bz + z, 3, 6 + r.nextInt(6));
            else oak(w, r, bx + x, y + 1, bz + z, 0, 4 + r.nextInt(3));
        }
        int grass = b == Biomes.PLAINS || b == Biomes.SAVANNA ? 60 : b == Biomes.JUNGLE ? 50 : b == Biomes.FOREST || b == Biomes.TAIGA || b == Biomes.BIRCH ? 20 : b == Biomes.SWAMP ? 15 : b == Biomes.HILLS ? 8 : 2;
        for (int i = 0; i < grass; i++) {
            int x = r.nextInt(16), z = r.nextInt(16), y = top(c, x, z);
            if (c.get(x, y, z) != GRASS || c.get(x, y + 1, z) != 0) continue;
            int tb = c.biome[z * 16 + x];
            c.set(x, y + 1, z, TALLGRASS, tb == Biomes.TAIGA || tb == Biomes.JUNGLE ? (r.nextInt(3) == 0 ? 2 : 1) : 1);
        }
        int flowers = b == Biomes.PLAINS ? 6 : b == Biomes.FOREST || b == Biomes.BIRCH ? 4 : b == Biomes.SWAMP || b == Biomes.SAVANNA ? 2 : 1;
        for (int i = 0; i < flowers; i++) {
            int x = r.nextInt(16), z = r.nextInt(16), y = top(c, x, z);
            if (c.get(x, y, z) != GRASS || c.get(x, y + 1, z) != 0) continue;
            if (r.nextInt(3) == 0) c.set(x, y + 1, z, FLOWER_Y);
            else c.set(x, y + 1, z, FLOWER, b == Biomes.SWAMP ? 1 : r.nextInt(9) == 0 ? 2 + r.nextInt(7) : 0);
        }
        if (b == Biomes.DESERT) {
            for (int i = 0; i < 6; i++) {
                int x = 1 + r.nextInt(14), z = 1 + r.nextInt(14), y = top(c, x, z);
                if (c.get(x, y, z) != SAND) continue;
                if (r.nextInt(3) == 0) {
                    if (c.get(x + 1, y + 1, z) == 0 && c.get(x - 1, y + 1, z) == 0 && c.get(x, y + 1, z + 1) == 0 && c.get(x, y + 1, z - 1) == 0) {
                        int hgt = 1 + r.nextInt(3);
                        for (int k = 1; k <= hgt; k++) c.set(x, y + k, z, CACTUS);
                    }
                } else if (c.get(x, y + 1, z) == 0) c.set(x, y + 1, z, DEADBUSH);
            }
        }
        // sugar cane next to water
        for (int i = 0; i < 10; i++) {
            int x = 1 + r.nextInt(14), z = 1 + r.nextInt(14), y = top(c, x, z);
            int g = c.get(x, y, z);
            if ((g != GRASS && g != SAND && g != DIRT) || y != SEA - 1 + 0 && y != SEA) continue;
            if (c.get(x + 1, y, z) == WATER || c.get(x - 1, y, z) == WATER || c.get(x, y, z + 1) == WATER || c.get(x, y, z - 1) == WATER) {
                int hgt = 1 + r.nextInt(3);
                for (int k = 1; k <= hgt; k++) if (c.get(x, y + k, z) == 0) c.set(x, y + k, z, REEDS);
            }
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

    // ------------------------------------------------------------------ noise
    /** Ken Perlin's improved noise with a random offset, like the original's NoiseGeneratorImproved */
    static final class Improved {
        final int[] p = new int[512];
        final double xo, yo, zo;
        Improved(Random r) {
            xo = r.nextDouble() * 256; yo = r.nextDouble() * 256; zo = r.nextDouble() * 256;
            for (int i = 0; i < 256; i++) p[i] = i;
            for (int i = 0; i < 256; i++) { int j = r.nextInt(256 - i) + i; int t = p[i]; p[i] = p[j]; p[j] = t; p[i + 256] = p[i]; }
        }
        double noise(double x, double y, double z) { return Noise.noise(p, x + xo, y + yo, z + zo); }
    }

    /** octaves: octave i samples at frequency 1/2^i and weighs 2^i (as the original: large raw values) */
    static final class Octaves {
        final Improved[] g;
        Octaves(Random r, int n) { g = new Improved[n]; for (int i = 0; i < n; i++) g[i] = new Improved(r); }
        double get(double x, double y, double z) {
            double v = 0, f = 1;
            for (Improved o : g) { v += o.noise(x * f, y * f, z * f) / f; f /= 2; }
            return v;
        }
        double get2(double x, double z) {
            double v = 0, f = 1;
            for (Improved o : g) { v += o.noise(x * f, 10, z * f) / f; f /= 2; }
            return v;
        }
    }

    static final class Noise {
        private final int[] p = new int[512];
        Noise(long seed) {
            int[] q = new int[256];
            for (int i = 0; i < 256; i++) q[i] = i;
            Random r = new Random(seed);
            for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = q[i]; q[i] = q[j]; q[j] = t; }
            for (int i = 0; i < 512; i++) p[i] = q[i & 255];
        }
        double fbm(double x, double z, int oct) {
            double s = 0, a = 1, f = 1, norm = 0;
            for (int i = 0; i < oct; i++) { s += a * noise(p, x * f, 0.5 + i * 3.17, z * f); norm += a; a *= 0.5; f *= 2; }
            return s / norm;
        }
        static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
        static double grad(int h, double x, double y, double z) {
            h &= 15; double u = h < 8 ? x : y, v = h < 4 ? y : h == 12 || h == 14 ? x : z;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }
        static double lerp(double t, double a, double b) { return a + t * (b - a); }
        static double noise(int[] p, double x, double y, double z) {
            double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
            int X = (int) ((long) fx & 255), Y = (int) ((long) fy & 255), Z = (int) ((long) fz & 255);
            x -= fx; y -= fy; z -= fz;
            double u = fade(x), v = fade(y), w = fade(z);
            int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z, B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
            return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)), lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
                    lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)), lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
        }
    }
}
