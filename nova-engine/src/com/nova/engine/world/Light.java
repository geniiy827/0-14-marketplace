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
     * after a block change at x y z: recompute both layers inside the box the change can reach
     * (15 around; sky the whole column height when the column top moved), seeded from the cells around it
     */
    public static void relight(World w, int wx, int wy, int wz) {
        Chunk own = w.chunk(wx >> 4, wz >> 4);
        if (own == null || own.lit < 1) return;
        int oldH = own.height[(wz & 15) * 16 + (wx & 15)] & 255;
        // the column of the edit: new height + sky fill (the fill below the old/new top changes)
        int[] box = {wx - 15, wz - 15, wx + 15, wz + 15};
        int ylo0 = Math.max(0, wy - 15), yhi0 = Math.min(Chunk.H - 1, wy + 15);
        for (int layer = 0; layer < 2; layer++) {
            int ylo = ylo0, yhi = yhi0;
            if (layer == 0) {
                int lx = wx & 15, lz = wz & 15, y = Chunk.H - 1;
                while (y >= 0 && LB[own.blocks[Chunk.idx(lx, y, lz)] & 255] == 0) y--;
                own.height[lz * 16 + lx] = (byte) (y + 1);
                if (y + 1 != oldH) { ylo = 0; yhi = Chunk.H - 1; }
            }
            // reset the box to its sources
            for (int z = box[1]; z <= box[3]; z++) for (int x = box[0]; x <= box[2]; x++) {
                Chunk k = at(w, own, x >> 4, z >> 4);
                if (k == null) continue;
                if (layer == 0) {
                    int lx = x & 15, lz = z & 15;
                    int br = 15;
                    for (int y = Chunk.H - 1; y >= 0; y--) {
                        int i = Chunk.idx(lx, y, lz);
                        if (br > 0) { br -= LB[k.blocks[i] & 255]; if (br < 0) br = 0; }
                        if (y < ylo || y > yhi) continue;
                        if (k.light(0, i) != br) { k.light(0, i, br); k.touchLight(lx, y, lz, w); }
                    }
                } else {
                    for (int y = ylo; y <= yhi; y++) {
                        int i = Chunk.idx(x & 15, y, z & 15);
                        int e = EMIT[k.blocks[i] & 255];
                        if (k.light(1, i) != e) { k.light(1, i, e); k.touchLight(x & 15, y, z & 15, w); }
                    }
                }
            }
            // seeds: every lit cell of the box and the ring of cells around it
            int[] q = queue(); int tail = 0;
            for (int z = box[1] - 1; z <= box[3] + 1; z++) for (int x = box[0] - 1; x <= box[2] + 1; x++) {
                Chunk k = at(w, own, x >> 4, z >> 4);
                if (k == null) continue;
                boolean ring = x < box[0] || x > box[2] || z < box[1] || z > box[3];
                for (int y = Math.max(0, ylo - 1); y <= Math.min(Chunk.H - 1, yhi + 1); y++) {
                    boolean inside = !ring && y >= ylo && y <= yhi;
                    boolean border = ring || y < ylo || y > yhi;
                    if (!inside && !border) continue;
                    int v = k.light(layer, Chunk.idx(x & 15, y, z & 15));
                    if (v > 1) {
                        if (tail + 3 > q.length) q = grow(q);
                        q[tail++] = x; q[tail++] = y; q[tail++] = z;
                    }
                }
            }
            int[] fbox = {box[0], box[1], box[2], box[3]};
            floodY(w, own, layer, q, tail, fbox, ylo, yhi);
        }
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
