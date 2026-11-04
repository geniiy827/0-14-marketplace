package com.nova.engine.game;

import com.nova.engine.world.Blocks;
import com.nova.engine.world.World;

/** voxel ray walk (Amanatides & Woo): first block that is not air/liquid within reach */
public final class Raycast {
    public boolean hit;
    public int x, y, z, face;            // face: 0 down 1 up 2 north 3 south 4 west 5 east (the side that was hit)

    public void cast(World w, double ox, double oy, double oz, float dx, float dy, float dz, double reach) {
        hit = false;
        int ix = (int) Math.floor(ox), iy = (int) Math.floor(oy), iz = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? 1e30 : Math.abs(1 / dx), tdy = dy == 0 ? 1e30 : Math.abs(1 / dy), tdz = dz == 0 ? 1e30 : Math.abs(1 / dz);
        double tmx = dx == 0 ? 1e30 : (dx > 0 ? ix + 1 - ox : ox - ix) * tdx;
        double tmy = dy == 0 ? 1e30 : (dy > 0 ? iy + 1 - oy : oy - iy) * tdy;
        double tmz = dz == 0 ? 1e30 : (dz > 0 ? iz + 1 - oz : oz - iz) * tdz;
        int f = -1;
        double t = 0;
        while (t <= reach) {
            int b = w.get(ix, iy, iz);
            if (b != 0 && Blocks.exists[b] && Blocks.kind[b] != Blocks.LIQUID && f >= 0) {
                float[][] bs = com.nova.engine.world.Shapes.at(w, ix, iy, iz);
                if (bs == com.nova.engine.world.Shapes.CUBE) { hit = true; x = ix; y = iy; z = iz; face = f; return; }
                // shaped block: the ray must hit one of its boxes
                double best = 1e30; int bf = -1;
                for (float[] bb : bs) {
                    double t0 = -1e30, t1 = 1e30; int ef = -1;
                    double[] o = {ox - ix, oy - iy, oz - iz}, dd = {dx, dy, dz};
                    double[] lo = {bb[0], bb[1], bb[2]}, hi = {bb[3], bb[4], bb[5]};
                    boolean miss = false;
                    for (int a = 0; a < 3 && !miss; a++) {
                        if (Math.abs(dd[a]) < 1e-9) { if (o[a] < lo[a] || o[a] > hi[a]) miss = true; continue; }
                        double ta = (lo[a] - o[a]) / dd[a], tb = (hi[a] - o[a]) / dd[a];
                        int fa = a == 0 ? (dd[a] > 0 ? 4 : 5) : a == 1 ? (dd[a] > 0 ? 0 : 1) : (dd[a] > 0 ? 2 : 3);
                        if (ta > tb) { double tt = ta; ta = tb; tb = tt; }
                        if (ta > t0) { t0 = ta; ef = fa; }
                        if (tb < t1) t1 = tb;
                        if (t0 > t1) miss = true;
                    }
                    if (!miss && t1 >= 0 && t0 < best && t0 <= reach) { best = t0; bf = ef; }
                }
                if (bf >= 0) { hit = true; x = ix; y = iy; z = iz; face = bf; return; }
            }
            if (tmx < tmy && tmx < tmz) { ix += sx; t = tmx; tmx += tdx; f = sx > 0 ? 4 : 5; }
            else if (tmy < tmz) { iy += sy; t = tmy; tmy += tdy; f = sy > 0 ? 0 : 1; }
            else { iz += sz; t = tmz; tmz += tdz; f = sz > 0 ? 2 : 3; }
        }
    }

    /** neighbour position on the hit face (where a new block goes) */
    public int nx() { return x + (face == 4 ? -1 : face == 5 ? 1 : 0); }
    public int ny() { return y + (face == 0 ? -1 : face == 1 ? 1 : 0); }
    public int nz() { return z + (face == 2 ? -1 : face == 3 ? 1 : 0); }
}
