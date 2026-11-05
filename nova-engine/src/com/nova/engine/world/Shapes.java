package com.nova.engine.world;

/**
 * Boxes of the non-cube blocks (block units 0..1), shared by the mesher (shape), the selection overlay and the pick
 * ray. Neighbour ids are needed for fences/panes, the data of the block below for the upper half of doors.
 */
public final class Shapes {
    private Shapes() { }

    static final float S = 1f / 16f;
    public static final float[][] CUBE = {{0, 0, 0, 1, 1, 1}};

    static boolean fenceLink(int nb) {
        return nb != 0 && Blocks.exists[nb] && (Blocks.model[nb] == Blocks.M_FENCE || nb == 107 || Blocks.opaque[nb]);
    }

    static boolean paneLink(int nb) {
        return nb != 0 && Blocks.exists[nb] && (Blocks.model[nb] == Blocks.M_PANE || Blocks.opaque[nb]);
    }

    /** boxes for rendering / picking; n s w e = neighbour ids north (-z) south west (-x) east; belowData for doors */
    public static float[][] boxes(int id, int data, int n, int s, int w, int e, int below, int belowData) {
        int kind = Blocks.kind[id];
        if (kind == Blocks.CROSS) return new float[][]{{0.2f, 0, 0.2f, 0.8f, 0.8f, 0.8f}};
        if (kind != Blocks.MODEL) {
            float h = id == 78 ? ((data & 7) + 1) / 8f : Blocks.height[id];
            if (h >= 1f && Blocks.inset[id] <= 0) return CUBE;
            float in = Blocks.inset[id];
            return new float[][]{{in, 0, in, 1 - in, h, 1 - in}};
        }
        switch (Blocks.model[id]) {
            case Blocks.M_STAIRS: {
                boolean up = (data & 4) != 0;
                float lo = up ? 0.5f : 0f, hy0 = up ? 0f : 0.5f, hy1 = hy0 + 0.5f;
                float[] base = {0, lo, 0, 1, lo + 0.5f, 1};
                float[] back;
                switch (data & 3) {
                    case 0: back = new float[]{0.5f, hy0, 0, 1, hy1, 1}; break;
                    case 1: back = new float[]{0, hy0, 0, 0.5f, hy1, 1}; break;
                    case 2: back = new float[]{0, hy0, 0.5f, 1, hy1, 1}; break;
                    default: back = new float[]{0, hy0, 0, 1, hy1, 0.5f}; break;
                }
                return new float[][]{base, back};
            }
            case Blocks.M_FENCE: {
                java.util.ArrayList<float[]> b = new java.util.ArrayList<float[]>();
                b.add(new float[]{6 * S, 0, 6 * S, 10 * S, 1, 10 * S});
                for (float y0 : new float[]{6 * S, 12 * S}) {
                    float y1 = y0 + 3 * S;
                    if (fenceLink(n) || n == id) b.add(new float[]{7 * S, y0, 0, 9 * S, y1, 6 * S});
                    if (fenceLink(s) || s == id) b.add(new float[]{7 * S, y0, 10 * S, 9 * S, y1, 1});
                    if (fenceLink(w) || w == id) b.add(new float[]{0, y0, 7 * S, 6 * S, y1, 9 * S});
                    if (fenceLink(e) || e == id) b.add(new float[]{10 * S, y0, 7 * S, 1, y1, 9 * S});
                }
                return b.toArray(new float[0][]);
            }
            case Blocks.M_PANE: {
                boolean bn = paneLink(n), bs = paneLink(s), bw = paneLink(w), be = paneLink(e);
                if (!bn && !bs && !bw && !be) bn = bs = bw = be = true;
                java.util.ArrayList<float[]> b = new java.util.ArrayList<float[]>();
                b.add(new float[]{7 * S, 0, 7 * S, 9 * S, 1, 9 * S});
                if (bn) b.add(new float[]{7 * S, 0, 0, 9 * S, 1, 7 * S});
                if (bs) b.add(new float[]{7 * S, 0, 9 * S, 9 * S, 1, 1});
                if (bw) b.add(new float[]{0, 0, 7 * S, 7 * S, 1, 9 * S});
                if (be) b.add(new float[]{9 * S, 0, 7 * S, 1, 1, 9 * S});
                return b.toArray(new float[0][]);
            }
            case Blocks.M_DOOR: return door((data & 8) != 0 ? (below == id ? belowData & 7 : 0) : data & 7);
            case Blocks.M_TRAPDOOR: return trapdoor(data);
            case Blocks.M_LADDER:
                switch (data) {
                    case 2: return new float[][]{{0, 0, 1 - 2 * S, 1, 1, 1}};
                    case 3: return new float[][]{{0, 0, 0, 1, 1, 2 * S}};
                    case 4: return new float[][]{{1 - 2 * S, 0, 0, 1, 1, 1}};
                    default: return new float[][]{{0, 0, 0, 2 * S, 1, 1}};
                }
            case Blocks.M_CARPET: return new float[][]{{0, 0, 0, 1, S, 1}};
            case Blocks.M_RAIL: return new float[][]{{0, 0, 0, 1, 2 * S, 1}};              // BaseRailBlock: 0.125 high
            case Blocks.M_POT: return new float[][]{{5 * S, 0, 5 * S, 11 * S, 6 * S, 11 * S}};
            case Blocks.M_TORCH:
                switch (data) {
                    case 1: return new float[][]{{0, 0.2f, 0.35f, 0.3f, 0.8f, 0.65f}};
                    case 2: return new float[][]{{0.7f, 0.2f, 0.35f, 1, 0.8f, 0.65f}};
                    case 3: return new float[][]{{0.35f, 0.2f, 0, 0.65f, 0.8f, 0.3f}};
                    case 4: return new float[][]{{0.35f, 0.2f, 0.7f, 0.65f, 0.8f, 1}};
                    default: return new float[][]{{0.4f, 0, 0.4f, 0.6f, 0.6f, 0.6f}};
                }
            default: return CUBE;
        }
    }

    static final float DOOR_T = 0.1825f;

    /**
     * DoorBlock::getVisualShape from the composite data (lower: dir bits 0-1, open bit 2; upper: bit 3 + right hinge
     * bit 0 -> composite bit 4): one 0.1825 thick slab per half.
     */
    public static float[][] door(int composite) {
        int dir = composite & 3;
        boolean open = (composite & 4) != 0, right = (composite & 16) != 0;
        float t = DOOR_T;
        float[] n = {0, 0, 0, 1, 1, t}, w = {0, 0, 0, t, 1, 1}, s = {0, 0, 1 - t, 1, 1, 1}, e = {1 - t, 0, 0, 1, 1, 1};
        float[] b;
        switch (dir) {
            case 0: b = !open ? w : right ? s : n; break;
            case 1: b = !open ? n : right ? w : e; break;
            case 2: b = !open ? e : right ? n : s; break;
            default: b = !open ? s : right ? e : w; break;
        }
        return new float[][]{b};
    }

    /** DoorBlock::getCompositeData */
    public static int doorComposite(Access a, int x, int y, int z) {
        int d = a.data(x, y, z);
        if ((d & 8) != 0) return (a.data(x, y - 1, z) & 7) | 8 | (d & 1) << 4;
        return (d & 7) | (a.data(x, y + 1, z) & 1) << 4;
    }

    /** TrapDoorBlock::getVisualShape: dir bits 0-1, upside-down bit 2, open bit 3 */
    public static float[][] trapdoor(int data) {
        float e0 = 0.0005f, e1 = 0.9995f, t = DOOR_T;
        if ((data & 8) != 0) {
            switch (data & 3) {
                case 0: return new float[][]{{e0, e0, e0, t, e1, e1}};
                case 1: return new float[][]{{1 - t, e0, e0, e1, e1, e1}};
                case 2: return new float[][]{{e0, e0, e0, e1, e1, t}};
                default: return new float[][]{{e0, e0, 1 - t, e1, e1, e1}};
            }
        }
        if ((data & 4) != 0) return new float[][]{{e0, 1 - t, e0, e1, e1, e1}};
        return new float[][]{{e0, e0, e0, e1, t, e1}};
    }

    /** block id / data around a position, for the stair corner shapes */
    public interface Access { int id(int x, int y, int z); int data(int x, int y, int z); }

    static boolean isStairs(int id) { return id > 0 && Blocks.exists[id] && Blocks.model[id] == Blocks.M_STAIRS; }

    /** StairBlock::isLockAttached: stairs with exactly this data */
    static boolean lock(Access a, int x, int y, int z, int data) { return isStairs(a.id(x, y, z)) && a.data(x, y, z) == data; }

    /**
     * Stairs of 0.14.3 (StairBlock::addAABBs): setBaseShape (the half slab), setStepShape (half step, or a quarter for
     * an outer corner: returns false) and, when the step is a half, setInnerPieceShape (a quarter for an inner corner).
     * Neighbours count only when they are stairs of the same half (data bit 4).
     */
    public static float[][] stairs(int data, Access a, int x, int y, int z) {
        boolean up = (data & 4) != 0;
        float b0 = up ? 0.5f : 0f, s0 = up ? 0f : 0.5f, s1 = up ? 0.5f : 1f;
        float[] base = {0, b0, 0, 1, b0 + 0.5f, 1};
        float x0, z0, x1, z1;
        boolean full = true;
        int dir = data & 3;
        int nx = dir == 0 ? x + 1 : dir == 1 ? x - 1 : x, nz = dir == 2 ? z + 1 : dir == 3 ? z - 1 : z;
        int nb = a.id(nx, y, nz), nd = a.data(nx, y, nz);
        boolean same = isStairs(nb) && ((nd ^ data) & 4) == 0;
        nd &= 3;
        // setStepShape
        switch (dir) {
            case 0:
                x0 = 0.5f; x1 = 1; z0 = 0; z1 = 1;
                if (same && nd == 3 && !lock(a, x, y, z + 1, data)) { z1 = 0.5f; full = false; }
                else if (same && nd == 2 && !lock(a, x, y, z - 1, data)) { z0 = 0.5f; full = false; }
                break;
            case 1:
                x0 = 0; x1 = 0.5f; z0 = 0; z1 = 1;
                if (same && nd == 3 && !lock(a, x, y, z + 1, data)) { z1 = 0.5f; full = false; }
                else if (same && nd == 2 && !lock(a, x, y, z - 1, data)) { z0 = 0.5f; full = false; }
                break;
            case 2:
                x0 = 0; x1 = 1; z0 = 0.5f; z1 = 1;
                if (same && nd == 1 && !lock(a, x + 1, y, z, data)) { x1 = 0.5f; full = false; }
                else if (same && nd == 0 && !lock(a, x - 1, y, z, data)) { x0 = 0.5f; full = false; }
                break;
            default:
                x0 = 0; x1 = 1; z0 = 0; z1 = 0.5f;
                if (same && nd == 1 && !lock(a, x + 1, y, z, data)) { x1 = 0.5f; full = false; }
                else if (same && nd == 0 && !lock(a, x - 1, y, z, data)) { x0 = 0.5f; full = false; }
                break;
        }
        float[] step = {x0, s0, z0, x1, s1, z1};
        if (!full) return new float[][]{base, step};
        // setInnerPieceShape: the stairs behind (opposite side) turned sideways
        int bx = dir == 0 ? x - 1 : dir == 1 ? x + 1 : x, bz = dir == 2 ? z - 1 : dir == 3 ? z + 1 : z;
        int bb = a.id(bx, y, bz), bd = a.data(bx, y, bz);
        if (!isStairs(bb) || ((bd ^ data) & 4) != 0) return new float[][]{base, step};
        bd &= 3;
        float[] in = null;
        switch (dir) {
            case 0:
                if (bd == 3 && !lock(a, x, y, z - 1, data)) in = new float[]{0, s0, 0, 0.5f, s1, 0.5f};
                else if (bd == 2 && !lock(a, x, y, z + 1, data)) in = new float[]{0, s0, 0.5f, 0.5f, s1, 1};
                break;
            case 1:
                if (bd == 3 && !lock(a, x, y, z - 1, data)) in = new float[]{0.5f, s0, 0, 1, s1, 0.5f};
                else if (bd == 2 && !lock(a, x, y, z + 1, data)) in = new float[]{0.5f, s0, 0.5f, 1, s1, 1};
                break;
            case 2:
                if (bd == 1 && !lock(a, x - 1, y, z, data)) in = new float[]{0, s0, 0, 0.5f, s1, 0.5f};
                else if (bd == 0 && !lock(a, x + 1, y, z, data)) in = new float[]{0.5f, s0, 0, 1, s1, 0.5f};
                break;
            default:
                if (bd == 1 && !lock(a, x - 1, y, z, data)) in = new float[]{0, s0, 0.5f, 0.5f, s1, 1};
                else if (bd == 0 && !lock(a, x + 1, y, z, data)) in = new float[]{0.5f, s0, 0.5f, 1, s1, 1};
                break;
        }
        return in == null ? new float[][]{base, step} : new float[][]{base, step, in};
    }

    static Access worldAccess(final World w) {
        return new Access() {
            public int id(int x, int y, int z) { return w.get(x, y, z); }
            public int data(int x, int y, int z) { return w.data(x, y, z); }
        };
    }

    /** boxes of the block at a world position */
    public static float[][] at(World w, int x, int y, int z) {
        int id = w.get(x, y, z);
        if (id == 0) return null;
        if (Blocks.model[id] == Blocks.M_STAIRS) return stairs(w.data(x, y, z), worldAccess(w), x, y, z);
        if (Blocks.model[id] == Blocks.M_DOOR) return door(doorComposite(worldAccess(w), x, y, z));
        return boxes(id, w.data(x, y, z), w.get(x, y, z - 1), w.get(x, y, z + 1), w.get(x - 1, y, z), w.get(x + 1, y, z),
                w.get(x, y - 1, z), w.data(x, y - 1, z));
    }
}
