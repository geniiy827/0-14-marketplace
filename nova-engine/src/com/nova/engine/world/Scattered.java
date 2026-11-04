package com.nova.engine.world;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * RandomScatteredLargeFeature of MCPE 0.14.3: desert pyramids and witch huts (no jungle temples in 0.14).
 * isFeatureChunk 0x5e7c40: spacing 32, separation 8, with the random of the LargeFeature pass (the chunk random
 * (x*a + z*b) ^ seed, one draw taken by StructureFeature::addFeature); biome at the chunk centre desert / desert hills ->
 * DesertPyramidPiece 21x15x21, swampland (M) -> SwamplandHut 7x7x9 at (x*16, 64, z*16), facing south. Buildings are
 * the programs recorded from the binary (tools/emu_scattered.py).
 */
final class Scattered {
    final Generator gen;
    final int seed, a, b;
    private final HashMap<Long, Village.Piece> starts = new HashMap<Long, Village.Piece>();
    private final HashMap<Long, Boolean> checked = new HashMap<Long, Boolean>();
    static final int PYRAMID = 100, HUT = 101;

    Scattered(Generator gen, int seed) {
        this.gen = gen; this.seed = seed;
        McRandom r = new McRandom(seed);
        a = (int) ((r.nextU32() >>> 2) << 1) + 1;
        b = (int) ((r.nextU32() >>> 2) << 1) + 1;
    }

    Village.Piece feature(int cx, int cz) {
        McRandom r = new McRandom((cx * a + cz * b) ^ seed);
        r.nextU32();                                              // StructureFeature::addFeature
        int ax = cx < 0 ? cx - 31 : cx, az = cz < 0 ? cz - 31 : cz;
        int x = (ax / 32) * 32 + (int) (r.nextU32() % 24);
        int z = (az / 32) * 32 + (int) (r.nextU32() % 24);
        if (x != cx || z != cz) return null;
        int bio = gen.biomeAt(cx * 16 + 8, cz * 16 + 8);
        if (bio == 2 || bio == 17) return new Village.Piece(PYRAMID, new Village.Box(cx * 16, 64, cz * 16, cx * 16 + 20, 78, cz * 16 + 20), Village.S, 0);
        if (bio == 6 || bio == 134) return new Village.Piece(HUT, new Village.Box(cx * 16, 64, cz * 16, cx * 16 + 6, 70, cz * 16 + 8), Village.S, 0);
        return null;
    }

    List<Village.Piece> near(int cx, int cz) {
        List<Village.Piece> out = new ArrayList<Village.Piece>();
        for (int x = cx - 8; x <= cx + 8; x++) for (int z = cz - 8; z <= cz + 8; z++) {
            long k = World.key(x, z);
            if (!checked.containsKey(k)) { Village.Piece p = feature(x, z); checked.put(k, p != null); if (p != null) starts.put(k, p); }
            if (checked.get(k)) out.add(starts.get(k));
        }
        return out;
    }

    void postProcess(Village.Blocks4 w, McRandom r, int cx, int cz) {
        Village.Box chunk = new Village.Box(cx << 4, 1, cz << 4, (cx << 4) + 15, 512, (cz << 4) + 15);
        for (Village.Piece p : near(cx, cz)) {
            if (!p.bb.intersects(chunk)) continue;
            if (p.type == HUT && p.avg < 0) {                     // ScatteredFeaturePiece::updateAverageGroundHeight
                int sum = 0, n = 0;
                for (int z = p.bb.z0; z <= p.bb.z1; z++) for (int x = p.bb.x0; x <= p.bb.x1; x++) {
                    if (!(x >= chunk.x0 && x <= chunk.x1 && z >= chunk.z0 && z <= chunk.z1)) continue;
                    sum += Math.max(w.topSolid(x, z, true), 64);
                    n++;
                }
                if (n == 0) continue;
                p.avg = sum / n;
                int off = p.avg - p.bb.y0;
                p.bb.y0 += off; p.bb.y1 += off;
            }
            int[][] prog = Village.PROGRAMS.get((p.type == PYRAMID ? "DesertPyramidPiece" : "SwamplandHut") + "|0|");
            if (prog != null) Village.replay(w, r, null, p, chunk, prog);
        }
    }
}
