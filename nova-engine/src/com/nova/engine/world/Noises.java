package com.nova.engine.world;

/**
 * Noise of MCPE 0.14.3, ported float-exact from libminecraftpe (ImprovedNoise, PerlinNoise, SimplexNoise,
 * PerlinSimplexNoise). All arithmetic is single precision in the same operation order as the original.
 */
public final class Noises {
    private Noises() { }

    // ------------------------------------------------------------------ ImprovedNoise
    public static final class Improved {
        final float xo, yo, zo;
        final int[] p = new int[512];

        Improved(McRandom r) {
            xo = r.nextFloat() * 256f;
            yo = r.nextFloat() * 256f;
            zo = r.nextFloat() * 256f;
            for (int i = 0; i < 256; i++) p[i] = i;
            for (int i = 0; i < 256; i++) {
                int j = r.nextInt(256 - i) + i;
                int t = p[i]; p[i] = p[j]; p[j] = t;
                p[i + 256] = p[i];
            }
        }

        static float lerp(float t, float a, float b) { return a + (b - a) * t; }

        static float grad(int h, float x, float y, float z) {
            h &= 15;
            float u = h < 8 ? x : y;
            float v = h < 4 ? y : ((h & 13) == 12 ? x : z);
            return ((h & 1) != 0 ? -u : u) + ((h & 2) != 0 ? -v : v);
        }

        static float grad2(int h, float x, float z) {
            float u = (float) (1 - ((h >> 3) & 1)) * x;
            float v = (h & 15) <= 3 ? 0f : ((h & 13) == 12 ? x : z);
            return ((h & 1) != 0 ? -u : u) + ((h & 2) != 0 ? -v : v);
        }

        /** calcValues: o[0] = fade, o[1] = floor & 255; returns frac */
        static float calc(float v, float[] o) {
            int fi = (int) v;
            if (v < fi) fi--;
            float frac = v - (float) fi;
            o[1] = fi & 255;
            o[0] = (frac * frac * frac) * (10f + (-15f + frac * 6f) * frac);
            return frac;
        }

        private void blend(float[] q, float x, float y, float z, int X, int Y, int Z, float fx) {
            int A = p[X] + Y, B = p[X + 1] + Y;
            int AA = p[A] + Z, AB = p[A + 1] + Z, BA = p[B] + Z, BB = p[B + 1] + Z;
            float x1 = x - 1f, y1 = y - 1f, z1 = z - 1f;
            q[0] = lerp(fx, grad(p[AA], x, y, z), grad(p[BA], x1, y, z));
            q[1] = lerp(fx, grad(p[AB], x, y1, z), grad(p[BB], x1, y1, z));
            q[2] = lerp(fx, grad(p[AA + 1], x, y, z1), grad(p[BA + 1], x1, y, z1));
            q[3] = lerp(fx, grad(p[AB + 1], x, y1, z1), grad(p[BB + 1], x1, y1, z1));
        }

        /** _readArea: buffer[(x*zs+z)*ys+y] += noise / pow (ys == 1: 2D, y ignored, index x*zs+z) */
        void readArea(float[] buf, float px, float py, float pz, int xs, int ys, int zs,
                                   float sx, float sy, float sz, float pow) {
            float inv = 1f / pow;
            float[] o = new float[2], q = new float[4];
            if (ys == 1) {
                int idx = 0;
                for (int x = 0; x < xs; x++) {
                    float fracX = calc(xo + ((float) x + px) * sx, o);
                    int Xi = (int) o[1]; float fadeX = o[0];
                    for (int z = 0; z < zs; z++) {
                        float fracZ = calc(zo + ((float) z + pz) * sz, o);
                        int Zi = (int) o[1]; float fadeZ = o[0];
                        int A = p[p[Xi]] + Zi, B = p[p[Xi + 1]] + Zi;
                        float fx1 = fracX - 1f, fz1 = fracZ - 1f;
                        float l1 = lerp(fadeX, grad2(p[A], fracX, fracZ), grad(p[B], fx1, 0f, fracZ));
                        float l2 = lerp(fadeX, grad(p[A + 1], fracX, 0f, fz1), grad(p[B + 1], fx1, 0f, fz1));
                        float v = lerp(fadeZ, l1, l2);
                        buf[idx] = buf[idx] + v * inv;
                        idx++;
                    }
                }
                return;
            }
            int lastY = -1, base = 0;
            for (int x = 0; x < xs; x++) {
                float fracX = calc(xo + ((float) x + px) * sx, o);
                int Xi = (int) o[1]; float fadeX = o[0];
                for (int z = 0; z < zs; z++) {
                    float fracZ = calc(zo + ((float) z + pz) * sz, o);
                    int Zi = (int) o[1]; float fadeZ = o[0];
                    int idx = base;
                    for (int y = 0; y < ys; y++) {
                        float fracY = calc(yo + ((float) y + py) * sy, o);
                        int Yi = (int) o[1]; float fadeY = o[0];
                        if (y == 0 || Yi != lastY) {
                            lastY = Yi;
                            blend(q, fracX, fracY, fracZ, Xi, Yi, Zi, fadeX);
                        }
                        float v = lerp(fadeZ, lerp(fadeY, q[0], q[1]), lerp(fadeY, q[2], q[3]));
                        buf[idx] = buf[idx] + v * inv;
                        idx++;
                    }
                    base += ys;
                }
            }
        }
    }

    // ------------------------------------------------------------------ PerlinNoise
    public static final class Perlin {
        final Improved[] levels;

        Perlin(McRandom r, int n) {
            levels = new Improved[n];
            for (int i = 0; i < n; i++) levels[i] = new Improved(r);
        }

        /** 3D region, buffer cleared first */
        public void region(float[] buf, float px, float py, float pz, int xs, int ys, int zs, float sx, float sy, float sz) {
            java.util.Arrays.fill(buf, 0, xs * ys * zs, 0f);
            float pow = 1f;
            for (Improved l : levels) {
                l.readArea(buf, px, py, pz, xs, ys, zs, pow * sx, pow * sy, pow * sz, pow);
                pow *= 0.5f;
            }
        }

        /** 2D region (Vec2 overload): pos (x, 10, z), ys 1, scale (sx, 1, sz) */
        public void region2(float[] buf, float px, float pz, int xs, int zs, float sx, float sz) {
            region(buf, px, 10f, pz, xs, 1, zs, sx, 1f, sz);
        }
    }

    // ------------------------------------------------------------------ SimplexNoise
    static final int[] GRAD3 = {1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1, 0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1};
    static final float SQRT3 = (float) Math.sqrt(3.0);
    static final float F2 = 0.5f * (SQRT3 - 1f);
    static final float G2 = (3f - SQRT3) / 6f;

    public static final class Simplex {
        final float xo, yo, zo;
        final int[] p = new int[512];

        Simplex(McRandom r) {
            xo = r.nextFloat() * 256f;
            yo = r.nextFloat() * 256f;
            zo = r.nextFloat() * 256f;
            for (int i = 0; i < 256; i++) p[i] = i;
            for (int i = 0; i < 256; i++) {
                int j = r.nextInt(256 - i) + i;
                int t = p[i]; p[i] = p[j]; p[j] = t;
                p[i + 256] = p[i];
            }
        }

        float value(float x, float y) {
            float s = (y + x) * F2;
            float xs = s + x, ys = s + y;
            int i = (int) xs; if (xs <= 0f) i--;
            int j = (int) ys; if (ys <= 0f) j--;
            float t = (float) (i + j) * G2;
            float x0 = x - ((float) i - t);
            float y0 = y - ((float) j - t);
            int i1, j1;
            if (x0 > y0) { i1 = 1; j1 = 0; } else { i1 = 0; j1 = 1; }
            float x1 = (G2 - (float) i1) + x0, y1 = (G2 - (float) j1) + y0;
            float g2m = (G2 + G2) + -1f;
            float x2 = x0 + g2m, y2 = y0 + g2m;
            int ii = i & 255, jj = j & 255;
            int gi0 = p[ii + p[jj]] % 12, gi1 = p[ii + i1 + p[jj + j1]] % 12, gi2 = p[ii + 1 + p[jj + 1]] % 12;
            float n0, n1, n2;
            float t0 = 0.5f - (y0 * y0 + x0 * x0);
            if (t0 < 0) n0 = 0f;
            else { t0 *= t0; float dt = x0 * GRAD3[gi0 * 3] + y0 * GRAD3[gi0 * 3 + 1]; t0 *= t0; n0 = t0 * dt; }
            float t1 = 0.5f - (y1 * y1 + x1 * x1);
            if (t1 < 0) n1 = 0f;
            else { t1 *= t1; t1 *= t1; n1 = t1 * (x1 * GRAD3[gi1 * 3] + y1 * GRAD3[gi1 * 3 + 1]); }
            float t2 = 0.5f - (x2 * x2 + y2 * y2);
            if (t2 < 0) n2 = 0f;
            else { t2 *= t2; t2 *= t2; n2 = t2 * (x2 * GRAD3[gi2 * 3] + y2 * GRAD3[gi2 * 3 + 1]); }
            return ((n1 + n0) + n2) * 70f;
        }

        /** _add: buf[z*xs+x] += value(xo + (x+px)*sx, yo + (z+pz)*sz) * amp */
        void add(float[] buf, float px, float pz, int xs, int zs, float sx, float sz, float amp) {
            int idx = 0;
            for (int z = 0; z < zs; z++) {
                float vz = yo + ((float) z + pz) * sz;
                for (int x = 0; x < xs; x++) {
                    float vx = xo + ((float) x + px) * sx;
                    buf[idx] = buf[idx] + value(vx, vz) * amp;
                    idx++;
                }
            }
        }
    }

    public static final class PerlinSimplex {
        final Simplex[] levels;

        PerlinSimplex(McRandom r, int n) {
            levels = new Simplex[n];
            for (int i = 0; i < n; i++) levels[i] = new Simplex(r);
        }

        public void region(float[] buf, float px, float pz, int xs, int zs, float sx, float sz, float sizeScale, float powScale) {
            java.util.Arrays.fill(buf, 0, xs * zs, 0f);
            float a = 1f, b = 1f;
            for (Simplex s : levels) {
                float amp = 0.55f / b;
                float m = a * b;
                s.add(buf, px, pz, xs, zs, m * sx, m * sz, amp);
                a *= sizeScale;
                b *= powScale;
            }
        }
    }
}
