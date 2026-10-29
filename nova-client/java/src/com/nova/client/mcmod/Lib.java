package com.nova.client.mcmod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** math.* and vec.* functions implemented in Java (registry: mcmod/api/math.tsv, vec.tsv with impl "java:Lib") */
final class Lib {
    private static final Random RND = new Random();

    static Object call(String name, List<Object> a, int line) {
        switch (name) {
            case "math.tan": return Math.tan(Math.toRadians(n(a, 0)));
            case "math.asin": return Math.toDegrees(Math.asin(n(a, 0)));
            case "math.acos": return Math.toDegrees(Math.acos(n(a, 0)));
            case "math.atan": return Math.toDegrees(Math.atan(n(a, 0)));
            case "math.atan2": return Math.toDegrees(Math.atan2(n(a, 0), n(a, 1)));
            case "math.pow": return Math.pow(n(a, 0), n(a, 1));
            case "math.exp": return Math.exp(n(a, 0));
            case "math.log": return a.get(1) == null ? Math.log(n(a, 0)) : Math.log(n(a, 0)) / Math.log(n(a, 1));
            case "math.sign": return Math.signum(n(a, 0));
            case "math.mod": { double m = n(a, 1); if (m == 0) throw new ScriptError("math.mod: деление на 0", line); double r = n(a, 0) % m; return r < 0 ? r + Math.abs(m) : r; }
            case "math.lerp": return n(a, 0) + (n(a, 1) - n(a, 0)) * n(a, 2);
            case "math.map": { double a1 = n(a, 1), b1 = n(a, 2); if (b1 == a1) return n(a, 3); return n(a, 3) + (n(a, 0) - a1) / (b1 - a1) * (n(a, 4) - n(a, 3)); }
            case "math.roundTo": { double k = Math.pow(10, Math.max(0, Math.min(10, (int) n(a, 1)))); return Math.round(n(a, 0) * k) / k; }
            case "math.snap": { double st = n(a, 1); return st == 0 ? n(a, 0) : Math.round(n(a, 0) / st) * st; }
            case "math.hypot": return Math.hypot(n(a, 0), n(a, 1));
            case "math.rad": return Math.toRadians(n(a, 0));
            case "math.deg": return Math.toDegrees(n(a, 0));
            case "math.angleDiff": { double d = (n(a, 1) - n(a, 0)) % 360; if (d > 180) d -= 360; if (d < -180) d += 360; return d; }
            case "math.rand": return n(a, 0) + RND.nextDouble() * (n(a, 1) - n(a, 0));
            case "math.randInt": { long lo = (long) Math.ceil(Math.min(n(a, 0), n(a, 1))), hi = (long) Math.floor(Math.max(n(a, 0), n(a, 1))); return (double) (lo + (long) (RND.nextDouble() * (hi - lo + 1))); }
            case "math.chance": return RND.nextDouble() < n(a, 0);
            case "math.sum": case "math.avg": case "math.minOf": case "math.maxOf": {
                List<?> l = list(a.get(0), name, line);
                if (l.isEmpty()) return name.equals("math.sum") ? 0.0 : null;
                double s = 0, mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
                for (Object o : l) {
                    if (!(o instanceof Double)) throw new ScriptError(name + ": в списке не число", line);
                    double v = (Double) o; s += v; mn = Math.min(mn, v); mx = Math.max(mx, v);
                }
                return name.equals("math.sum") ? s : name.equals("math.avg") ? s / l.size() : name.equals("math.minOf") ? mn : mx;
            }
            case "math.e": return Math.E;
            case "math.noise": return noise(n(a, 0), n(a, 1), n(a, 2));
            case "vec.new": return v(n(a, 0), n(a, 1), n(a, 2));
            case "vec.add": { double[] p = vec(a.get(0), line), q = vec(a.get(1), line); return v(p[0] + q[0], p[1] + q[1], p[2] + q[2]); }
            case "vec.sub": { double[] p = vec(a.get(0), line), q = vec(a.get(1), line); return v(p[0] - q[0], p[1] - q[1], p[2] - q[2]); }
            case "vec.mul": { double[] p = vec(a.get(0), line); double k = n(a, 1); return v(p[0] * k, p[1] * k, p[2] * k); }
            case "vec.dot": { double[] p = vec(a.get(0), line), q = vec(a.get(1), line); return p[0] * q[0] + p[1] * q[1] + p[2] * q[2]; }
            case "vec.cross": { double[] p = vec(a.get(0), line), q = vec(a.get(1), line); return v(p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]); }
            case "vec.len": { double[] p = vec(a.get(0), line); return Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); }
            case "vec.norm": { double[] p = vec(a.get(0), line); double l = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); return l == 0 ? v(0, 0, 0) : v(p[0] / l, p[1] / l, p[2] / l); }
            case "vec.dist": { double[] p = vec(a.get(0), line), q = vec(a.get(1), line); double dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2]; return Math.sqrt(dx * dx + dy * dy + dz * dz); }
            case "vec.lerp": { double[] p = vec(a.get(0), line), q = vec(a.get(1), line); double t = n(a, 2); return v(p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t); }
            case "vec.floor": { double[] p = vec(a.get(0), line); return v(Math.floor(p[0]), Math.floor(p[1]), Math.floor(p[2])); }
            case "vec.rotY": { double[] p = vec(a.get(0), line); double r = Math.toRadians(n(a, 1)), c = Math.cos(r), s = Math.sin(r); return v(p[0] * c - p[2] * s, p[1], p[0] * s + p[2] * c); }
            case "vec.fromAngles": { double y = Math.toRadians(n(a, 0)), p = Math.toRadians(n(a, 1)), c = Math.cos(p); return v(-Math.sin(y) * c, -Math.sin(p), Math.cos(y) * c); }
            case "vec.yaw": { double[] p = vec(a.get(0), line); return Math.toDegrees(Math.atan2(-p[0], p[2])); }
            case "vec.pitch": { double[] p = vec(a.get(0), line); return Math.toDegrees(Math.atan2(-p[1], Math.hypot(p[0], p[2]))); }
            default: throw new ScriptError("функция " + name + " не реализована", line);
        }
    }

    private static double n(List<Object> a, int i) { Object o = a.get(i); return o instanceof Double ? (Double) o : 0; }

    private static Map<String, Object> v(double x, double y, double z) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", x); m.put("y", y); m.put("z", z);
        return m;
    }

    private static double[] vec(Object o, int line) {
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            return new double[]{d(m.get("x")), d(m.get("y")), d(m.get("z"))};
        }
        if (o instanceof List && ((List<?>) o).size() >= 3) {
            List<?> l = (List<?>) o;
            return new double[]{d(l.get(0)), d(l.get(1)), d(l.get(2))};
        }
        throw new ScriptError("нужен вектор {x, y, z}, а не " + Interp.typeName(o), line);
    }

    private static double d(Object o) { return o instanceof Double ? (Double) o : 0; }

    private static List<?> list(Object o, String name, int line) {
        if (!(o instanceof List)) throw new ScriptError(name + ": нужен список", line);
        return (List<?>) o;
    }

    // ---- Perlin noise (Ken Perlin's improved noise)
    private static final int[] P = new int[512];
    static {
        int[] p = {151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,240,21,10,23,190,6,148,
            247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,171,168,68,175,74,165,
            71,134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54,65,25,63,161,
            1,216,80,73,209,76,132,187,208,89,18,169,200,196,135,130,116,188,159,86,164,100,109,198,173,186,3,64,52,217,226,250,124,
            123,5,202,38,147,118,126,255,82,85,212,207,206,59,227,47,16,58,17,182,189,28,42,223,183,170,213,119,248,152,2,44,154,163,
            70,221,153,101,155,167,43,172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,246,97,228,251,34,242,193,
            238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,107,49,192,214,31,181,199,106,157,184,84,204,176,115,121,50,45,
            127,4,150,254,138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180};
        for (int i = 0; i < 512; i++) P[i] = p[i & 255];
    }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double grad(int h, double x, double y, double z) {
        h &= 15; double u = h < 8 ? x : y, v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    static double noise(double x, double y, double z) {
        int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255, Z = (int) Math.floor(z) & 255;
        x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
        double u = fade(x), v = fade(y), w = fade(z);
        int A = P[X] + Y, AA = P[A] + Z, AB = P[A + 1] + Z, B = P[X + 1] + Y, BA = P[B] + Z, BB = P[B + 1] + Z;
        return lerp(w, lerp(v, lerp(u, grad(P[AA], x, y, z), grad(P[BA], x - 1, y, z)), lerp(u, grad(P[AB], x, y - 1, z), grad(P[BB], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(P[AA + 1], x, y, z - 1), grad(P[BA + 1], x - 1, y, z - 1)), lerp(u, grad(P[AB + 1], x, y - 1, z - 1), grad(P[BB + 1], x - 1, y - 1, z - 1))));
    }
}
