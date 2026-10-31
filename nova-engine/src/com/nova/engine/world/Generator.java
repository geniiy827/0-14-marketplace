package com.nova.engine.world;

import java.util.Random;

/** Beta terrain: hills from octave Perlin noise, sea level 62, beaches, trees, flowers, ores, caves-lite. */
public final class Generator {
    static final int STONE = 1, GRASS = 2, DIRT = 3, BEDROCK = 7, WATER = 9, SAND = 12, GRAVEL = 13,
            GOLD = 14, IRON = 15, COAL = 16, LOG = 17, LEAVES = 18, TALLGRASS = 31, FLOWER_Y = 37, FLOWER_R = 38,
            DIAMOND = 56, SNOW = 80;
    public static final int SEA = 62;
    private final long seed;
    private final Noise n1, n2, n3, n4;

    public Generator(long seed) {
        this.seed = seed;
        n1 = new Noise(seed);
        n2 = new Noise(seed * 31 + 7);
        n3 = new Noise(seed * 131 + 11);
        n4 = new Noise(seed * 17 + 3);
    }

    public int height(int x, int z) {
        double cont = n1.fbm(x / 300.0, z / 300.0, 4);            // continents
        double hills = n2.fbm(x / 80.0, z / 80.0, 4);
        double rough = Math.max(0, n3.fbm(x / 160.0, z / 160.0, 2));
        double h = SEA + 2 + cont * 22 + hills * (6 + rough * 26);
        return (int) Math.max(4, Math.min(Chunk.H - 8, h));
    }

    public void generate(Chunk c) {
        int bx = c.cx << 4, bz = c.cz << 4;
        Random r = new Random(seed ^ (c.cx * 341873128712L + c.cz * 132897987541L));
        int[] hs = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = bx + x, wz = bz + z;
            int h = height(wx, wz);
            hs[z * 16 + x] = h;
            boolean beach = h <= SEA + 1;
            for (int y = 0; y <= Math.max(h, SEA); y++) {
                int id;
                if (y == 0) id = BEDROCK;
                else if (y <= 2 && r.nextInt(2) == 0) id = BEDROCK;
                else if (y > h) id = WATER;
                else if (y == h) id = beach ? (h < SEA - 3 ? GRAVEL : SAND) : (h > 100 ? SNOW : GRASS);
                else if (y > h - 4) id = beach ? SAND : DIRT;
                else id = STONE;
                c.set(x, y, z, id);
            }
            // caves-lite: worms of air from 3D noise below the surface
            for (int y = 5; y < h - 4; y++) {
                double v = n4.noise(wx / 24.0, y / 16.0, wz / 24.0);
                if (Math.abs(v) < 0.06) c.set(x, y, z, 0);
            }
        }
        // ores
        ore(c, r, COAL, 20, 127, 8);
        ore(c, r, IRON, 12, 64, 6);
        ore(c, r, GOLD, 3, 32, 5);
        ore(c, r, DIAMOND, 2, 16, 4);
        // plants and trees (inside the chunk only: no cross-chunk writes)
        for (int i = 0; i < 40; i++) {
            int x = r.nextInt(16), z = r.nextInt(16), h = hs[z * 16 + x];
            if (c.get(x, h, z) != GRASS) continue;
            int k = r.nextInt(10);
            c.set(x, h + 1, z, k < 7 ? TALLGRASS : k < 9 ? FLOWER_R : FLOWER_Y);
        }
        int trees = r.nextInt(3);
        for (int t = 0; t < trees; t++) {
            int x = 2 + r.nextInt(12), z = 2 + r.nextInt(12), h = hs[z * 16 + x];
            if (c.get(x, h, z) != GRASS || h > 100) continue;
            int th = 4 + r.nextInt(2);
            for (int y = h + th - 2; y <= h + th + 1; y++) {
                int rad = y > h + th - 1 ? 1 : 2;
                for (int dz = -rad; dz <= rad; dz++) for (int dx = -rad; dx <= rad; dx++) {
                    if (Math.abs(dx) == rad && Math.abs(dz) == rad && (rad == 2 ? r.nextInt(2) == 0 : y == h + th + 1)) continue;
                    if (c.get(x + dx, y, z + dz) == 0) c.set(x + dx, y, z + dz, LEAVES);
                }
            }
            for (int y = h + 1; y <= h + th; y++) c.set(x, y, z, LOG);
            c.set(x, h, z, DIRT);
        }
        c.generated = true;
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

    /** Ken Perlin's improved noise with a seeded permutation */
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
            for (int i = 0; i < oct; i++) { s += a * noise(x * f, 0.5 + i * 3.17, z * f); norm += a; a *= 0.5; f *= 2; }
            return s / norm;
        }
        static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
        static double grad(int h, double x, double y, double z) {
            h &= 15; double u = h < 8 ? x : y, v = h < 4 ? y : h == 12 || h == 14 ? x : z;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }
        static double lerp(double t, double a, double b) { return a + t * (b - a); }
        double noise(double x, double y, double z) {
            int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255, Z = (int) Math.floor(z) & 255;
            x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
            double u = fade(x), v = fade(y), w = fade(z);
            int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z, B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
            return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)), lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
                    lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)), lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
        }
    }
}
