package com.geniiy.minebot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** A* по блокам: ходьба, диагонали, прыжок на 1 блок, спуск до 3 блоков, плавание. */
public final class PathFinder {
    public static final class Node implements Comparable<Node> {
        final int x, y, z; double g, f; Node parent;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int compareTo(Node o) { return Double.compare(f, o.f); }
    }

    private final World w;
    public PathFinder(World w) { this.w = w; }

    static long k(int x, int y, int z) { return (((long) x & 0x3ffffff) << 38) | (((long) z & 0x3ffffff) << 12) | (y & 0xfff); }

    /** Можно ли стоять ногами в (x,y,z). */
    public boolean standable(int x, int y, int z) {
        if (!w.passable(x, y, z) || !w.passable(x, y + 1, z)) return false;
        if (w.dangerous(x, y, z) || w.dangerous(x, y + 1, z)) return false;
        if (w.liquid(x, y, z)) return true; // плывём
        int below = w.id(x, y - 1, z);
        if (below < 0) return false;
        if (below == 81 || below == 10 || below == 11) return false; // кактус, лава
        return w.solid(x, y - 1, z);
    }

    /** Найти путь. Если цель недостижима — путь к ближайшей к ней изученной точке. */
    public List<int[]> find(int sx, int sy, int sz, int tx, int ty, int tz, double near, int maxNodes) {
        Map<Long, Node> all = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Node s = new Node(sx, sy, sz);
        s.g = 0; s.f = h(sx, sy, sz, tx, ty, tz);
        all.put(k(sx, sy, sz), s);
        open.add(s);
        Node best = s; double bestH = s.f;
        int visited = 0;
        while (!open.isEmpty() && visited < maxNodes) {
            Node c = open.poll();
            visited++;
            double hc = h(c.x, c.y, c.z, tx, ty, tz);
            if (hc < bestH) { bestH = hc; best = c; }
            if (hc <= near) { best = c; break; }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean diag = dx != 0 && dz != 0;
                int nx = c.x + dx, nz = c.z + dz;
                for (int dy = 1; dy >= -3; dy--) {
                    int ny = c.y + dy;
                    if (!standable(nx, ny, nz)) continue;
                    if (dy == 1) {
                        // прыжок: над головой должно быть место
                        if (!w.passable(c.x, c.y + 2, c.z)) continue;
                    }
                    if (dy < 0) {
                        // спуск: путь вниз свободен
                        boolean ok = true;
                        for (int yy = ny + 1; yy <= c.y + 1; yy++) if (!w.passable(nx, yy, nz)) { ok = false; break; }
                        if (!ok) continue;
                    }
                    if (diag) {
                        int hy = Math.max(c.y, ny);
                        if (!w.passable(c.x + dx, hy, c.z) || !w.passable(c.x + dx, hy + 1, c.z)
                                || !w.passable(c.x, hy, c.z + dz) || !w.passable(c.x, hy + 1, c.z + dz)) continue;
                    }
                    double cost = diag ? 1.414 : 1.0;
                    if (dy == 1) cost += 0.6;
                    if (dy < 0) cost += 0.3 * -dy;
                    if (w.liquid(nx, ny, nz)) cost += 1.5;
                    long key = k(nx, ny, nz);
                    Node n = all.get(key);
                    double g = c.g + cost;
                    if (n == null) { n = new Node(nx, ny, nz); all.put(key, n); }
                    else if (g >= n.g) break;
                    n.g = g; n.parent = c; n.f = g + h(nx, ny, nz, tx, ty, tz);
                    open.remove(n);
                    open.add(n);
                    break; // для каждой колонки берём первую подходящую высоту (сверху вниз)
                }
            }
        }
        List<int[]> path = new ArrayList<>();
        for (Node n = best; n != null; n = n.parent) path.add(new int[]{n.x, n.y, n.z});
        Collections.reverse(path);
        return path;
    }

    static double h(int x, int y, int z, int tx, int ty, int tz) {
        double dx = x - tx, dy = y - ty, dz = z - tz;
        return Math.sqrt(dx * dx + dy * dy * 1.5 + dz * dz);
    }
}
