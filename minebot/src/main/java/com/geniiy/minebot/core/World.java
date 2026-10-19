package com.geniiy.minebot.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** То, что бот знает о мире: блоки загруженных чанков и сущности. */
public final class World {
    public static final class Chunk {
        public final byte[] ids = new byte[32768];
        public final byte[] meta = new byte[16384];
    }

    public static final class Ent {
        public final long eid;
        public int type;            // 63 = игрок
        public String name = "";
        public float x, y, z, yaw, pitch; // y — ноги
        public long seen = System.currentTimeMillis();
        public double vx, vz;        // скорость (блоков за такт), оценка
        public Ent(long eid) { this.eid = eid; }
        /** Обновить позицию и оценить скорость. */
        public void moveTo(float nx, float ny, float nz) {
            long now = System.currentTimeMillis();
            double dt = Math.max(1, (now - seen) / 50.0);
            double jx = nx - x, jz = nz - z;
            if (now - seen < 1000 && jx * jx + jz * jz < 4) { vx = vx * 0.5 + jx / dt * 0.5; vz = vz * 0.5 + jz / dt * 0.5; }
            else { vx = 0; vz = 0; } // телепорт или давно не двигался
            double sp = Math.sqrt(vx * vx + vz * vz);
            if (sp > 0.6) { vx *= 0.6 / sp; vz *= 0.6 / sp; }
            x = nx; y = ny; z = nz; seen = now;
        }
        public boolean isPlayer() { return type == 63; }
        /** Скорость с учётом того, что стоящая сущность не шлёт обновлений. */
        public double curVx() { return System.currentTimeMillis() - seen < 400 ? vx : 0; }
        public double curVz() { return System.currentTimeMillis() - seen < 400 ? vz : 0; }
        public double dist2(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz; return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    public final Map<Long, Ent> ents = new ConcurrentHashMap<>();
    /** eid → ник (из списка игроков). */
    public final Map<Long, String> names = new ConcurrentHashMap<>();

    public String nameOf(long eid) { String n = names.get(eid); return n == null ? "" : n; }

    static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    /** order 0 — столбцами (x,z,y), 1 — слоями (y,z,x). */
    public void putChunk(int cx, int cz, byte[] data, int order) {
        if (data.length < 32768 + 16384) return;
        Chunk c = new Chunk();
        if (order == 1) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 128; y++) {
                int src = (y << 8) | (z << 4) | x, dst = (x << 11) | (z << 7) | y;
                c.ids[dst] = data[src];
                int sv = data[32768 + (src >> 1)] & 0xff;
                int m = (src & 1) == 0 ? sv & 15 : sv >> 4;
                int dv = c.meta[dst >> 1] & 0xff;
                c.meta[dst >> 1] = (byte) ((dst & 1) == 0 ? (dv & 0xf0) | m : (dv & 0x0f) | (m << 4));
            }
        } else {
            System.arraycopy(data, 0, c.ids, 0, 32768);
            System.arraycopy(data, 32768, c.meta, 0, 16384);
        }
        chunks.put(key(cx, cz), c);
    }

    public int chunkCount() { return chunks.size(); }

    public boolean loaded(int x, int z) { return chunks.containsKey(key(x >> 4, z >> 4)); }

    /** -1 если чанк неизвестен. */
    public int id(int x, int y, int z) {
        if (y < 0) return 7;
        if (y > 127) return 0;
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        if (c == null) return -1;
        return c.ids[((x & 15) << 11) | ((z & 15) << 7) | y] & 0xff;
    }

    public int meta(int x, int y, int z) {
        if (y < 0 || y > 127) return 0;
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        if (c == null) return 0;
        int i = ((x & 15) << 11) | ((z & 15) << 7) | y;
        int v = c.meta[i >> 1] & 0xff;
        return (i & 1) == 0 ? v & 15 : v >> 4;
    }

    public void set(int x, int y, int z, int id, int m) {
        if (y < 0 || y > 127) return;
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        if (c == null) return;
        int i = ((x & 15) << 11) | ((z & 15) << 7) | y;
        c.ids[i] = (byte) id;
        int v = c.meta[i >> 1] & 0xff;
        v = (i & 1) == 0 ? (v & 0xf0) | (m & 15) : (v & 0x0f) | ((m & 15) << 4);
        c.meta[i >> 1] = (byte) v;
    }

    // ---------- свойства блоков ----------
    private static final boolean[] PASS = new boolean[256];   // можно пройти насквозь
    private static final boolean[] DANGER = new boolean[256]; // нельзя наступать / заходить
    private static final boolean[] LIQUID = new boolean[256];
    static {
        int[] pass = {0, 6, 8, 9, 10, 11, 27, 28, 30, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59, 63, 66, 68, 69, 70, 72, 75, 76,
                65, 77, 78, 83, 90, 104, 105, 106, 115, 126, 131, 132, 141, 142, 143, 147, 148, 171, 175, 244};
        for (int p : pass) PASS[p] = true;
        int[] danger = {10, 11, 51, 81, 30, 90};
        for (int d : danger) DANGER[d] = true;
        LIQUID[8] = LIQUID[9] = LIQUID[10] = LIQUID[11] = true;
    }

    /** Блок пропускает тело (неизвестные чанки считаем твёрдыми). */
    public boolean passable(int x, int y, int z) {
        int id = id(x, y, z);
        if (id < 0) return false;
        return PASS[id];
    }

    public boolean solid(int x, int y, int z) {
        int id = id(x, y, z);
        if (id < 0) return true;
        return !PASS[id];
    }

    public boolean dangerous(int x, int y, int z) {
        int id = id(x, y, z);
        return id >= 0 && DANGER[id];
    }

    public boolean liquid(int x, int y, int z) {
        int id = id(x, y, z);
        return id >= 0 && LIQUID[id];
    }

    /** Высота верхней грани блока (для полублоков 0.5). */
    public double topHeight(int x, int y, int z) {
        int id = id(x, y, z);
        if (id < 0) return 1;
        if (PASS[id]) return 0;
        if (id == 44 || id == 158) { // полублоки
            return (meta(x, y, z) & 8) != 0 ? 1.0 : 0.5;
        }
        if (id == 78) return 0.125;
        if (id == 171) return 0.0625;
        if (id == 96 || id == 167) return (meta(x, y, z) & 8) != 0 ? 1 : 0.1875; // люки
        if (id == 85 || id == 113 || id == 139 || id == 107 || (id >= 183 && id <= 187)) return 1.5; // заборы
        if (id == 26) return 0.5625;
        if (id == 92) return 0.5;
        if (id == 60 || id == 198) return 0.9375;
        if (id == 54 || id == 146 || id == 130) return 0.875;
        return 1;
    }

    public void clear() { chunks.clear(); ents.clear(); names.clear(); }

    public Ent findPlayer(String name) {
        Ent best = null;
        for (Ent e : ents.values()) {
            if (!e.isPlayer()) continue;
            if (e.name.isEmpty()) e.name = nameOf(e.eid);
            if (name == null || e.name.equalsIgnoreCase(name)) return e;
        }
        if (name != null) {
            String n = name.toLowerCase();
            for (Ent e : ents.values()) if (e.isPlayer() && e.name.toLowerCase().startsWith(n)) best = e;
        }
        return best;
    }

    public Ent nearestPlayer(double x, double y, double z, long exclude) {
        Ent best = null; double bd = Double.MAX_VALUE;
        for (Ent e : ents.values()) {
            if (!e.isPlayer() || e.eid == exclude) continue;
            double d = e.dist2(x, y, z);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    public Map<Long, Ent> snapshot() { return new HashMap<>(ents); }
}
