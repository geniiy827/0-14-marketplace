package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.blocks.PeBlocks;

import java.util.*;

/** «Блок для заливки»: один блок или смесь (случайная, градиент, шум, шахматка, плитка буфера). */
public class Pattern {
    public enum Kind { SINGLE("Блок"), RANDOM("Случайная смесь"), GRADIENT("Градиент"), NOISE("Шум"), CHECKER("Шахматка"), CLIPBOARD("Как в буфере");
        public final String ru; Kind(String ru) { this.ru = ru; } }

    public Kind kind = Kind.SINGLE;
    public String name = "";
    public final List<int[]> entries = new ArrayList<>(); // {state, weight}
    public int axis = 1;
    public boolean dither = true;
    public double scale = 8;
    public long seed = 1;
    public int step = 1;
    public transient int gradMin, gradMax;
    public transient Clipboard clipboard;

    public static Pattern single(int state) { Pattern p = new Pattern(); p.entries.add(new int[]{state, 100}); return p; }

    public int first() { return entries.isEmpty() ? 0 : entries.get(0)[0]; }

    public void normalize() {
        int total = 0;
        for (int[] e : entries) total += Math.max(0, e[1]);
        if (total <= 0) { for (int[] e : entries) e[1] = 100 / Math.max(1, entries.size()); return; }
        for (int[] e : entries) e[1] = Math.round(e[1] * 100f / total);
    }

    private static long mix(long x) { x ^= x >>> 33; x *= 0xff51afd7ed558ccdL; x ^= x >>> 33; x *= 0xc4ceb9fe1a85ec53L; x ^= x >>> 33; return x; }
    private double rnd(int x, int y, int z) { long h = mix(seed * 0x9E3779B97F4A7C15L + x * 73856093L + y * 19349663L + z * 83492791L); return (h >>> 11) / (double) (1L << 53); }

    public int apply(int x, int y, int z) {
        if (entries.isEmpty()) return 0;
        switch (kind) {
            case SINGLE: return entries.get(0)[0];
            case RANDOM: {
                int total = 0;
                for (int[] e : entries) total += Math.max(0, e[1]);
                if (total <= 0) return entries.get(0)[0];
                double r = rnd(x, y, z) * total;
                for (int[] e : entries) { r -= Math.max(0, e[1]); if (r < 0) return e[0]; }
                return entries.get(entries.size() - 1)[0];
            }
            case GRADIENT: {
                int c = axis == 0 ? x : axis == 2 ? z : y;
                double t = gradMax > gradMin ? (c - gradMin) / (double) (gradMax - gradMin) : 0;
                double f = t * (entries.size() - 1);
                if (dither) f += (rnd(x, y, z) - 0.5);
                int i = (int) Math.round(Math.max(0, Math.min(entries.size() - 1, f)));
                return entries.get(i)[0];
            }
            case NOISE: {
                double n = (Noise.perlin(x / scale, y / scale, z / scale, seed) + 1) / 2; // 0..1
                int i = (int) Math.min(entries.size() - 1, Math.floor(n * entries.size()));
                return entries.get(Math.max(0, i))[0];
            }
            case CHECKER: {
                int s = Math.max(1, step);
                int k = Math.floorDiv(x, s) + Math.floorDiv(y, s) + Math.floorDiv(z, s);
                return entries.get(Math.floorMod(k, Math.min(2, entries.size())))[0];
            }
            case CLIPBOARD: {
                Clipboard c = clipboard;
                if (c == null || c.w == 0) return entries.get(0)[0];
                int v = c.get(Math.floorMod(x, c.w), Math.floorMod(y, c.h), Math.floorMod(z, c.l));
                return v == Clipboard.NONE ? 0 : v;
            }
        }
        return entries.get(0)[0];
    }

    public String describe() {
        PeBlocks t = PeBlocks.get();
        if (kind == Kind.SINGLE || entries.size() == 1) return t.shortName(first());
        StringBuilder sb = new StringBuilder(kind.ru).append(": ");
        for (int i = 0; i < entries.size() && i < 3; i++) { if (i > 0) sb.append(", "); sb.append(t.shortName(entries.get(i)[0])); if (kind == Kind.RANDOM) sb.append(' ').append(entries.get(i)[1]).append('%'); }
        if (entries.size() > 3) sb.append("…");
        return sb.toString();
    }

    public Pattern copy() {
        Pattern p = new Pattern();
        p.kind = kind; p.name = name; p.axis = axis; p.dither = dither; p.scale = scale; p.seed = seed; p.step = step;
        for (int[] e : entries) p.entries.add(e.clone());
        p.clipboard = clipboard;
        return p;
    }

    /** Классический шум Перлина (3D). */
    public static final class Noise {
        private static final int[] P = new int[512];
        static {
            int[] p = new int[256];
            for (int i = 0; i < 256; i++) p[i] = i;
            Random r = new Random(12345);
            for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = p[i]; p[i] = p[j]; p[j] = t; }
            for (int i = 0; i < 512; i++) P[i] = p[i & 255];
        }
        public static double perlin(double x, double y, double z, long seed) {
            x += (seed & 1023) * 17.13; z += ((seed >> 10) & 1023) * 11.7;
            int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255, Z = (int) Math.floor(z) & 255;
            x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
            double u = fade(x), v = fade(y), w = fade(z);
            int A = P[X] + Y, AA = P[A] + Z, AB = P[A + 1] + Z, B = P[X + 1] + Y, BA = P[B] + Z, BB = P[B + 1] + Z;
            return lerp(w, lerp(v, lerp(u, grad(P[AA], x, y, z), grad(P[BA], x - 1, y, z)), lerp(u, grad(P[AB], x, y - 1, z), grad(P[BB], x - 1, y - 1, z))),
                    lerp(v, lerp(u, grad(P[AA + 1], x, y, z - 1), grad(P[BA + 1], x - 1, y, z - 1)), lerp(u, grad(P[AB + 1], x, y - 1, z - 1), grad(P[BB + 1], x - 1, y - 1, z - 1))));
        }
        static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
        static double lerp(double t, double a, double b) { return a + t * (b - a); }
        static double grad(int h, double x, double y, double z) {
            h &= 15; double u = h < 8 ? x : y, v = h < 4 ? y : h == 12 || h == 14 ? x : z;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }
    }
}
