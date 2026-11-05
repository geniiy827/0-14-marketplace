package com.nova.engine.world;

/**
 * Light of 0.14.3 (LightLayer SKY / BLOCK, Brightness 0..15), ported from the code:
 *  - per block tables (Block::mLightEmission, mLightBlock, mTranslucency, mSolid) dumped by running
 *    Block::initBlocks of libminecraftpe.so in an emulator (tools/emu_blocks.py)
 *  - LevelChunk::recalcHeightmap: height = 1 + the top block with mLightBlock != 0; sky light filled down each
 *    column from 15, minus mLightBlock of every block, stopping at 0
 *  - LightUpdate::update rule: lb = max(1, mLightBlock); emission = (SKY: canSeeSky ? 15 : 0, BLOCK: mLightEmission);
 *    light = lb >= 15 && emission == 0 ? 0 : max(emission, max(6 neighbours) - lb)
 *  The fixed point of that rule is computed with a flood fill (spread) and, after an edit, by recomputing the
 *  region the edit can reach (relight), the way the queued LightUpdates of Level::updateLights end up.
 */
public final class Light {
    private Light() { }

    public static final int[] EMIT = {
            0,0,0,0,0,0,0,0,0,0,15,15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,14,15,
            3,0,0,0,0,0,0,0,0,0,13,0,0,0,0,0,0,0,0,0,0,0,9,0,7,0,0,0,0,0,0,0,0,0,0,0,0,15,11,15,0,0,7,0,0,0,0,0,0,0,0,0,
            0,0,0,0,0,0,0,0,0,0,0,0,12,0,0,0,0,0,0,0,15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,7,0,0,0,0,0,0,
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,13,0,0,0,0,0,0,0,0,0};
    public static final int[] LB = {
            0,15,15,15,15,15,0,15,3,3,15,15,15,15,15,15,15,15,1,15,0,15,15,15,15,15,0,0,0,15,1,0,0,15,15,15,15,0,0,0,0,
            15,15,15,0,15,15,15,15,15,0,0,0,0,0,0,15,15,15,0,0,15,15,0,0,0,0,0,0,0,0,0,0,15,15,0,0,0,0,3,15,0,15,0,15,0,
            15,15,15,15,0,15,0,0,0,15,0,15,15,15,15,0,0,15,0,0,0,0,0,0,15,0,15,0,0,0,0,3,3,15,0,15,15,15,15,15,0,0,0,15,
            15,0,0,15,0,0,0,15,15,0,0,0,0,0,0,3,0,0,0,0,0,0,15,15,3,15,0,15,0,15,15,1,15,0,0,0,15,0,15,15,15,0,15,15,15,
            0,15,15,0,15,0,15,0,0,0,0,0,0,15,15,15,15,15,0,0,0,0,0,0,0,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,
            15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,0,15,15,15,15,15,15,15,
            15,15,15,15};
    /** mTranslucency x 100 */
    public static final int[] TRANS = {
            100,0,0,0,0,0,100,0,100,100,100,100,0,0,0,0,0,0,50,0,100,0,0,0,0,0,10,100,100,100,80,100,100,100,100,80,100,
            100,100,100,100,0,0,0,80,0,0,0,0,0,100,100,0,80,50,80,0,0,0,100,0,0,0,80,80,100,100,80,80,100,20,80,20,0,0,
            100,100,100,89,0,0,50,0,100,100,80,0,0,0,100,0,0,80,100,100,80,80,0,0,0,0,80,100,0,100,100,100,80,80,80,0,
            100,0,80,80,100,0,80,80,100,80,0,100,100,100,0,100,100,80,0,100,100,100,0,80,80,80,100,100,80,50,100,100,
            100,100,80,50,20,20,100,100,0,0,0,80,0,80,0,80,0,100,50,0,80,80,80,100,80,100,100,0,89,0,0,0,100,100,100,0,
            0,80,0,80,80,80,80,80,80,100,100,100,100,100,80,80,80,80,80,0,100,100,100,100,100,100,100,100,100,100,100,
            100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,
            100,100,100,100,100,100,0,100,0,0,0,0,0,100,100,100,100,100,0};
    public static final int[] SOLID = {
            0,1,1,1,1,1,0,1,0,0,0,0,1,1,1,1,1,1,0,1,0,1,1,1,1,1,0,0,0,1,0,0,0,1,1,1,1,0,0,0,0,1,1,1,0,1,1,1,1,1,0,0,0,0,
            0,0,1,1,1,0,0,1,1,0,0,0,0,0,0,0,0,0,0,1,1,0,0,0,0,0,1,0,1,0,1,0,1,1,1,1,0,1,0,0,0,0,0,1,1,1,1,0,0,1,0,0,0,0,
            0,0,1,0,1,0,0,0,0,0,0,1,0,1,1,1,1,1,0,0,0,1,1,0,0,1,0,0,0,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,0,1,0,1,0,1,1,0,
            1,0,0,0,1,0,1,1,1,0,1,1,1,0,1,1,0,1,0,1,0,0,0,0,0,0,1,1,1,1,1,0,0,0,0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,
            1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,1,1,1,1,1,1,1,1,1,1,1};

    static final ThreadLocal<java.util.HashSet<Long>> TOUCHED = new ThreadLocal<java.util.HashSet<Long>>() {
        protected java.util.HashSet<Long> initialValue() { return new java.util.HashSet<Long>(); }
    };

    static long skey(int cx, int cz, int s) { return ((long) (cx & 0xFFFFFF) << 36) | ((long) (cz & 0xFFFFFF) << 8) | s; }

    /** bump every section touched since the last flush once (no rebuild storm, no half-lit meshes) */
    public static void flush(World w) {
        java.util.HashSet<Long> t = TOUCHED.get();
        for (long k : t) w.touch((int) (k >> 36) << 8 >> 8, (int) (k >> 8) << 8 >> 8, (int) (k & 0xFF));
        t.clear();
    }

    static int cost(int id) { int l = LB[id]; return l == 0 ? 1 : l; }

    // ------------------------------------------------------------------ chunk creation
    /** LevelChunk::recalcHeightmap + block light sources (setBrightness(BLOCK, pos, emission)) */
    public static void fill(Chunk c) {
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) column(c, x, z);
        byte[] b = c.blocks, l = c.light;
        for (int i = 0; i < b.length; i++) l[i] = (byte) ((l[i] & 0xF0) | EMIT[b[i] & 255]);
    }

    /** heightmap and sky fill of one column */
    static void column(Chunk c, int x, int z) {
        int y = Chunk.H - 1;
        while (y >= 0 && LB[c.blocks[Chunk.idx(x, y, z)] & 255] == 0) y--;
        c.height[z * 16 + x] = (byte) (y + 1);
        int br = 15;
        for (y = Chunk.H - 1; y >= 0; y--) {
            int i = Chunk.idx(x, y, z);
            if (br > 0) {
                br -= LB[c.blocks[i] & 255];
                if (br < 0) br = 0;
            }
            c.light(0, i, br);
        }
    }

    // ------------------------------------------------------------------ flood fill
    private static final ThreadLocal<int[]> QUEUE = new ThreadLocal<int[]>();

    private static Chunk at(World w, Chunk own, int cx, int cz) {
        if (own != null && own.cx == cx && own.cz == cz) return own;
        Chunk c = w.chunk(cx, cz);
        return c != null && c.lit >= 1 ? c : null;
    }

    /**
     * spread the light of chunk c into its neighbours (and theirs into c): every cell of c plus the cells just
     * outside its border are the seeds; values only grow (sources never disappear while generating)
     */
    public static void spread(World w, Chunk c) {
        for (int layer = 0; layer < 2; layer++) {
            int[] q = queue(); int head = 0, tail = 0;
            int x0 = c.cx << 4, z0 = c.cz << 4;
            // above every column top of the area all is 15 / nothing changes: seed only up to there
            int top = 0;
            for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) {
                Chunk k = (x < 0 || x > 15 || z < 0 || z > 15) ? at(w, c, (x0 + x) >> 4, (z0 + z) >> 4) : c;
                if (k != null) top = Math.max(top, k.height[((z0 + z) & 15) * 16 + ((x0 + x) & 15)] & 255);
            }
            int ymax = layer == 0 ? Math.min(Chunk.H - 1, top + 1) : Chunk.H - 1;
            for (int y = 0; y <= ymax; y++) for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) {
                Chunk k = (x < 0 || x > 15 || z < 0 || z > 15) ? at(w, c, (x0 + x) >> 4, (z0 + z) >> 4) : c;
                if (k == null) continue;
                int v = k.light(layer, Chunk.idx((x0 + x) & 15, y, (z0 + z) & 15));
                if (v > 1) {
                    if (tail + 3 > q.length) { q = grow(q); }
                    q[tail++] = x0 + x; q[tail++] = y; q[tail++] = z0 + z;
                }
            }
            flood(w, c, layer, q, head, tail, null);
        }
        flush(w);
    }

    private static int[] queue() {
        int[] q = QUEUE.get();
        if (q == null) { q = new int[3 << 16]; QUEUE.set(q); }
        return q;
    }

    private static int[] grow(int[] q) {
        int[] n = java.util.Arrays.copyOf(q, q.length * 2);
        QUEUE.set(n);
        return n;
    }

    static final int[] DX = {-1, 1, 0, 0, 0, 0}, DY = {0, 0, -1, 1, 0, 0}, DZ = {0, 0, 0, 0, -1, 1};

    /** flood from the queued cells over every lit chunk */
    private static void flood(World w, Chunk own, int layer, int[] q, int head, int tail, int[] box) {
        floodY(w, own, layer, q, tail, null, 0, Chunk.H - 1);
    }

    // ------------------------------------------------------------------ edits
    /**
     * after a block change at x y z: the converged result of LightUpdate::update for both layers inside the box the
     * change can reach (15 around; the whole height when the column top moved). Computed in a scratch box first and
     * only the cells that really change are written (no dark frame while it runs).
     */
    public static void relight(World w, int wx, int wy, int wz) {
        Chunk own = w.chunk(wx >> 4, wz >> 4);
        if (own == null || own.lit < 1) return;
        int lx0 = wx & 15, lz0 = wz & 15;
        int oldH = own.height[lz0 * 16 + lx0] & 255;
        int y = Chunk.H - 1;
        while (y >= 0 && LB[own.blocks[Chunk.idx(lx0, y, lz0)] & 255] == 0) y--;
        own.height[lz0 * 16 + lx0] = (byte) (y + 1);
        boolean column = y + 1 != oldH;
        final int R = 15, W = 2 * R + 1;
        int bx0 = wx - R, bz0 = wz - R;
        for (int layer = 0; layer < 2; layer++) {
            int ylo = Math.max(0, wy - R), yhi = Math.min(Chunk.H - 1, wy + R);
            if (layer == 0 && column) { ylo = 0; yhi = Chunk.H - 1; }
            int HH = yhi - ylo + 1;
            byte[] v = new byte[W * W * HH];
            byte[] lb = new byte[W * W * HH];
            Chunk[] ch = new Chunk[W * W];
            // sources: sky = the column fill (15 minus mLightBlock from the top), block = mLightEmission
            for (int dz = 0; dz < W; dz++) for (int dx = 0; dx < W; dx++) {
                int x = bx0 + dx, z = bz0 + dz;
                Chunk k = at(w, own, x >> 4, z >> 4);
                ch[dz * W + dx] = k;
                if (k == null) continue;
                int lx = x & 15, lz = z & 15, br = 15;
                for (int yy = Chunk.H - 1; yy >= ylo; yy--) {
                    int id = k.blocks[Chunk.idx(lx, yy, lz)] & 255;
                    if (layer == 0 && br > 0) { br -= LB[id]; if (br < 0) br = 0; }
                    if (yy > yhi) continue;
                    int c = (dz * W + dx) * HH + (yy - ylo);
                    v[c] = (byte) (layer == 0 ? br : EMIT[id]);
                    lb[c] = (byte) cost(id);
                }
            }
            int[] q = queue(); int tail = 0;
            // light coming in from the cells around the box
            for (int dz = 0; dz < W; dz++) for (int dx = 0; dx < W; dx++) {
                if (ch[dz * W + dx] == null) continue;
                for (int yy = ylo; yy <= yhi; yy++) {
                    int c = (dz * W + dx) * HH + (yy - ylo);
                    int cst = lb[c];
                    if (cst >= 15) { if (v[c] > 1) { if (tail + 3 > q.length) q = grow(q); q[tail++] = dx; q[tail++] = yy; q[tail++] = dz; } continue; }
                    int best = v[c];
                    for (int d = 0; d < 6; d++) {
                        int nx = dx + DX[d], ny = yy + DY[d], nz = dz + DZ[d];
                        boolean out = nx < 0 || nz < 0 || nx >= W || nz >= W || ny < ylo || ny > yhi;
                        if (!out || ny < 0 || ny >= Chunk.H) continue;
                        int ax = bx0 + nx, az = bz0 + nz;
                        Chunk k = at(w, own, ax >> 4, az >> 4);
                        if (k == null) continue;
                        int nv = k.light(layer, Chunk.idx(ax & 15, ny, az & 15)) - cst;
                        if (nv > best) best = nv;
                    }
                    v[c] = (byte) best;
                    if (best > 1) { if (tail + 3 > q.length) q = grow(q); q[tail++] = dx; q[tail++] = yy; q[tail++] = dz; }
                }
            }
            // flood inside the box
            int head = 0;
            while (head < tail) {
                int dx = q[head], yy = q[head + 1], dz = q[head + 2];
                head += 3;
                int val = v[(dz * W + dx) * HH + (yy - ylo)];
                if (val <= 1) continue;
                for (int d = 0; d < 6; d++) {
                    int nx = dx + DX[d], ny = yy + DY[d], nz = dz + DZ[d];
                    if (nx < 0 || nz < 0 || nx >= W || nz >= W || ny < ylo || ny > yhi || ch[nz * W + nx] == null) continue;
                    int c = (nz * W + nx) * HH + (ny - ylo);
                    int cst = lb[c];
                    if (cst >= 15) continue;
                    int cand = val - cst;
                    if (cand > v[c]) {
                        v[c] = (byte) cand;
                        if (cand > 1) {
                            if (tail + 3 > q.length) {
                                System.arraycopy(q, head, q, 0, tail - head); tail -= head; head = 0;
                                if (tail + 3 > q.length) q = grow(q);
                            }
                            q[tail++] = nx; q[tail++] = ny; q[tail++] = nz;
                        }
                    }
                }
            }
            // write the differences
            for (int dz = 0; dz < W; dz++) for (int dx = 0; dx < W; dx++) {
                Chunk k = ch[dz * W + dx];
                if (k == null) continue;
                int lx = (bx0 + dx) & 15, lz = (bz0 + dz) & 15;
                for (int yy = ylo; yy <= yhi; yy++) {
                    int i = Chunk.idx(lx, yy, lz), nv = v[(dz * W + dx) * HH + (yy - ylo)];
                    if (k.light(layer, i) != nv) { k.light(layer, i, nv); k.touchLight(lx, yy, lz, w); }
                }
            }
        }
        flush(w);
    }

    /** flood restricted to the box and y range (cells outside only seed) */
    private static void floodY(World w, Chunk own, int layer, int[] q, int tail, int[] box, int ylo, int yhi) {
        int head = 0;
        while (head < tail) {
            int x = q[head], y = q[head + 1], z = q[head + 2];
            head += 3;
            Chunk k = at(w, own, x >> 4, z >> 4);
            if (k == null) continue;
            int v = k.light(layer, Chunk.idx(x & 15, y, z & 15));
            if (v <= 1) continue;
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (ny < ylo || ny > yhi) continue;
                if (box != null && (nx < box[0] || nz < box[1] || nx > box[2] || nz > box[3])) continue;
                Chunk n = (nx >> 4) == (x >> 4) && (nz >> 4) == (z >> 4) ? k : at(w, own, nx >> 4, nz >> 4);
                if (n == null) continue;
                int i = Chunk.idx(nx & 15, ny, nz & 15);
                int lb = cost(n.blocks[i] & 255);
                if (lb >= 15) continue;
                int cand = v - lb;
                if (cand > n.light(layer, i)) {
                    n.light(layer, i, cand);
                    n.touchLight(nx & 15, ny, nz & 15, w);
                    if (cand > 1) {
                        if (tail + 3 > q.length) {
                            // compact: drop the processed part
                            System.arraycopy(q, head, q, 0, tail - head); tail -= head; head = 0;
                            if (tail + 3 > q.length) q = grow(q);
                        }
                        q[tail++] = nx; q[tail++] = ny; q[tail++] = nz;
                    }
                }
            }
        }
    }
}
