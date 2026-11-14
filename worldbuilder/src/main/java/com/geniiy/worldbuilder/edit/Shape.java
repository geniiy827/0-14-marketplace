package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.util.LongIntMap;
import com.geniiy.worldbuilder.util.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Фигура выделения. Любая фигура вписана в свой параллелепипед [min..max] — растягивая грани рамки,
 * получаем эллипсы и «блины». Поворот (кроме параллелепипеда) — произвольные углы, вокселизация пересчитывается.
 */
public class Shape {
    public enum Type {
        CUBOID("Параллелепипед"), ELLIPSOID("Эллипсоид / сфера"), CYLINDER("Цилиндр"), CONE("Конус"), PYRAMID("Пирамида"),
        TORUS("Тор"), HALF_SPHERE("Полусфера"), HALF_CYLINDER("Полуцилиндр"), POLYGON("Призма по контуру"), VOXELS("Произвольная маска");
        public final String ru; Type(String ru) { this.ru = ru; }
    }
    public enum Mode { NEW, ADD, SUBTRACT, INTERSECT }

    public Type type = Type.CUBOID;
    public Mode mode = Mode.ADD;
    public String name = "";
    public boolean hidden;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int axis = 1; // 0 X, 1 Y, 2 Z
    public boolean apexUp = true;     // конус/пирамида: вершина вверх
    public boolean stepped = true;    // пирамида ступенчатая
    public double minorRatio = 0.35;  // тор: толщина трубы относительно радиуса
    public boolean hollow; public int thickness = 1;
    public boolean wireframe;
    public boolean halfPositive = true; // полусфера: верхняя половина
    public double rotX, rotY, rotZ;   // градусы
    public List<int[]> polygon = new ArrayList<>(); // точки (x, z) призмы
    public LongIntMap voxels;         // для VOXELS
    public int version;

    public Shape() {}
    public Shape(Type t, int x1, int y1, int z1, int x2, int y2, int z2) { type = t; setBox(x1, y1, z1, x2, y2, z2); }

    public void setBox(int x1, int y1, int z1, int x2, int y2, int z2) {
        minX = Math.min(x1, x2); maxX = Math.max(x1, x2);
        minY = Math.min(y1, y2); maxY = Math.max(y1, y2);
        minZ = Math.min(z1, z2); maxZ = Math.max(z1, z2);
        version++;
    }
    public int min(int ax) { return ax == 0 ? minX : ax == 1 ? minY : minZ; }
    public int max(int ax) { return ax == 0 ? maxX : ax == 1 ? maxY : maxZ; }
    public int sx() { return maxX - minX + 1; }
    public int sy() { return maxY - minY + 1; }
    public int sz() { return maxZ - minZ + 1; }
    public void move(int dx, int dy, int dz) {
        minX += dx; maxX += dx; minY += dy; maxY += dy; minZ += dz; maxZ += dz;
        for (int[] p : polygon) { p[0] += dx; p[1] += dz; }
        if (voxels != null) {
            LongIntMap n = new LongIntMap(voxels.size());
            voxels.forEach((k, v) -> n.put(Pos.pack(Pos.x(k) + dx, Pos.y(k) + dy, Pos.z(k) + dz), 1));
            voxels = n;
        }
        version++;
    }

    public Shape copy() {
        Shape s = new Shape();
        s.type = type; s.mode = mode; s.name = name; s.hidden = hidden;
        s.minX = minX; s.minY = minY; s.minZ = minZ; s.maxX = maxX; s.maxY = maxY; s.maxZ = maxZ;
        s.axis = axis; s.apexUp = apexUp; s.stepped = stepped; s.minorRatio = minorRatio; s.hollow = hollow; s.thickness = thickness;
        s.wireframe = wireframe; s.halfPositive = halfPositive; s.rotX = rotX; s.rotY = rotY; s.rotZ = rotZ;
        for (int[] p : polygon) s.polygon.add(p.clone());
        if (voxels != null) { s.voxels = new LongIntMap(voxels.size()); voxels.forEach((k, v) -> s.voxels.put(k, 1)); }
        return s;
    }

    /** Габариты с учётом поворота (для обхода). */
    public int[] bounds() {
        if (type == Type.CUBOID || type == Type.VOXELS || type == Type.POLYGON || (rotX == 0 && rotY == 0 && rotZ == 0))
            return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
        double cx = (minX + maxX + 1) / 2.0, cy = (minY + maxY + 1) / 2.0, cz = (minZ + maxZ + 1) / 2.0;
        double r = Math.sqrt(sx() * sx() + sy() * sy() + sz() * sz()) / 2.0 + 1;
        return new int[]{(int) Math.floor(cx - r), (int) Math.max(0, Math.floor(cy - r)), (int) Math.floor(cz - r),
                (int) Math.ceil(cx + r), (int) Math.min(127, Math.ceil(cy + r)), (int) Math.ceil(cz + r)};
    }

    public long volumeBox() { int[] b = bounds(); return (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1); }

    private double[] rotM;
    private int rotVersion = -1;

    private double[] inverseRotation() {
        if (rotM != null && rotVersion == version) return rotM;
        double ax = Math.toRadians(-rotX), ay = Math.toRadians(-rotY), az = Math.toRadians(-rotZ);
        // R = Rx * Ry * Rz (обратный поворот)
        double cx = Math.cos(ax), sx = Math.sin(ax), cy = Math.cos(ay), sy = Math.sin(ay), cz = Math.cos(az), sz = Math.sin(az);
        double[] rz = {cz, -sz, 0, sz, cz, 0, 0, 0, 1};
        double[] ry = {cy, 0, sy, 0, 1, 0, -sy, 0, cy};
        double[] rx = {1, 0, 0, 0, cx, -sx, 0, sx, cx};
        rotM = mul(mul(rz, ry), rx);
        rotVersion = version;
        return rotM;
    }
    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { double s = 0; for (int k = 0; k < 3; k++) s += a[i * 3 + k] * b[k * 3 + j]; r[i * 3 + j] = s; }
        return r;
    }

    public boolean contains(int x, int y, int z) {
        if (!containsSolid(x, y, z)) return false;
        if (wireframe && type == Type.CUBOID) {
            int c = 0;
            if (x == minX || x == maxX) c++;
            if (y == minY || y == maxY) c++;
            if (z == minZ || z == maxZ) c++;
            return c >= 2;
        }
        if (hollow) {
            int t = Math.max(1, thickness);
            return !(containsSolid(x + t, y, z) && containsSolid(x - t, y, z) && containsSolid(x, y + t, z)
                    && containsSolid(x, y - t, z) && containsSolid(x, y, z + t) && containsSolid(x, y, z - t));
        }
        return true;
    }

    /** Принадлежность без учёта «полости». */
    public boolean containsSolid(int x, int y, int z) {
        switch (type) {
            case CUBOID: return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
            case VOXELS: return voxels != null && voxels.get(Pos.pack(x, y, z), 0) != 0;
            case POLYGON: return y >= minY && y <= maxY && inPolygon(x + 0.5, z + 0.5);
            default: break;
        }
        double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
        double cx = (minX + maxX + 1) / 2.0, cy = (minY + maxY + 1) / 2.0, cz = (minZ + maxZ + 1) / 2.0;
        double dx = px - cx, dy = py - cy, dz = pz - cz;
        if (rotX != 0 || rotY != 0 || rotZ != 0) {
            double[] m = inverseRotation();
            double nx = m[0] * dx + m[1] * dy + m[2] * dz, ny = m[3] * dx + m[4] * dy + m[5] * dz, nz = m[6] * dx + m[7] * dy + m[8] * dz;
            dx = nx; dy = ny; dz = nz;
        }
        double rx = sx() / 2.0, ry = sy() / 2.0, rz = sz() / 2.0;
        double u = dx / rx, v = dy / ry, w = dz / rz; // −1..1
        // переставляем оси так, чтобы «ось фигуры» была v
        double a, b, c; // a, c — поперечные, b — вдоль оси (−1..1)
        if (axis == 0) { b = u; a = v; c = w; } else if (axis == 2) { b = w; a = u; c = v; } else { b = v; a = u; c = w; }
        if (Math.abs(u) > 1.0001 || Math.abs(v) > 1.0001 || Math.abs(w) > 1.0001) return false;
        switch (type) {
            case ELLIPSOID: return u * u + v * v + w * w <= 1.0;
            case CYLINDER: return a * a + c * c <= 1.0;
            case CONE: {
                double t = (b + 1) / 2; // 0..1 вдоль оси
                double r = apexUp ? 1 - t : t;
                return a * a + c * c <= r * r;
            }
            case PYRAMID: {
                double t = (b + 1) / 2;
                double r = apexUp ? 1 - t : t;
                if (stepped) {
                    int len = axis == 0 ? sx() : axis == 2 ? sz() : sy();
                    int step = (int) Math.floor(t * len);
                    r = apexUp ? 1 - (double) step / len : (double) (step + 1) / len;
                }
                return Math.abs(a) <= r + 1e-9 && Math.abs(c) <= r + 1e-9;
            }
            case TORUS: {
                double m = Math.max(0.05, Math.min(0.95, minorRatio));
                double q = Math.sqrt(a * a + c * c);
                double d = (q - (1 - m)) / m;
                return d * d + b * b <= 1.0;
            }
            case HALF_SPHERE: {
                double bb = halfPositive ? (b + 1) / 2 : (1 - b) / 2; // 0..1 от плоскости среза
                return a * a + c * c + bb * bb <= 1.0;
            }
            case HALF_CYLINDER: {
                // ось цилиндра — ось фигуры, срез — по второй поперечной оси
                double cc = halfPositive ? (c + 1) / 2 : (1 - c) / 2;
                return a * a + cc * cc <= 1.0;
            }
            default: return false;
        }
    }

    private boolean inPolygon(double x, double z) {
        boolean in = false;
        int n = polygon.size();
        if (n < 3) return false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i)[0] + 0.5, zi = polygon.get(i)[1] + 0.5, xj = polygon.get(j)[0] + 0.5, zj = polygon.get(j)[1] + 0.5;
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    public void updatePolygonBounds() {
        if (polygon.isEmpty()) return;
        int ax = Integer.MAX_VALUE, az = Integer.MAX_VALUE, bx = Integer.MIN_VALUE, bz = Integer.MIN_VALUE;
        for (int[] p : polygon) { ax = Math.min(ax, p[0]); az = Math.min(az, p[1]); bx = Math.max(bx, p[0]); bz = Math.max(bz, p[1]); }
        minX = ax; maxX = bx; minZ = az; maxZ = bz; version++;
    }

    public String sizeText() {
        String s = sx() + " × " + sy() + " × " + sz();
        if (type == Type.ELLIPSOID && sx() == sy() && sy() == sz()) s += " (r = " + Gui2.fmt(sx() / 2.0) + ")";
        return s;
    }

    static final class Gui2 { static String fmt(double v) { return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v); } }
}
