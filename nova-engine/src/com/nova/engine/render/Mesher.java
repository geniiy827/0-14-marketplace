package com.nova.engine.render;

import com.nova.engine.world.Blocks;
import com.nova.engine.world.Chunk;
import com.nova.engine.world.World;

/**
 * Builds the mesh of one 16^3 section (worker thread). Vertex = 6 ints:
 * x y z (float bits, block units relative to the section origin), u v (float bits), colour RGBA8.
 * Quads only (4 vertices); faces CCW seen from outside; smooth ambient occlusion per vertex.
 */
public final class Mesher {
    public static final int STRIDE = 6;

    /** result of one build */
    public static final class Mesh {
        public int cx, sy, cz, version;
        public int[] solid, water;      // vertex data; quads of 4 vertices
        public int solidVerts, waterVerts;
    }

    static final class Buf {
        int[] a = new int[1 << 16];
        int n;
        void add(int v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; }
        int[] copy() { return java.util.Arrays.copyOf(a, n); }
    }

    private final Buf solid = new Buf(), water = new Buf();
    private final Chunk[] around = new Chunk[9];
    private final float[] uv;
    private int bx, by, bz;               // section origin (world)
    private int data, biome;              // of the block being meshed

    private int tintOf(int id, int f) { return tintOfValue(Blocks.tint[id][f], id); }

    private int tintOfValue(int t, int id) {
        if (t == Blocks.TINT_GRASS) return com.nova.engine.world.Biomes.GRASS[biome];
        if (t == Blocks.TINT_FOLIAGE) {
            if (id == 18 && data == 1) return 0x619961;        // spruce
            if (id == 18 && data == 2) return 0x80A755;        // birch
            return com.nova.engine.world.Biomes.FOLIAGE[biome];
        }
        if (t == Blocks.TINT_WATER) return com.nova.engine.world.Biomes.WATER[biome];
        return t;
    }

    public Mesher(float[] slotUv) { this.uv = slotUv; }

    // face tables: corner offsets (4 x xyz), normal, tangent axes
    static final int[][] CORNERS = {
            {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},      // down
            {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},      // up
            {1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0},      // north -z
            {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},      // south +z
            {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},      // west -x
            {1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1},      // east +x
    };
    static final int[][] NORMAL = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    static final float[] SHADE = {0.5f, 1.0f, 0.8f, 0.8f, 0.62f, 0.62f};
    static final float[] AO = {0.5f, 0.68f, 0.84f, 1.0f};

    public Mesh build(World w, int cx, int sy, int cz, int version) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) around[(dz + 1) * 3 + dx + 1] = w.ready(cx + dx, cz + dz);
        if (around[4] == null) return null;
        solid.n = 0; water.n = 0;
        bx = cx << 4; by = sy << 4; bz = cz << 4;
        Chunk c = around[4];
        for (int y = 0; y < 16; y++) {
            int wy = by + y;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int id = c.blocks[Chunk.idx(x, wy, z)] & 255;
                if (id == 0 || !Blocks.exists[id]) continue;
                int k = Blocks.kind[id];
                data = c.data[Chunk.idx(x, wy, z)] & 15;
                biome = c.biome[z * 16 + x] & 255;
                if (k == Blocks.CROSS) { cross(id, x, y, z); continue; }
                for (int f = 0; f < 6; f++) {
                    int[] n = NORMAL[f];
                    int nb = get(x + n[0], wy + n[1], z + n[2]);
                    boolean ins = f >= 2 && Blocks.inset[id] > 0;
                    if (!ins && !visible(id, nb, k) && !(f == 1 && Blocks.height[id] < 1)) continue;
                    face(id, f, x, y, z, k == Blocks.LIQUID ? (Blocks.kind[get(x, wy + 1, z)] == Blocks.LIQUID ? 1f : 0.875f) : 1f);
                    if (f >= 2 && Blocks.sideOverlay[id] >= 0) { overlay = true; face(id, f, x, y, z, 1f); overlay = false; }
                }
            }
        }
        Mesh m = new Mesh();
        m.cx = cx; m.sy = sy; m.cz = cz; m.version = version;
        m.solid = solid.copy(); m.solidVerts = solid.n / STRIDE;
        m.water = water.copy(); m.waterVerts = water.n / STRIDE;
        return m;
    }

    private static boolean visible(int id, int nb, int kind) {
        if (nb == 0 || !Blocks.exists[nb]) return true;
        if (kind == Blocks.LIQUID) return Blocks.kind[nb] != Blocks.LIQUID && !Blocks.opaque[nb];
        if (Blocks.opaque[nb]) return false;
        if (nb == id) return Blocks.selfFaces[id];
        return true;
    }

    /** block at section-local x/z (may be outside 0..15 by one) and world y */
    private int get(int x, int wy, int z) {
        if (wy < 0) return 1;
        if (wy >= Chunk.H) return 0;
        int ix = x < 0 ? 0 : x > 15 ? 2 : 1, iz = z < 0 ? 0 : z > 15 ? 2 : 1;
        Chunk c = around[iz * 3 + ix];
        if (c == null) return 0;
        return c.blocks[Chunk.idx(x & 15, wy, z & 15)] & 255;
    }

    private boolean occludes(int x, int wy, int z) {
        int b = get(x, wy, z);
        return b != 0 && Blocks.opaque[b];
    }

    private boolean overlay;

    private void face(int id, int f, int x, int y, int z, float top) {
        int s = (overlay ? Blocks.sideOverlay[id] : Blocks.texFor(id, data, f)) * 4;
        float u0 = uv[s], v0 = uv[s + 1], u1 = uv[s + 2], v1 = uv[s + 3];
        int tint = overlay ? tintOfValue(Blocks.sideOverlayTint[id], id) : tintOf(id, f);
        float bh = Blocks.height[id];
        float tr = ((tint >> 16) & 255) / 255f, tg = ((tint >> 8) & 255) / 255f, tb = (tint & 255) / 255f;
        boolean liquid = Blocks.kind[id] == Blocks.LIQUID;
        Buf out = Blocks.layer[id] == Blocks.TRANSLUCENT ? water : solid;
        int[] cr = CORNERS[f], n = NORMAL[f];
        int wy = by + y;
        float ins = f >= 2 ? Blocks.inset[id] : 0;
        // per-vertex AO
        int[] ao = new int[4];
        for (int v = 0; v < 4; v++) {
            if (liquid) { ao[v] = 3; continue; }
            int ox = cr[v * 3], oy = cr[v * 3 + 1], oz = cr[v * 3 + 2];
            int ax = x + n[0], ay = wy + n[1], az = z + n[2];
            // tangent directions: the two axes that are not the normal axis
            int dx = n[0] != 0 ? 0 : ox * 2 - 1, dy = n[1] != 0 ? 0 : oy * 2 - 1, dz = n[2] != 0 ? 0 : oz * 2 - 1;
            boolean s1, s2, cn;
            if (n[0] != 0) { s1 = occludes(ax, ay + dy, az); s2 = occludes(ax, ay, az + dz); cn = occludes(ax, ay + dy, az + dz); }
            else if (n[1] != 0) { s1 = occludes(ax + dx, ay, az); s2 = occludes(ax, ay, az + dz); cn = occludes(ax + dx, ay, az + dz); }
            else { s1 = occludes(ax + dx, ay, az); s2 = occludes(ax, ay + dy, az); cn = occludes(ax + dx, ay + dy, az); }
            ao[v] = s1 && s2 ? 0 : 3 - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (cn ? 1 : 0));
        }
        // texture corners per vertex
        float[] tu = new float[4], tv = new float[4];
        float vt = bh < 1 ? v1 - (v1 - v0) * bh : v0;      // partial blocks: the lower part of the side texture
        if (f >= 2) { tu[0] = u0; tv[0] = v1; tu[1] = u1; tv[1] = v1; tu[2] = u1; tv[2] = vt; tu[3] = u0; tv[3] = vt; }
        else { tu[0] = u0; tv[0] = v0; tu[1] = u0; tv[1] = v1; tu[2] = u1; tv[2] = v1; tu[3] = u1; tv[3] = v0; }
        // flip the diagonal when it makes the AO gradient symmetric
        int start = ao[0] + ao[2] < ao[1] + ao[3] ? 1 : 0;
        int alpha = liquid && Blocks.layer[id] == Blocks.TRANSLUCENT ? 190 : 255;
        for (int i = 0; i < 4; i++) {
            int v = (start + i) & 3;
            float px = x + cr[v * 3], py = y + cr[v * 3 + 1], pz = z + cr[v * 3 + 2];
            if (ins > 0) { px -= n[0] * ins; pz -= n[2] * ins; }
            if (liquid && cr[v * 3 + 1] == 1) py = y + top;
            else if (bh < 1 && cr[v * 3 + 1] == 1) py = y + bh;
            float l = SHADE[f] * AO[ao[v]];
            int r = (int) (tr * l * 255), g = (int) (tg * l * 255), b = (int) (tb * l * 255);
            out.add(Float.floatToRawIntBits(px)); out.add(Float.floatToRawIntBits(py)); out.add(Float.floatToRawIntBits(pz));
            out.add(Float.floatToRawIntBits(tu[v])); out.add(Float.floatToRawIntBits(tv[v]));
            out.add(r | g << 8 | b << 16 | alpha << 24);
        }
    }

    /** plants: two crossed quads, each seen from both sides */
    private void cross(int id, int x, int y, int z) {
        int s = Blocks.texFor(id, data, 2) * 4;
        float u0 = uv[s], v0 = uv[s + 1], u1 = uv[s + 2], v1 = uv[s + 3];
        int tint = tintOf(id, 2);
        int col = (tint >> 16 & 255) | (tint >> 8 & 255) << 8 | (tint & 255) << 16 | 255 << 24;
        float[][] q = {
                {0.15f, 0, 0.15f, 0.85f, 0, 0.85f}, {0.85f, 0, 0.85f, 0.15f, 0, 0.15f},
                {0.15f, 0, 0.85f, 0.85f, 0, 0.15f}, {0.85f, 0, 0.15f, 0.15f, 0, 0.85f}};
        for (float[] p : q) {
            float ax = x + p[0], az = z + p[2], bxx = x + p[3], bzz = z + p[5];
            vert(solid, ax, y, az, u0, v1, col);
            vert(solid, bxx, y, bzz, u1, v1, col);
            vert(solid, bxx, y + 1, bzz, u1, v0, col);
            vert(solid, ax, y + 1, az, u0, v0, col);
        }
    }

    private static void vert(Buf o, float x, float y, float z, float u, float v, int col) {
        o.add(Float.floatToRawIntBits(x)); o.add(Float.floatToRawIntBits(y)); o.add(Float.floatToRawIntBits(z));
        o.add(Float.floatToRawIntBits(u)); o.add(Float.floatToRawIntBits(v)); o.add(col);
    }
}
