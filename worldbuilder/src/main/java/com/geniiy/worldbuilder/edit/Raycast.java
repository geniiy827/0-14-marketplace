package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.blocks.JavaStates;
import com.geniiy.worldbuilder.pe.PeWorld;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;

/** Луч по вокселям (DDA) с точной проверкой формы Java-блока (плиты, ступени, факелы). */
public final class Raycast {
    private Raycast() {}

    public static final class Hit {
        public int x, y, z, face = -1;
        public double hx, hy, hz;
        public boolean air;
        public boolean ungenerated; // попали в «пол» несгенерированного чанка
        public int nx() { return x + (face == 5 ? 1 : face == 4 ? -1 : 0); }
        public int ny() { return y + (face == 1 ? 1 : face == 0 ? -1 : 0); }
        public int nz() { return z + (face == 3 ? 1 : face == 2 ? -1 : 0); }
        public double fy() { return hy - Math.floor(hy); }
    }

    public static Hit cast(PeWorld w, double ox, double oy, double oz, double dx, double dy, double dz, double maxDist,
                           boolean liquids, boolean showInvisible, int sliceY, double airDist) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? (x + 1 - ox) : (ox - x)) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? (y + 1 - oy) : (oy - y)) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? (z + 1 - oz) : (oz - z)) * tdz);
        int face = -1;
        double t = 0;
        Vec3d start = new Vec3d(ox, oy, oz), end = new Vec3d(ox + dx * maxDist, oy + dy * maxDist, oz + dz * maxDist);
        for (int i = 0; i < 4096 && t <= maxDist; i++) {
            if (y >= 0 && y < 128 && y <= sliceY) {
                int v = w.getV(x, y, z);
                if (v != 0) {
                    int id = v >> 4;
                    boolean skip = (!liquids && v > 0 && id >= 8 && id <= 11) || (!showInvisible && (id == 95 || id == 248 || id == 249 || id == 255));
                    if (!skip) {
                        BlockState st = v < 0 ? JavaStates.UNKNOWN_STATE : JavaStates.BASE[v & 4095];
                        VoxelShape shape;
                        try { shape = st.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN); } catch (Throwable e) { shape = null; }
                        if (shape == null || shape.isEmpty()) {
                            Hit h = new Hit(); h.x = x; h.y = y; h.z = z; h.face = face < 0 ? 1 : face;
                            h.hx = ox + dx * t; h.hy = oy + dy * t; h.hz = oz + dz * t; return h;
                        }
                        BlockHitResult r = shape.raycast(start, end, new BlockPos(x, y, z));
                        if (r != null) {
                            Hit h = new Hit(); h.x = x; h.y = y; h.z = z; h.face = r.getSide().ordinal();
                            h.hx = r.getPos().x; h.hy = r.getPos().y; h.hz = r.getPos().z; return h;
                        }
                    }
                }
            }
            // пол y=0 для несгенерированных чанков
            if (y == -1 && dy < 0 && !w.chunkExists(x >> 4, z >> 4)) {
                Hit h = new Hit(); h.x = x; h.y = -1; h.z = z; h.face = 1; h.ungenerated = true;
                h.hx = ox + dx * t; h.hy = 0; h.hz = oz + dz * t; return h;
            }
            if (tmx < tmy && tmx < tmz) { x += sx; t = tmx; tmx += tdx; face = sx > 0 ? 4 : 5; }
            else if (tmy < tmz) { y += sy; t = tmy; tmy += tdy; face = sy > 0 ? 0 : 1; }
            else { z += sz; t = tmz; tmz += tdz; face = sz > 0 ? 2 : 3; }
            if (y < -2 && dy < 0) break;
            if (y > 200 && dy > 0) break;
        }
        // «в воздухе»: точка на расстоянии airDist
        Hit h = new Hit();
        h.air = true;
        h.hx = ox + dx * airDist; h.hy = oy + dy * airDist; h.hz = oz + dz * airDist;
        h.x = (int) Math.floor(h.hx); h.y = (int) Math.floor(h.hy); h.z = (int) Math.floor(h.hz);
        h.face = -1;
        return h;
    }
}
