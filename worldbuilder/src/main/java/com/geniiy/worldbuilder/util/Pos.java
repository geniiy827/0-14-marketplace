package com.geniiy.worldbuilder.util;

/** Упаковка координат блока в long: x и z — 26 бит со знаком, y — 8 бит. */
public final class Pos {
    private Pos() {}
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 34) | ((long) (z & 0x3FFFFFF) << 8) | (y & 0xFF);
    }
    public static int x(long p) { return ((int) (p >>> 34)) << 6 >> 6; }
    public static int z(long p) { return ((int) ((p >>> 8) & 0x3FFFFFF)) << 6 >> 6; }
    public static int y(long p) { return (int) (p & 0xFF); }
    public static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    public static int ckX(long k) { return (int) (k >> 32); }
    public static int ckZ(long k) { return (int) k; }
    public static long colKey(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
}
