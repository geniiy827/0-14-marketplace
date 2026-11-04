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
        public World world;             // the world it was built from (dropped when the world was replaced)
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
        if (t == Blocks.TINT_GRASS && id == 175 && (data & 7) != 2 && (data & 7) != 3) return 0xFFFFFF;
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
                if (k == Blocks.MODEL) { model(id, x, wy, y, z); continue; }
                int above = get(x, wy + 1, z);
                snowy = id == 2 && (above == 78 || above == 80) && Blocks.grassSnowSide >= 0;
                for (int f = 0; f < 6; f++) {
                    int[] n = NORMAL[f];
                    int nb = get(x + n[0], wy + n[1], z + n[2]);
                    boolean ins = f >= 2 && Blocks.inset[id] > 0;
                    if (!ins && !visible(id, nb, k) && !(f == 1 && Blocks.height[id] < 1) && !(id == 78 && nb == 78 && f >= 2)) continue;   // top snow of other depth beside
                    face(id, f, x, y, z, k == Blocks.LIQUID ? (Blocks.kind[get(x, wy + 1, z)] == Blocks.LIQUID ? 1f : 0.875f) : 1f);
                    if (f >= 2 && Blocks.sideOverlay[id] >= 0 && !snowy) { overlay = true; face(id, f, x, y, z, 1f); overlay = false; }
                }
            }
        }
        Mesh m = new Mesh();
        m.world = w;
        m.cx = cx; m.sy = sy; m.cz = cz; m.version = version;
        m.solid = solid.copy(); m.solidVerts = solid.n / STRIDE;
        m.water = water.copy(); m.waterVerts = water.n / STRIDE;
        return m;
    }

    private static boolean visible(int id, int nb, int kind) {
        if (nb == 0 || !Blocks.exists[nb]) return true;
        // liquids: no face against any block box (farmland, slabs, glass, ice ...): coplanar faces flicker
        if (kind == Blocks.LIQUID) return Blocks.kind[nb] == Blocks.CROSS || Blocks.kind[nb] == Blocks.MODEL;
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

    private boolean overlay, snowy;

    private void face(int id, int f, int x, int y, int z, float top) {
        int s = (overlay ? Blocks.sideOverlay[id] : snowy && f >= 2 ? Blocks.grassSnowSide : Blocks.texFor(id, data, f)) * 4;
        float u0 = uv[s], v0 = uv[s + 1], u1 = uv[s + 2], v1 = uv[s + 3];
        int tint = overlay ? tintOfValue(Blocks.sideOverlayTint[id], id) : snowy && f >= 2 ? 0xFFFFFF : tintOf(id, f);
        float bh = id == 78 ? ((data & 7) + 1) / 8f : Blocks.height[id];       // top snow: data = layers - 1
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
        if (liquid) {                                        // water/lava seen from both sides: the reverse quad
            int base = out.n - 4 * STRIDE;
            for (int v = 3; v >= 0; v--) for (int k = 0; k < STRIDE; k++) out.add(out.a[base + v * STRIDE + k]);
        }
    }

    // ------------------------------------------------------------------ shaped blocks (stairs, fences, doors ...)
    private void model(int id, int x, int wy, int y, int z) {
        if (Blocks.model[id] == Blocks.M_TORCH) { torchMesh(id, x, y, z); return; }
        float[][] bs = com.nova.engine.world.Shapes.boxes(id, data, get(x, wy, z - 1), get(x, wy, z + 1), get(x - 1, wy, z), get(x + 1, wy, z),
                get(x, wy - 1, z), worldData(x, wy - 1, z));
        for (float[] b : bs) box(id, x, wy, y, z, b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** torch: a 2/16 stick from the middle of the tile, standing or leaning out of the wall (data 1..4) */
    private void torchMesh(int id, int x, int y, int z) {
        float bx = 0.5f, bz = 0.5f, by = 0f, lx = 0, lz = 0;
        switch (data) {
            case 1: bx = 0.06f; lx = 0.25f; by = 0.2f; break;
            case 2: bx = 0.94f; lx = -0.25f; by = 0.2f; break;
            case 3: bz = 0.06f; lz = 0.25f; by = 0.2f; break;
            case 4: bz = 0.94f; lz = -0.25f; by = 0.2f; break;
            default: break;
        }
        float h = 10f / 16f, hw = 1f / 16f;
        int sl = Blocks.texFor(id, data, 2) * 4;
        float u0 = uv[sl], v0 = uv[sl + 1], du = uv[sl + 2] - u0, dv = uv[sl + 3] - v0;
        float su0 = u0 + du * 7 / 16f, su1 = u0 + du * 9 / 16f, sv0 = v0 + dv * 6 / 16f, sv1 = v0 + dv;
        // bottom corners (b) and top corners (t), ccw from -x-z
        float[][] c = new float[8][];
        float[][] o = {{-hw, -hw}, {hw, -hw}, {hw, hw}, {-hw, hw}};
        for (int i = 0; i < 4; i++) {
            c[i] = new float[]{x + bx + o[i][0], y + by, z + bz + o[i][1]};
            c[i + 4] = new float[]{x + bx + lx + o[i][0], y + by + h, z + bz + lz + o[i][1]};
        }
        int[][] sides = {{1, 0, 4, 5}, {3, 2, 6, 7}, {0, 3, 7, 4}, {2, 1, 5, 6}};     // north, south, west, east
        float[] shade = {0.8f, 0.8f, 0.62f, 0.62f};
        for (int f = 0; f < 4; f++) {
            int col = shadeCol(shade[f]);
            int[] q = sides[f];
            vert(solid, c[q[0]][0], c[q[0]][1], c[q[0]][2], su0, sv1, col);
            vert(solid, c[q[1]][0], c[q[1]][1], c[q[1]][2], su1, sv1, col);
            vert(solid, c[q[2]][0], c[q[2]][1], c[q[2]][2], su1, sv0, col);
            vert(solid, c[q[3]][0], c[q[3]][1], c[q[3]][2], su0, sv0, col);
        }
        float tv0 = v0 + dv * 6 / 16f, tv1 = v0 + dv * 8 / 16f;
        int col = shadeCol(1f);
        vert(solid, c[4][0], c[4][1], c[4][2], su0, tv0, col);
        vert(solid, c[7][0], c[7][1], c[7][2], su0, tv1, col);
        vert(solid, c[6][0], c[6][1], c[6][2], su1, tv1, col);
        vert(solid, c[5][0], c[5][1], c[5][2], su1, tv0, col);
    }

    private static int shadeCol(float l) { int v = (int) (255 * l); return v | v << 8 | v << 16 | 255 << 24; }

    private int worldData(int x, int wy, int z) {
        int ix = x < 0 ? 0 : x > 15 ? 2 : 1, iz = z < 0 ? 0 : z > 15 ? 2 : 1;
        Chunk c = around[iz * 3 + ix];
        if (c == null || wy < 0 || wy >= Chunk.H) return 0;
        return c.data(x & 15, wy, z & 15);
    }

    /** an axis aligned part of a block: faces at the block border are culled by opaque neighbours, UV = the box area */
    private void box(int id, int x, int wy, int y, int z, float x0, float y0, float z0, float x1, float y1, float z1) {
        Buf out = Blocks.layer[id] == Blocks.TRANSLUCENT ? water : solid;
        for (int f = 0; f < 6; f++) {
            int[] n = NORMAL[f];
            boolean border = (f == 0 && y0 == 0) || (f == 1 && y1 == 1) || (f == 2 && z0 == 0) || (f == 3 && z1 == 1) || (f == 4 && x0 == 0) || (f == 5 && x1 == 1);
            if (border) {
                int nb = get(x + n[0], wy + n[1], z + n[2]);
                if (nb != 0 && Blocks.exists[nb] && Blocks.opaque[nb]) continue;
            }
            int sl = Blocks.texFor(id, data, f) * 4;
            float u0 = uv[sl], v0 = uv[sl + 1], du = uv[sl + 2] - u0, dv = uv[sl + 3] - v0;
            int tint = tintOf(id, f);
            float l = SHADE[f];
            int col = (int) (((tint >> 16) & 255) * l) | (int) (((tint >> 8) & 255) * l) << 8 | (int) ((tint & 255) * l) << 16 | 255 << 24;
            int[] cr = CORNERS[f];
            for (int v = 0; v < 4; v++) {
                float cx = cr[v * 3] == 0 ? x0 : x1, cy = cr[v * 3 + 1] == 0 ? y0 : y1, cz = cr[v * 3 + 2] == 0 ? z0 : z1;
                float tu, tv;
                if (f <= 1) { tu = cx; tv = cz; }
                else if (f <= 3) { tu = f == 2 ? 1 - cx : cx; tv = 1 - cy; }
                else { tu = f == 4 ? cz : 1 - cz; tv = 1 - cy; }
                vert(out, x + cx, y + cy, z + cz, u0 + du * tu, v0 + dv * tv, col);
            }
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
