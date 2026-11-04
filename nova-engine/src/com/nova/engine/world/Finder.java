package com.nova.engine.world;

import java.util.ArrayList;
import java.util.List;

/** Locates villages, desert pyramids and witch huts around a chunk with the same feature checks as the generator. */
public final class Finder {
    private Finder() { }

    public static final String[] TYPE = {"Деревня", "Пирамида", "Хижина ведьмы", "Заброшенная шахта"};

    /** {type, x, z, distance} sorted by distance, within r chunks */
    public static List<int[]> find(World w, int cx, int cz, int r) {
        Generator g = w.gen;
        int seed = (int) w.seed;
        Village v = new Village(g, seed);
        Scattered s = new Scattered(g, seed);
        Mineshaft m = new Mineshaft(g, seed);
        List<int[]> out = new ArrayList<int[]>();
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
            McRandom vr = v.featureRandom(x, z);
            if (vr != null) {
                Village.Start st = v.create(vr, x, z);
                if (st.valid) add(out, 0, (x << 4) + 5, (z << 4) + 5, cx, cz);
            }
            Mineshaft.Start ms = m.feature(x, z);
            if (ms != null) { Mineshaft.Box rb = ms.pieces.get(0).bb; add(out, 3, (rb.x0 + rb.x1) / 2, (rb.z0 + rb.z1) / 2, cx, cz); }
            Village.Piece p = s.feature(x, z);
            if (p != null) add(out, p.type == Scattered.PYRAMID ? 1 : 2, (p.bb.x0 + p.bb.x1) / 2, (p.bb.z0 + p.bb.z1) / 2, cx, cz);
        }
        java.util.Collections.sort(out, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[3] - b[3]; }
        });
        return out;
    }

    private static void add(List<int[]> out, int type, int x, int z, int cx, int cz) {
        int dx = x - (cx << 4), dz = z - (cz << 4);
        out.add(new int[]{type, x, z, (int) Math.sqrt((double) dx * dx + (double) dz * dz)});
    }
}
