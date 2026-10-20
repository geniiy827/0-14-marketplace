package com.geniiy.bridge14.proxy;

import java.io.DataInputStream;
import java.io.InputStream;

/**
 * Соединения заборов, стен, панелей и решёток. В 0.14 их форма считается на клиенте,
 * а Java ждёт её в самом состоянии блока — без этого стена без соединений вообще не рисуется.
 */
public final class Shapes {
    public static final int SOLID = 1, FENCE = 2, NETHER_FENCE = 3, PANE = 4, WALL = 5, GATE = 6;
    private static byte[] kind = new byte[0];
    private static int[] rMin = new int[0], rMax = new int[0];

    public interface Lookup { int state(int x, int y, int z); }

    static synchronized void load(InputStream raw) throws Exception {
        try (DataInputStream in = new DataInputStream(raw)) {
            int n = in.readInt();
            byte[] k = new byte[n];
            in.readFully(k);
            int r = in.readInt();
            int[] a = new int[r], b = new int[r];
            for (int i = 0; i < r; i++) { a[i] = in.readInt(); b[i] = in.readInt(); in.readByte(); }
            kind = k; rMin = a; rMax = b;
        }
    }

    public static int kind(int state) { return state >= 0 && state < kind.length ? kind[state] : 0; }

    public static boolean connectable(int state) { int k = kind(state); return k >= FENCE && k <= WALL; }

    private static int base(int state) {
        int lo = 0, hi = rMin.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            if (state < rMin[m]) hi = m - 1;
            else if (state > rMax[m]) lo = m + 1;
            else return rMin[m];
        }
        return -1;
    }

    private static boolean joins(int k, int nk) {
        switch (k) {
            case FENCE: return nk == FENCE || nk == GATE || nk == SOLID;
            case NETHER_FENCE: return nk == NETHER_FENCE || nk == GATE || nk == SOLID;
            case PANE: return nk == PANE || nk == WALL || nk == SOLID;
            case WALL: return nk == WALL || nk == GATE || nk == PANE || nk == SOLID;
            default: return false;
        }
    }

    /** Правильное состояние для блока в (x,y,z) с учётом соседей; если блок не соединяемый — без изменений. */
    public static int fix(int state, int x, int y, int z, Lookup w) {
        int k = kind(state);
        if (k < FENCE || k > WALL) return state;
        int b = base(state);
        if (b < 0) return state;
        boolean n = joins(k, kind(w.state(x, y, z - 1)));
        boolean s = joins(k, kind(w.state(x, y, z + 1)));
        boolean e = joins(k, kind(w.state(x + 1, y, z)));
        boolean wv = joins(k, kind(w.state(x - 1, y, z)));
        if (k == WALL) {
            int above = kind(w.state(x, y + 1, z));
            boolean straight = (n && s && !e && !wv) || (e && wv && !n && !s);
            boolean up = !straight || above == WALL;
            int E = e ? 1 : 0, N = n ? 1 : 0, S = s ? 1 : 0, W = wv ? 1 : 0;
            int idx = ((((E * 3 + N) * 3 + S) * 2 + (up ? 0 : 1)) * 2 + 1) * 3 + W;
            return b + idx;
        }
        int idx = (e ? 0 : 16) + (n ? 0 : 8) + (s ? 0 : 4) + 2 + (wv ? 0 : 1);
        return b + idx;
    }
}
