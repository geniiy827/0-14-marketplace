package com.nova.engine.world;

/**
 * LargeCaveFeature of MCPE 0.14.3, ported from the disassembly (LargeFeature::apply, addFeature, addRoom,
 * addTunnel, isDiggable, _thinSand). Single precision where the original uses floats, double where it calls
 * sin/cos/floor; reciprocal multiplies and operation order as in the binary. Carves only into the given chunk.
 */
public final class Caves {
    static final int RANGE = 8;
    static final float PI = 3.1415927f;
    static final int STONE = 1, GRASS = 2, DIRT = 3, FLOWING_WATER = 8, WATER = 9, LAVA = 11, SAND = 12,
            SANDSTONE = 24, MYCELIUM = 110, STAINED_CLAY = 159, HARDENED_CLAY = 172, RED_SANDSTONE = 179, PODZOL = 243;

    private final int seed;
    private Chunk c;
    private int cx, cz;

    public Caves(int seed) { this.seed = seed; }

    /** LargeFeature::apply */
    public void apply(Chunk chunk) {
        c = chunk; cx = chunk.cx; cz = chunk.cz;
        McRandom r = new McRandom(seed);
        int a = (int) ((r.nextU32() >>> 2) << 1) + 1;
        int b = (int) ((r.nextU32() >>> 2) << 1) + 1;
        for (int x = cx - RANGE; x <= cx + RANGE; x++) {
            for (int z = cz - RANGE; z <= cz + RANGE; z++) {
                r.setSeed((x * a + z * b) ^ seed);
                addFeature(r, x, z);
            }
        }
        c = null;
    }

    private void addFeature(McRandom r, int x, int z) {
        long u1 = r.nextU32(), u2 = r.nextU32(), u3 = r.nextU32(), u4 = r.nextU32();
        if (u4 % 15 != 0) return;
        int count = (int) (u3 % (u2 % (u1 % 40 + 1) + 1));
        for (int i = 0; i < count; i++) {
            long pa = r.nextU32(), pb = r.nextU32(), pc = r.nextU32(), pd = r.nextU32();
            float px = (float) ((int) (pa & 15) + x * 16);
            float py = (float) (int) (pc % (pb % 120 + 8));
            float pz = (float) ((int) (pd & 15) + z * 16);
            int tunnels = 1;
            if ((r.nextU32() & 3) == 0) {
                // addRoom
                float th = 1f + r.nextFloat() * 6f;
                addTunnel(r, px, py, pz, th, 0f, 0f, -1, -1, 0.5f);
                tunnels = (int) (r.nextU32() & 3) + 1;
            }
            float twoPi = PI + PI;
            for (int j = 0; j < tunnels; j++) {
                float yaw = twoPi * r.nextFloat();
                float pitch = (r.nextFloat() + -0.5f) * 0.25f;
                float t1 = r.nextFloat();
                float t2 = r.nextFloat();
                float thick = (t1 + t1) + t2;
                addTunnel(r, px, py, pz, thick, yaw, pitch, 0, 0, 1f);
            }
        }
    }

    private static boolean diggable(int id, int above) {
        if (id == STONE || id == DIRT || id == GRASS || id == HARDENED_CLAY || id == STAINED_CLAY || id == SANDSTONE
                || id == RED_SANDSTONE || id == MYCELIUM || id == PODZOL) return true;
        if (id == SAND) return above != WATER;
        return false;
    }

    private void addTunnel(McRandom parent, float x, float y, float z, float thickness, float yRot, float xRot,
                           int step, int dist, float yScale) {
        float xMid = (float) (cx * 16 + 8), zMid = (float) (cz * 16 + 8);
        McRandom r = new McRandom((int) (parent.nextU32() >>> 1));
        if (dist <= 0) {
            int i = (RANGE - 1) * 16;
            int q = i / 4;
            dist = i - (q != 0 ? (int) (r.nextU32() % q) : 0);
        }
        boolean room = false;
        if (step == -1) { step = dist / 2; room = true; }
        int half = dist / 2;
        int branch = (half != 0 ? (int) (r.nextU32() % half) : 0) + dist / 4;
        boolean steep = r.nextU32() % 6 == 0;
        if (step >= dist) return;
        float dYaw = 0f, dPitch = 0f;
        float piOverDist = PI / (float) dist;
        float maxD2 = (thickness + 18f) * (thickness + 18f);
        double thickD = thickness;
        for (; step < dist; step++) {
            double sinStep = Math.sin((double) (piOverDist * (float) step));
            double cosP = Math.cos(xRot), sinP = Math.sin(xRot);
            float fcos = (float) cosP;
            double cosY = Math.cos(yRot);
            y = (float) sinP + y;
            x = (float) ((double) x + (double) fcos * cosY);
            z = (float) ((double) z + (double) fcos * Math.sin(yRot));
            xRot = xRot * (steep ? 0.92f : 0.7f);
            yRot = yRot + dPitch * 0.1f;
            dPitch = dPitch * 0.75f;
            float a = r.nextFloat();
            xRot = xRot + dYaw * 0.1f;
            float b = r.nextFloat();
            float ab = a - b;
            float cc = r.nextFloat();
            dYaw = dYaw * 0.9f;
            dYaw = dYaw + (cc + cc) * ab;
            float d = r.nextFloat();
            float e = r.nextFloat();
            float de = d - e;
            float g = r.nextFloat();
            dPitch = dPitch + (g * 4f) * de;
            if (!room && step == branch && thickness > 1f) {
                float t1 = 0.5f + r.nextFloat() * 0.5f;
                float halfPi = PI * 0.5f;
                float xr = xRot * 0.33333334f;
                addTunnel(r, x, y, z, t1, yRot - halfPi, xr, step, dist, 1f);
                float t2 = 0.5f + r.nextFloat() * 0.5f;
                addTunnel(r, x, y, z, t2, halfPi + yRot, xr, step, dist, 1f);
                return;
            }
            if (!room && (r.nextU32() & 3) == 0) continue;
            float ddx = x - xMid, ddz = z - zMid, rem = (float) (dist - step);
            float dd = ddx * ddx;
            dd = dd + ddz * ddz;
            dd = dd - rem * rem;
            if (dd > maxD2) return;
            float rad = (float) (1.5 + sinStep * thickD);
            float rad2 = rad + rad;
            if (x < (xMid - rad2) - 16f || z < (zMid - rad2) - 16f || x > (xMid + 16f) + rad2 || z > (zMid + 16f) + rad2) continue;
            float yRad = rad * yScale;
            int x0 = Math.max(0, (int) Math.floor(x - rad) - cx * 16 - 1);
            int x1 = Math.min(16, (int) Math.floor(x + rad) - cx * 16 + 1);
            int y0 = Math.max(1, (int) Math.floor(y - yRad) - 1);
            int y1 = Math.min(120, (int) Math.floor(y + yRad) + 1);
            int z0 = Math.max(0, (int) Math.floor(z - rad) - cz * 16 - 1);
            int z1 = Math.min(16, (int) Math.floor(z + rad) - cz * 16 + 1);
            // water nearby: leave this step
            boolean water = false;
            for (int xx = x0; !water && xx < x1; xx++) for (int zz = z0; !water && zz < z1; zz++) {
                for (int yy = y1 + 1; !water && yy >= y0 - 1; yy--) {
                    if (yy >= 0 && yy < 128) {
                        int id = c.get(xx, yy, zz);
                        if (id == FLOWING_WATER || id == WATER) water = true;
                        if (yy != y0 - 1 && xx != x0 && xx != x1 - 1 && zz != z0 && zz != z1 - 1) yy = y0;
                    }
                }
            }
            if (water) continue;
            float invR = 1f / rad, invY = 1f / yRad;
            for (int xx = x0; xx < x1; xx++) {
                float dx = ((0.5f - x) + (float) (xx + cx * 16)) * invR;
                dx = dx * dx;
                for (int zz = z0; zz < z1; zz++) {
                    float dz = ((0.5f - z) + (float) (zz + cz * 16)) * invR;
                    float dxz = dx + dz * dz;
                    if (!(dxz < 1f)) continue;
                    boolean grass = false;
                    for (int yy = y1 - 1; yy >= y0; yy--) {
                        float dy = ((0.5f - y) + (float) yy) * invY;
                        if (!((double) dy > -0.7)) continue;
                        if (!(dxz + dy * dy < 1f)) continue;
                        int id = c.get(xx, yy, zz);
                        if (id == GRASS) grass = true;
                        if (!diggable(id, c.get(xx, yy + 1, zz))) continue;
                        if (yy < 10) { c.set(xx, yy, zz, LAVA, 0); continue; }
                        if (yy <= 124 && c.get(xx, yy + 1, zz) == SAND && c.get(xx, yy + 2, zz) == SAND && c.get(xx, yy + 3, zz) == SAND)
                            c.set(xx, yy + 1, zz, c.data(xx, yy + 1, zz) == 1 ? RED_SANDSTONE : SANDSTONE, 0);
                        c.set(xx, yy, zz, 0, 0);
                        if (grass && c.get(xx, yy - 1, zz) == DIRT) c.set(xx, yy - 1, zz, GRASS, 0);
                    }
                }
            }
            if (room) return;
        }
    }
}
