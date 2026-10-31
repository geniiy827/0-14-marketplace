package com.nova.engine.game;

import com.nova.engine.world.Blocks;
import com.nova.engine.world.World;

/**
 * The player: feet position, hitbox 0.6 x 1.8, eyes at 1.62. Physics at 20 ticks per second like the original
 * (walk acceleration, friction 0.546 on ground, gravity 0.08, drag 0.98, jump 0.42), interpolated for rendering.
 * Auto-jump onto one-block steps (as in Pocket Edition). Creative flight.
 */
public final class Player {
    public static final float W = 0.6f, H = 1.8f, EYE = 1.62f;
    public double x, y, z, px, py, pz;          // now / previous tick
    public double mx, my, mz;
    public float yaw, pitch;                    // degrees; yaw 0 looks to -z, pitch + looks down
    public boolean onGround, flying, inWater;
    public float moveF, moveS;                  // input -1..1
    public boolean jump, sneak;
    private int jumpTicks;

    public void tick(World w) {
        px = x; py = y; pz = z;
        double ry = Math.toRadians(yaw);
        double sin = Math.sin(ry), cos = Math.cos(ry);
        // input -> world: forward (sin, -cos), right (cos, sin)
        double ix = moveF * sin + moveS * cos, iz = -moveF * cos + moveS * sin;
        double il = Math.sqrt(ix * ix + iz * iz);
        if (il > 1) { ix /= il; iz /= il; }
        inWater = Blocks.kind[w.get(fl(x), fl(y + 0.4), fl(z))] == Blocks.LIQUID;
        if (flying) {
            double acc = 0.1;
            mx += ix * acc; mz += iz * acc;
            my = jump ? 0.45 : sneak ? -0.45 : my * 0.6;
            move(w);
            mx *= 0.6; mz *= 0.6;
            if (onGround && sneak) flying = false;
        } else if (inWater) {
            mx += ix * 0.02; mz += iz * 0.02;
            if (jump) my += 0.04;
            move(w);
            mx *= 0.8; my *= 0.8; mz *= 0.8;
            my -= 0.02;
        } else {
            double acc = onGround ? 0.1 * 1.3 : 0.02 * 1.3;    // a little faster than vanilla walking feels right on touch
            mx += ix * acc; mz += iz * acc;
            if (jump && onGround && jumpTicks == 0) { my = 0.42; jumpTicks = 10; }
            boolean wasGround = onGround;
            double bx = mx, bz = mz;
            move(w);
            // auto-jump: blocked horizontally while walking on the ground, and there is room one block up
            if (wasGround && il > 0.1 && ((bx != mx && Math.abs(bx) > 0.01) || (bz != mz && Math.abs(bz) > 0.01)) && jumpTicks == 0) {
                if (free(w, x + Math.signum(bx) * 0.4, y + 1.01, z + Math.signum(bz) * 0.4)) { my = 0.42; jumpTicks = 10; }
            }
            double fr = onGround ? 0.546 : 0.91;
            mx *= fr; mz *= fr;
            my = (my - 0.08) * 0.98;
        }
        if (jumpTicks > 0) jumpTicks--;
        if (y < -40) { y = 140; my = 0; }
    }

    private boolean free(World w, double cx, double fy, double cz) {
        for (int yy = fl(fy); yy <= fl(fy + H - 0.01); yy++) if (Blocks.solid[w.get(fl(cx), yy, fl(cz))]) return false;
        return true;
    }

    /** move by motion with collisions: y first, then x, then z (as the original) */
    private void move(World w) {
        double dy = clipY(w, my);
        onGround = my < 0 && dy != my;
        if (dy != my) my = 0;
        y += dy;
        double dx = clipX(w, mx);
        if (dx != mx) mx = 0;
        x += dx;
        double dz = clipZ(w, mz);
        if (dz != mz) mz = 0;
        z += dz;
    }

    private double clipY(World w, double d) {
        if (d == 0) return 0;
        int x0 = fl(x - W / 2), x1 = fl(x + W / 2 - 1e-4), z0 = fl(z - W / 2), z1 = fl(z + W / 2 - 1e-4);
        if (d < 0) {
            int yy = fl(y + d);
            for (int by = fl(y - 1e-4); by >= yy; by--)
                for (int bx = x0; bx <= x1; bx++) for (int bz = z0; bz <= z1; bz++)
                    if (solidAt(w, bx, by, bz)) return Math.max(d, by + 1 - y);
        } else {
            int yy = fl(y + H + d);
            for (int by = fl(y + H); by <= yy; by++)
                for (int bx = x0; bx <= x1; bx++) for (int bz = z0; bz <= z1; bz++)
                    if (solidAt(w, bx, by, bz)) return Math.min(d, by - (y + H) - 1e-4);
        }
        return d;
    }

    private double clipX(World w, double d) {
        if (d == 0) return 0;
        int y0 = fl(y + 1e-4), y1 = fl(y + H - 1e-4), z0 = fl(z - W / 2), z1 = fl(z + W / 2 - 1e-4);
        if (d < 0) {
            int xx = fl(x - W / 2 + d);
            for (int bx = fl(x - W / 2 - 1e-4); bx >= xx; bx--)
                for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++)
                    if (solidAt(w, bx, by, bz)) return Math.max(d, bx + 1 - (x - W / 2) + 1e-4);
        } else {
            int xx = fl(x + W / 2 + d);
            for (int bx = fl(x + W / 2); bx <= xx; bx++)
                for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++)
                    if (solidAt(w, bx, by, bz)) return Math.min(d, bx - (x + W / 2) - 1e-4);
        }
        return d;
    }

    private double clipZ(World w, double d) {
        if (d == 0) return 0;
        int y0 = fl(y + 1e-4), y1 = fl(y + H - 1e-4), x0 = fl(x - W / 2), x1 = fl(x + W / 2 - 1e-4);
        if (d < 0) {
            int zz = fl(z - W / 2 + d);
            for (int bz = fl(z - W / 2 - 1e-4); bz >= zz; bz--)
                for (int by = y0; by <= y1; by++) for (int bx = x0; bx <= x1; bx++)
                    if (solidAt(w, bx, by, bz)) return Math.max(d, bz + 1 - (z - W / 2) + 1e-4);
        } else {
            int zz = fl(z + W / 2 + d);
            for (int bz = fl(z + W / 2); bz <= zz; bz++)
                for (int by = y0; by <= y1; by++) for (int bx = x0; bx <= x1; bx++)
                    if (solidAt(w, bx, by, bz)) return Math.min(d, bz - (z + W / 2) - 1e-4);
        }
        return d;
    }

    private static boolean solidAt(World w, int x, int y, int z) {
        if (!w.loaded(x, z)) return true;                     // never fall into unloaded chunks
        return Blocks.solid[w.get(x, y, z)];
    }

    static int fl(double v) { return (int) Math.floor(v); }

    /** interpolated eye position */
    public double ex(float a) { return px + (x - px) * a; }
    public double ey(float a) { return py + (y - py) * a + EYE; }
    public double ez(float a) { return pz + (z - pz) * a; }

    /** look direction (unit) */
    public void look(float[] out) {
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
        out[0] = (float) (Math.sin(ry) * Math.cos(rp));
        out[1] = (float) -Math.sin(rp);
        out[2] = (float) (-Math.cos(ry) * Math.cos(rp));
    }
}
