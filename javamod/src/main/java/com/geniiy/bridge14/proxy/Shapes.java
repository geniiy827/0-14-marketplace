package com.geniiy.bridge14.proxy;

import java.io.DataInputStream;
import java.io.InputStream;

/**
 * Соединения заборов, стен, панелей и решёток. В 0.14 их форма считается на клиенте,
 * а Java ждёт её в самом состоянии блока — без этого стена без соединений вообще не рисуется.
 */
public final class Shapes {
    public static final int SOLID = 1, FENCE = 2, NETHER_FENCE = 3, PANE = 4, WALL = 5, GATE = 6, STAIRS = 7, DOOR = 8;
    private static byte[] kind = new byte[0];
    private static int[] rMin = new int[0], rMax = new int[0];
    private static byte[] insta = new byte[0];

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
            try { int m = in.readInt(); byte[] ib = new byte[m]; in.readFully(ib); insta = ib; } catch (Exception ignored) {}
        }
    }

    /** Блок ломается мгновенно (трава, цветы, факелы…): Java тогда шлёт только «начал ломать». */
    public static boolean instant(int state) { return state > 0 && state < insta.length && insta[state] != 0; }

    public static int kind(int state) { return state >= 0 && state < kind.length ? kind[state] : 0; }

    public static boolean connectable(int state) { int k = kind(state); return (k >= FENCE && k <= WALL) || k == STAIRS || k == DOOR; }

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

    // ступеньки: углы (inner/outer) как в Java StairsBlock.getStairShape; facing 0 север,1 юг,2 запад,3 восток; half 0 верх
    private static final int[][] DIR = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final int[] CCW = {2, 3, 1, 0}, OPP = {1, 0, 3, 2};
    private static int sFacing(int st) { int b = base(st); return b < 0 ? -1 : (st - b) / 20; }
    private static int sHalf(int st) { int b = base(st); return b < 0 ? -1 : ((st - b) / 10) % 2; }

    private static boolean differs(int facing, int half, int x, int y, int z, int d, Lookup w) {
        int n = w.state(x + DIR[d][0], y, z + DIR[d][2]);
        return kind(n) != STAIRS || sFacing(n) != facing || sHalf(n) != half;
    }

    private static int stairs(int st, int x, int y, int z, Lookup w) {
        int b = base(st);
        if (b < 0) return st;
        int off = st - b, wl = off % 2, half = (off / 10) % 2, f = off / 20;
        int shape = 0;
        int front = w.state(x + DIR[f][0], y, z + DIR[f][2]);
        int back = w.state(x - DIR[f][0], y, z - DIR[f][2]);
        boolean done = false;
        if (kind(front) == STAIRS && sHalf(front) == half) {
            int d2 = sFacing(front);
            if ((d2 < 2) != (f < 2) && differs(f, half, x, y, z, OPP[d2], w)) { shape = d2 == CCW[f] ? 3 : 4; done = true; }
        }
        if (!done && kind(back) == STAIRS && sHalf(back) == half) {
            int d3 = sFacing(back);
            if ((d3 < 2) != (f < 2) && differs(f, half, x, y, z, d3, w)) shape = d3 == CCW[f] ? 1 : 2;
        }
        return b + ((f * 2 + half) * 5 + shape) * 2 + wl;
    }

    // двери: в 0.14 у верхней половины в meta только петля, а сторона и «открыта» — у нижней; Java ждёт всё в каждой половине.
    // порядок свойств: facing(4) half(upper,lower) hinge(left,right) open(true,false) powered(true,false)
    private static int door(int st, int x, int y, int z, Lookup w) {
        int b = base(st);
        if (b < 0) return st;
        int off = st - b;
        int powered = off % 2, open = (off / 2) % 2, hinge = (off / 4) % 2, half = (off / 8) % 2, f = off / 16;
        if (half == 0) { // верх: сторона и «открыта» — от нижней половины
            int lo = w.state(x, y - 1, z);
            if (kind(lo) == DOOR && base(lo) == b && ((lo - b) / 8) % 2 == 1) { int lf = (lo - b); open = (lf / 2) % 2; f = lf / 16; powered = lf % 2; }
        } else {           // низ: петля — от верхней
            int up = w.state(x, y + 1, z);
            if (kind(up) == DOOR && base(up) == b && ((up - b) / 8) % 2 == 0) hinge = ((up - b) / 4) % 2;
        }
        return b + (((f * 2 + half) * 2 + hinge) * 2 + open) * 2 + powered;
    }

    /** Правильное состояние для блока в (x,y,z) с учётом соседей; если блок не соединяемый — без изменений. */
    public static int fix(int state, int x, int y, int z, Lookup w) {
        int k = kind(state);
        if (k == STAIRS) return stairs(state, x, y, z, w);
        if (k == DOOR) return door(state, x, y, z, w);
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
