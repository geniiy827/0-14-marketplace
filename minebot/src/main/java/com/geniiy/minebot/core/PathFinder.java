package com.geniiy.minebot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* по блокам: ходьба, диагонали, прыжок на блок вверх, спуск до 3 блоков,
 * плавание, лестницы/лианы и паркур (прыжки через ямы 1–2 блока),
 * если обычного пути нет — паркур дороже, поэтому используется только при необходимости.
 */
public final class PathFinder {
    /** Тип шага в точку. */
    public static final int WALK = 0, JUMP_UP = 1, DROP = 2, GAP = 3, CLIMB = 4, SWIM = 5;

    public static final class Node implements Comparable<Node> {
        final int x, y, z; double g, f; Node parent; int how;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int compareTo(Node o) { return Double.compare(f, o.f); }
    }

    private final World w;
    public boolean allowParkour = true;
    public PathFinder(World w) { this.w = w; }

    static long k(int x, int y, int z) { return (((long) x & 0x3ffffff) << 38) | (((long) z & 0x3ffffff) << 12) | (y & 0xfff); }

    public static boolean climbable(int id) { return id == 65 || id == 106; }

    /** Можно ли стоять ногами в (x,y,z). */
    public boolean standable(int x, int y, int z) {
        if (!w.passable(x, y, z) || !w.passable(x, y + 1, z)) return false;
        if (w.dangerous(x, y, z) || w.dangerous(x, y + 1, z)) return false;
        if (w.liquid(x, y, z)) return true;
        if (climbable(w.id(x, y, z))) return true;
        int below = w.id(x, y - 1, z);
        if (below < 0) return false;
        if (below == 81 || below == 10 || below == 11 || below == 51) return false;
        return w.solid(x, y - 1, z);
    }

    private boolean air(int x, int y, int z) { return w.passable(x, y, z) && !w.dangerous(x, y, z) && !w.liquid(x, y, z); }

    /** Найти путь. Если цель недостижима — путь к ближайшей к ней изученной точке. Шаг = {x,y,z,how}. */
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
            boolean cLiquid = w.liquid(c.x, c.y, c.z);
            // лестница / лиана / вода: вертикально
            if (climbable(w.id(c.x, c.y, c.z)) || cLiquid) {
                if (standable(c.x, c.y + 1, c.z)) relax(all, open, c, c.x, c.y + 1, c.z, 1.2, CLIMB, tx, ty, tz);
            }
            if (climbable(w.id(c.x, c.y - 1, c.z)) && standable(c.x, c.y - 1, c.z)) relax(all, open, c, c.x, c.y - 1, c.z, 1.0, CLIMB, tx, ty, tz);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean diag = dx != 0 && dz != 0;
                int nx = c.x + dx, nz = c.z + dz;
                boolean found = false;
                for (int dy = 1; dy >= -3; dy--) {
                    int ny = c.y + dy;
                    if (!standable(nx, ny, nz)) continue;
                    if (dy == 1 && !w.passable(c.x, c.y + 2, c.z)) continue;
                    if (dy < 0) {
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
                    int how = WALK;
                    if (dy == 1) { cost += 0.6; how = JUMP_UP; }
                    if (dy < 0) { cost += 0.3 * -dy; how = DROP; }
                    if (w.liquid(nx, ny, nz)) { cost += 1.5; how = SWIM; }
                    relax(all, open, c, nx, ny, nz, cost, how, tx, ty, tz);
                    found = true;
                    break;
                }
                // паркур: прыжок через яму (только прямо по осям)
                if (!found && !diag && allowParkour && !cLiquid && w.solid(c.x, c.y - 1, c.z)) {
                    if (!air(c.x, c.y + 2, c.z)) continue;
                    for (int gap = 1; gap <= 2; gap++) {
                        // промежуточные колонки должны быть свободны на высоте прыжка
                        boolean clear = true;
                        for (int i = 1; i <= gap; i++) {
                            int ix = c.x + dx * i, iz = c.z + dz * i;
                            if (!air(ix, c.y, iz) || !air(ix, c.y + 1, iz) || !air(ix, c.y + 2, iz)) { clear = false; break; }
                        }
                        if (!clear) break;
                        int lx = c.x + dx * (gap + 1), lz = c.z + dz * (gap + 1);
                        for (int dy = (gap == 1 ? 1 : 0); dy >= -2; dy--) {
                            if (!standable(lx, c.y + dy, lz)) continue;
                            if (dy == 1 && !air(lx, c.y + 2, lz)) continue;
                            relax(all, open, c, lx, c.y + dy, lz, 3.0 + gap * 1.5 + (dy == 1 ? 1.5 : 0), GAP, tx, ty, tz);
                            break;
                        }
                    }
                }
            }
        }
        List<int[]> path = new ArrayList<>();
        for (Node n = best; n != null; n = n.parent) path.add(new int[]{n.x, n.y, n.z, n.how});
        Collections.reverse(path);
        return path;
    }

    private void relax(Map<Long, Node> all, PriorityQueue<Node> open, Node c, int nx, int ny, int nz, double cost, int how,
                       int tx, int ty, int tz) {
        long key = k(nx, ny, nz);
        Node n = all.get(key);
        double g = c.g + cost;
        if (n == null) { n = new Node(nx, ny, nz); all.put(key, n); }
        else if (g >= n.g) return;
        n.g = g; n.parent = c; n.how = how; n.f = g + h(nx, ny, nz, tx, ty, tz);
        open.remove(n);
        open.add(n);
    }

    static double h(int x, int y, int z, int tx, int ty, int tz) {
        double dx = x - tx, dy = y - ty, dz = z - tz;
        return Math.sqrt(dx * dx + dy * dy * 1.5 + dz * dz);
    }
}
