package com.nova.engine.world;

/**
 * Block registry (ids 0..255). The engine knows nothing about concrete blocks: everything here is filled by mods,
 * the vanilla content included (mod/CoreContent). Plain arrays: the mesher reads them from worker threads.
 */
public final class Blocks {
    public static final int MAX = 256;
    public static final int AIR = 0;

    /** render kinds */
    public static final int CUBE = 0, CROSS = 1, LIQUID = 2;
    /** side faces pulled in by this much (cactus 1/16); such faces are never culled */
    public static final float[] inset = new float[MAX];
    /** layers */
    public static final int OPAQUE = 0, CUTOUT = 1, TRANSLUCENT = 2;

    public static final String[] name = new String[MAX];
    public static final boolean[] exists = new boolean[MAX];
    /** occludes neighbours' faces */
    public static final boolean[] opaque = new boolean[MAX];
    /** has a collision box */
    public static final boolean[] solid = new boolean[MAX];
    public static final int[] kind = new int[MAX];
    public static final int[] layer = new int[MAX];
    /** texture slot per face: 0 down, 1 up, 2 north(-z), 3 south(+z), 4 west(-x), 5 east(+x) */
    public static final int[][] tex = new int[MAX][6];
    /** tint (0xRRGGBB, 0xFFFFFF = none) per face */
    public static final int[][] tint = new int[MAX][6];
    /** draw faces against the same block (leaves) */
    public static final boolean[] selfFaces = new boolean[MAX];
    public static final int[] light = new int[MAX];
    /** height of the box (snow layer 0.125); cube faces only */
    public static final float[] height = new float[MAX];
    /** optional textures per data value: texData[id][data][face] (null = same for all data) */
    public static final int[][][] texData = new int[MAX][][];
    /** special tints: follow the biome colour of the column */
    public static final int TINT_GRASS = -2, TINT_FOLIAGE = -3, TINT_WATER = -4;

    private Blocks() { }

    /** registers (or replaces: mods may override vanilla) a block; returns a builder */
    public static Def register(int id, String n) {
        if (id <= 0 || id >= MAX) throw new IllegalArgumentException("block id " + id);
        name[id] = n;
        exists[id] = true;
        opaque[id] = true;
        solid[id] = true;
        kind[id] = CUBE;
        layer[id] = OPAQUE;
        selfFaces[id] = false;
        light[id] = 0;
        height[id] = 1;
        texData[id] = null;
        for (int f = 0; f < 6; f++) { tex[id][f] = 0; tint[id][f] = 0xFFFFFF; }
        return new Def(id);
    }

    public static int texFor(int id, int data, int face) {
        int[][] t = texData[id];
        if (t != null && data < t.length && t[data] != null) return t[data][face];
        return tex[id][face];
    }

    public static int byName(String n) {
        for (int i = 1; i < MAX; i++) if (exists[i] && n.equals(name[i])) return i;
        return -1;
    }

    public static final class Def {
        public final int id;
        Def(int id) { this.id = id; }
        public Def tex(int all) { for (int f = 0; f < 6; f++) tex[id][f] = all; return this; }
        public Def tex(int top, int bottom, int side) {
            tex[id][0] = bottom; tex[id][1] = top;
            for (int f = 2; f < 6; f++) tex[id][f] = side;
            return this;
        }
        public Def tint(int rgb) { for (int f = 0; f < 6; f++) tint[id][f] = rgb; return this; }
        public Def tintTop(int rgb) { tint[id][1] = rgb; return this; }
        public Def inset(float v) { inset[id] = v; opaque[id] = false; return this; }
        public Def cutout() { layer[id] = CUTOUT; opaque[id] = false; return this; }
        public Def translucent() { layer[id] = TRANSLUCENT; opaque[id] = false; return this; }
        public Def selfFaces() { selfFaces[id] = true; return this; }
        public Def cross() { kind[id] = CROSS; layer[id] = CUTOUT; opaque[id] = false; solid[id] = false; return this; }
        public Def liquid() { kind[id] = LIQUID; layer[id] = TRANSLUCENT; opaque[id] = false; solid[id] = false; return this; }
        public Def light(int l) { light[id] = l; return this; }
        public Def height(float h) { height[id] = h; opaque[id] = false; return this; }
        /** textures for one data value: all six faces */
        public Def data(int d, int all) { return data(d, all, all, all); }
        public Def data(int d, int top, int bottom, int side) {
            if (texData[id] == null || texData[id].length <= d) {
                int[][] n = new int[Math.max(d + 1, 4)][];
                if (texData[id] != null) System.arraycopy(texData[id], 0, n, 0, texData[id].length);
                texData[id] = n;
            }
            texData[id][d] = new int[]{bottom, top, side, side, side, side};
            return this;
        }
        public Def notSolid() { solid[id] = false; return this; }
    }
}
