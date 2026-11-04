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

    private int curX, curZ;

    /** biome of a section-local column (may be up to 16 outside, in the neighbour chunks) */
    private int biomeAt(int x, int z) {
        int ix = x < 0 ? 0 : x > 15 ? 2 : 1, iz = z < 0 ? 0 : z > 15 ? 2 : 1;
        Chunk c = around[iz * 3 + ix];
        if (c == null) return biome;
        return c.biome[(z & 15) * 16 + (x & 15)] & 255;
    }

    private int tintOf(int id, int f) { return tintOfValue(Blocks.tint[id][f], id); }

    private int tintOfValue(int t, int id) {
        if (t == Blocks.TINT_GRASS && id == 175 && (data & 7) != 2 && (data & 7) != 3) return 0xFFFFFF;
        if (t == Blocks.TINT_GRASS) return around[4] == null ? com.nova.engine.world.Biomes.GRASS[biome] : around[4].grass[(curZ & 15) * 16 + (curX & 15)];
        if (t == Blocks.TINT_FOLIAGE) {
            if (id == 18 && data == 1) return 0x619961;        // spruce
            if (id == 18 && data == 2) return 0x80A755;        // birch
            // LeafBlock::getColor: the foliage colours of the 8 columns 4 blocks around, averaged (sum / 8 per channel)
            int r = 0, g = 0, b = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int c = com.nova.engine.world.Biomes.foliageColor(biomeAt(curX + dx * 4, curZ + dz * 4));
                r += c >> 16 & 255; g += c >> 8 & 255; b += c & 255;
            }
            return (r >> 3) << 16 | (g >> 3) << 8 | (b >> 3);
        }
        if (t == Blocks.TINT_WATER) {
            // LiquidBlock::getColor (water): the 3 x 3 biome water colours around, averaged
            int r = 0, g = 0, b = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int c = com.nova.engine.world.Biomes.WATER[biomeAt(curX + dx, curZ + dz)];
                r += c >> 16 & 255; g += c >> 8 & 255; b += c & 255;
            }
            return (r / 9) << 16 | (g / 9) << 8 | (b / 9);
        }
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

    private void block(Chunk c, int x, int y, int z, int wy) {
                int id = c.blocks[Chunk.idx(x, wy, z)] & 255;
                if (id == 0 || !Blocks.exists[id]) return;
                int k = Blocks.kind[id];
                data = c.data[Chunk.idx(x, wy, z)] & 15;
                biome = c.biome[z * 16 + x] & 255;
                curX = x; curZ = z;
                if (k == Blocks.CROSS) { cross(id, x, y, z); return; }
                if (k == Blocks.MODEL) { model(id, x, wy, y, z); return; }
                int above = get(x, wy + 1, z);
                snowy = id == 2 && (above == 78 || above == 80) && Blocks.grassSnowSide >= 0;
                for (int f = 0; f < 6; f++) {
                    int[] n = NORMAL[f];
                    int nb = get(x + n[0], wy + n[1], z + n[2]);
                    boolean ins = f >= 2 && Blocks.inset[id] > 0;
                    if (k == Blocks.LIQUID) {
                        if (!liquidFace(id, nb, f)) continue;
                        liquidHeights(id, x, wy, z);
                    } else if (!ins && !visible(id, nb, k) && !(f == 1 && Blocks.height[id] < 1) && !(id == 78 && nb == 78 && f >= 2)) continue;   // top snow of other depth beside
                    face(id, f, x, y, z, 1f);
                    if (f >= 2 && Blocks.sideOverlay[id] >= 0 && !snowy) { overlay = true; face(id, f, x, y, z, 1f); overlay = false; }
                }
    }

    /** the geometry of one block (section-relative coordinates of its section), for the selection overlay */
    public int[] single(World w, int wx, int wy, int wz, int[] origin) {
        int cx = wx >> 4, cz = wz >> 4, sy = wy >> 4;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) around[(dz + 1) * 3 + dx + 1] = w.ready(cx + dx, cz + dz);
        if (around[4] == null) return new int[0];
        solid.n = 0; water.n = 0;
        bx = cx << 4; by = sy << 4; bz = cz << 4;
        origin[0] = bx; origin[1] = by; origin[2] = bz;
        block(around[4], wx & 15, wy - by, wz & 15, wy);
        int[] out = new int[solid.n + water.n];
        System.arraycopy(solid.a, 0, out, 0, solid.n);
        System.arraycopy(water.a, 0, out, solid.n, water.n);
        return out;
    }

    public Mesh build(World w, int cx, int sy, int cz, int version) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) around[(dz + 1) * 3 + dx + 1] = w.ready(cx + dx, cz + dz);
        if (around[4] == null) return null;
        solid.n = 0; water.n = 0;
        bx = cx << 4; by = sy << 4; bz = cz << 4;
        Chunk c = around[4];
        for (int y = 0; y < 16; y++) {
            int wy = by + y;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) block(c, x, y, z, wy);
        }
        Mesh m = new Mesh();
        m.world = w;
        m.cx = cx; m.sy = sy; m.cz = cz; m.version = version;
        m.solid = solid.copy(); m.solidVerts = solid.n / STRIDE;
        m.water = water.copy(); m.waterVerts = water.n / STRIDE;
        return m;
    }

    static boolean sameLiquid(int a, int b) { return (a == 8 || a == 9) ? (b == 8 || b == 9) : (a == 10 || a == 11) && (b == 10 || b == 11); }

    /** LiquidBlock::shouldRenderFace: never against the same liquid or ice; always up; else Block::shouldRenderFace
     *  with a full box = the neighbour is not solid */
    private static boolean liquidFace(int id, int nb, int f) {
        if (nb == 0 || !Blocks.exists[nb]) return true;
        if (sameLiquid(id, nb) || nb == 79) return false;
        if (f == 1) return true;
        return !Blocks.opaque[nb];
    }

    /** Material::isSolid: not air, plants, liquids, top snow, torches */
    private static boolean solidMaterial(int b) {
        if (b == 0 || !Blocks.exists[b]) return false;
        int k = Blocks.kind[b];
        return k != Blocks.CROSS && k != Blocks.LIQUID && b != 78 && !(k == Blocks.MODEL && Blocks.model[b] == Blocks.M_TORCH);
    }

    /** corner heights of the liquid top (x, z corner offsets 00 01 11 10 -> index cx * 2 + cz) */
    private final float[] liqH = new float[4];

    /** BlockTessellator::getWaterHeight for the four top corners of the liquid at x, wy, z */
    private void liquidHeights(int id, int x, int wy, int z) {
        for (int cx = 0; cx < 2; cx++) for (int cz = 0; cz < 2; cz++) {
            int px = x + cx, pz = z + cz;
            float sum = 0f; int count = 0; boolean full = false;
            for (int i = 0; i < 4 && !full; i++) {
                int bx = px - (i & 1), bz = pz - (i >> 1);
                if (sameLiquid(id, get(bx, wy + 1, bz))) { full = true; break; }
                int b = get(bx, wy, bz);
                if (sameLiquid(id, b)) {
                    int d = worldData(bx, wy, bz);
                    float h = d <= 7 ? (d + 1) * 0.11111111f : 0.11111111f;     // LiquidBlock::getHeightFromData
                    if (d == 0 || d >= 8) { sum += h * 10f; count += 10; }
                    sum += h; count++;
                } else if (!solidMaterial(b)) { sum += 1f; count++; }
            }
            liqH[cx * 2 + cz] = full ? 1f : 1f - sum / count;
        }
    }

    private static boolean visible(int id, int nb, int kind) {
        if (nb == 0 || !Blocks.exists[nb]) return true;
        // liquids: no face against any block box (farmland, slabs, glass, ice ...): coplanar faces flicker
        if (kind == Blocks.LIQUID) return Blocks.kind[nb] == Blocks.CROSS || Blocks.kind[nb] == Blocks.MODEL;
        if (Blocks.opaque[nb]) return false;
        if (nb == id) return Blocks.selfFaces[id];
        return true;
    }

    private final com.nova.engine.world.Shapes.Access access = new com.nova.engine.world.Shapes.Access() {
        public int id(int x, int y, int z) { return get(x, y, z); }
        public int data(int x, int y, int z) { return worldData(x, y, z); }
    };

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
            if (liquid) {
                if (cr[v * 3 + 1] == 1) py = y + liqH[cr[v * 3] * 2 + cr[v * 3 + 2]] - 0.001f;   // tessellateLiquid: top - 0.001
                if (f >= 2) { px -= n[0] * 0.001f; pz -= n[2] * 0.001f; }                       // sides 0.001 inside
            }
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
        if (Blocks.model[id] == Blocks.M_DOOR) {
            for (float[] b : com.nova.engine.world.Shapes.door(com.nova.engine.world.Shapes.doorComposite(access, x, wy, z)))
                box(id, x, wy, y, z, b[0], b[1], b[2], b[3], b[4], b[5]);
            return;
        }
        if (Blocks.model[id] == Blocks.M_STAIRS) {
            for (float[] b : com.nova.engine.world.Shapes.stairs(data, access, x, wy, z)) box(id, x, wy, y, z, b[0], b[1], b[2], b[3], b[4], b[5]);
            return;
        }
        float[][] bs = com.nova.engine.world.Shapes.boxes(id, data, get(x, wy, z - 1), get(x, wy, z + 1), get(x - 1, wy, z), get(x + 1, wy, z),
                get(x, wy - 1, z), worldData(x, wy - 1, z));
        for (float[] b : bs) box(id, x, wy, y, z, b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /**
     * torch: BlockTessellator::tessellateTorchInWorld / tessellateTorch (the Java renderTorchAtAngle): 4 full-tile
     * planes 1/16 from the centre (the transparent texture makes the stick), bottom shifted by (dx, dz), a cap at 10/16
     * from the middle of the tile; wall torches at +/-0.1 toward the wall, 0.2 up, leaning 0.4.
     */
    private void torchMesh(int id, int x, int y, int z) {
        float px = x, py = y, pz = z, dx = 0, dz = 0;
        switch (data) {
            case 1: px -= 0.1f; py += 0.2f; dx = -0.4f; break;
            case 2: px += 0.1f; py += 0.2f; dx = 0.4f; break;
            case 3: pz -= 0.1f; py += 0.2f; dz = -0.4f; break;
            case 4: pz += 0.1f; py += 0.2f; dz = 0.4f; break;
            default: break;
        }
        int sl = Blocks.texFor(id, data, 2) * 4;
        float u0 = uv[sl], v0 = uv[sl + 1], u1 = uv[sl + 2], v1 = uv[sl + 3], du = u1 - u0, dv = v1 - v0;
        float iu7 = u0 + du * 7 / 16f, iu9 = u0 + du * 9 / 16f, iv6 = v0 + dv * 6 / 16f, iv8 = v0 + dv * 8 / 16f;
        float iv13 = v0 + dv * 13 / 16f, iv15 = v0 + dv * 15 / 16f;
        float cx = px + 0.5f, cz = pz + 0.5f, x0 = cx - 0.5f, x1 = cx + 0.5f, z0 = cz - 0.5f, z1 = cz + 0.5f;
        float d = 0.0625f, h = 0.625f, tx = cx + dx * (1 - h), tz = cz + dz * (1 - h);
        int col = shadeCol(1f);
        float[][] q = {
                // cap
                {tx - d, py + h, tz - d, iu7, iv6}, {tx - d, py + h, tz + d, iu7, iv8}, {tx + d, py + h, tz + d, iu9, iv8}, {tx + d, py + h, tz - d, iu9, iv6},
                // bottom
                {cx + d + dx, py, cz - d + dz, iu9, iv13}, {cx + d + dx, py, cz + d + dz, iu9, iv15}, {cx - d + dx, py, cz + d + dz, iu7, iv15}, {cx - d + dx, py, cz - d + dz, iu7, iv13},
                // sides
                {cx - d, py + 1, z0, u0, v0}, {cx - d + dx, py, z0 + dz, u0, v1}, {cx - d + dx, py, z1 + dz, u1, v1}, {cx - d, py + 1, z1, u1, v0},
                {cx + d, py + 1, z1, u0, v0}, {cx + dx + d, py, z1 + dz, u0, v1}, {cx + dx + d, py, z0 + dz, u1, v1}, {cx + d, py + 1, z0, u1, v0},
                {x0, py + 1, cz + d, u0, v0}, {x0 + dx, py, cz + d + dz, u0, v1}, {x1 + dx, py, cz + d + dz, u1, v1}, {x1, py + 1, cz + d, u1, v0},
                {x1, py + 1, cz - d, u0, v0}, {x1 + dx, py, cz - d + dz, u0, v1}, {x0 + dx, py, cz - d + dz, u1, v1}, {x0, py + 1, cz - d, u1, v0}};
        for (int i = 0; i < q.length; i += 4) {                     // both windings: seen from either side
            for (int k = 0; k < 4; k++) vert(solid, q[i + k][0], q[i + k][1], q[i + k][2], q[i + k][3], q[i + k][4], col);
            for (int k = 3; k >= 0; k--) vert(solid, q[i + k][0], q[i + k][1], q[i + k][2], q[i + k][3], q[i + k][4], col);
        }
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
                {0.05f, 0, 0.05f, 0.95f, 0, 0.95f}, {0.95f, 0, 0.95f, 0.05f, 0, 0.05f},
                {0.05f, 0, 0.95f, 0.95f, 0, 0.05f}, {0.95f, 0, 0.05f, 0.05f, 0, 0.95f}};
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
