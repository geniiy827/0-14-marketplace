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
            case Blocks.M_DOOR: {
                int d = data;
                if ((d & 8) != 0) d = below == id ? belowData & 7 : 0;
                float t = 3 * S;
                switch (d & 3) {
                    case 0: return new float[][]{{0, 0, 0, t, 1, 1}};
                    case 1: return new float[][]{{0, 0, 0, 1, 1, t}};
                    case 2: return new float[][]{{1 - t, 0, 0, 1, 1, 1}};
                    default: return new float[][]{{0, 0, 1 - t, 1, 1, 1}};
                }
            }
            case Blocks.M_LADDER:
                switch (data) {
                    case 2: return new float[][]{{0, 0, 1 - 2 * S, 1, 1, 1}};
                    case 3: return new float[][]{{0, 0, 0, 1, 1, 2 * S}};
                    case 4: return new float[][]{{1 - 2 * S, 0, 0, 1, 1, 1}};
                    default: return new float[][]{{0, 0, 0, 2 * S, 1, 1}};
                }
            case Blocks.M_CARPET: return new float[][]{{0, 0, 0, 1, S, 1}};
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

    /** boxes of the block at a world position */
    public static float[][] at(World w, int x, int y, int z) {
        int id = w.get(x, y, z);
        if (id == 0) return null;
        return boxes(id, w.data(x, y, z), w.get(x, y, z - 1), w.get(x, y, z + 1), w.get(x - 1, y, z), w.get(x + 1, y, z),
                w.get(x, y - 1, z), w.data(x, y - 1, z));
    }
}
