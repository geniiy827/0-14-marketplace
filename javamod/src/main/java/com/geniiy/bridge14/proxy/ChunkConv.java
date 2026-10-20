package com.geniiy.bridge14.proxy;

import java.util.HashMap;
import java.util.Map;

/** Перевод чанка 0.14 (16×16×128) в пакет чанка Java 1.20.1 (высота мира 0..127). */
public final class ChunkConv {
    public static final int SECTIONS = 8;   // 128 / 16
    // id типов блок-сущностей 1.20.1 (порядок BlockEntityType)
    static final int BE_CHEST = 1, BE_TRAPPED = 2, BE_ENDER = 3, BE_SIGN = 7, BE_ENCHANT = 12, BE_END_PORTAL = 13,
            BE_SKULL = 15, BE_BANNER = 19, BE_BED = 24;

    /** Хранимый чанк: java-состояния блоков [x<<11|z<<7|y] и свет. */
    public static final class Chunk {
        public final int[] state = new int[32768];
        public final byte[] pe = new byte[32768];   // id блока 0.14 (для логики)
        public final byte[] sky = new byte[16384];  // ниблы в порядке 0.14
        public final byte[] blk = new byte[16384];
        /** Блок-сущности 0.14 (таблички и т.п.) по индексу блока. */
        public final Map<Integer, Map<String, Object>> tiles = new HashMap<>();
        public long received = System.currentTimeMillis();
    }

    /** Тип блок-сущности Java для блока 0.14 (или -1). */
    static int beType(int peId) {
        switch (peId) {
            case 54: return BE_CHEST; case 146: return BE_TRAPPED; case 130: return BE_ENDER;
            case 63: case 68: return BE_SIGN; case 144: return BE_SKULL; case 26: return BE_BED;
            case 176: case 177: return BE_BANNER; case 116: return BE_ENCHANT; case 119: return BE_END_PORTAL;
            default: return -1;
        }
    }

    /** NBT блок-сущности для Java (у табличек — текст). */
    static byte[] beNbt(Chunk c, int i) {
        int id = c.pe[i] & 0xff;
        if (id == 63 || id == 68) {
            Map<String, Object> t = c.tiles.get(i);
            return Nbt.signJava(t != null ? Nbt.signLines(t) : null);
        }
        return new byte[]{0};
    }

    static int idx(int x, int y, int z) { return (x << 11) | (z << 7) | y; }

    /** Разобрать данные 0.14. order 0 — столбцы, 1 — слои. */
    public static Chunk fromPe(byte[] d, int order) {
        Chunk c = new Chunk();
        if (d.length < 32768 + 16384) return c;
        boolean haveLight = d.length >= 32768 + 16384 * 3;
        boolean anySky = false;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 128; y++) {
            int src = order == 1 ? ((y << 8) | (z << 4) | x) : idx(x, y, z);
            int dst = idx(x, y, z);
            int id = d[src] & 0xff;
            int mv = d[32768 + (src >> 1)] & 0xff;
            int meta = (src & 1) == 0 ? mv & 15 : mv >> 4;
            c.pe[dst] = (byte) id;
            c.state[dst] = Tables.block(id, meta);
            if (haveLight) {
                int sv = d[32768 + 16384 + (src >> 1)] & 0xff, bv = d[32768 + 32768 + (src >> 1)] & 0xff;
                int s = (src & 1) == 0 ? sv & 15 : sv >> 4, bl = (src & 1) == 0 ? bv & 15 : bv >> 4;
                if (s != 0) anySky = true;
                setNib(c.sky, dst, s); setNib(c.blk, dst, bl);
            }
        }
        if (!anySky) computeSky(c); // сервер не прислал свет — считаем простое небо
        // карта высот (256), цвета биомов (1024), доп. данные, затем блок-сущности в NBT (LE)
        int off = 32768 + 16384 * 3 + 256 + 1024;
        if (d.length >= off + 4) {
            try {
                com.geniiy.bridge14.pe.Buf b = new com.geniiy.bridge14.pe.Buf(d, off, d.length);
                int extra = b.li32();
                if (extra >= 0 && extra < 100000) {
                    b.skip(extra * 6);
                    for (Map<String, Object> t : Nbt.readAllLE(b)) {
                        int tx = Nbt.intOf(t.get("x"), 0), ty = Nbt.intOf(t.get("y"), -1), tz = Nbt.intOf(t.get("z"), 0);
                        if (ty >= 0 && ty < 128) c.tiles.put(idx(tx & 15, ty, tz & 15), t);
                    }
                }
            } catch (Exception ignored) {}
        }
        return c;
    }

    static void setNib(byte[] a, int i, int v) {
        int o = a[i >> 1] & 0xff;
        a[i >> 1] = (byte) ((i & 1) == 0 ? (o & 0xf0) | (v & 15) : (o & 0x0f) | ((v & 15) << 4));
    }
    static int nib(byte[] a, int i) { int o = a[i >> 1] & 0xff; return (i & 1) == 0 ? o & 15 : o >> 4; }

    private static boolean transparent(int peId) {
        switch (peId) {
            case 0: case 6: case 8: case 9: case 18: case 20: case 31: case 32: case 37: case 38: case 39: case 40: case 50:
            case 51: case 55: case 59: case 63: case 65: case 66: case 68: case 69: case 70: case 72: case 75: case 76: case 77:
            case 78: case 83: case 85: case 101: case 102: case 104: case 105: case 106: case 107: case 111: case 113: case 141:
            case 142: case 143: case 161: case 171: case 175: case 244: return true;
            default: return false;
        }
    }

    private static void computeSky(Chunk c) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int light = 15;
            for (int y = 127; y >= 0; y--) {
                int id = c.pe[idx(x, y, z)] & 0xff;
                if (!transparent(id)) light = 0;
                else if (id == 8 || id == 9 || id == 18 || id == 161) light = Math.max(0, light - 2);
                setNib(c.sky, idx(x, y, z), light);
            }
        }
    }

    /** Тело пакета map_chunk (без id пакета). */
    public static byte[] toJava(int cx, int cz, Chunk c) {
        JBuf p = new JBuf();
        p.i32(cx).i32(cz);
        // карты высот
        long[] hm = heightmap(c);
        p.u8(10).i16(0);                // корневой compound без имени (сетевой NBT 1.20.1 с пустым именем)
        for (String key : new String[]{"MOTION_BLOCKING", "WORLD_SURFACE"}) {
            byte[] kb = key.getBytes();
            p.u8(12); p.i16(kb.length); p.raw(kb); p.i32(hm.length); for (long l : hm) p.i64(l);
        }
        p.u8(0);
        // секции
        JBuf sec = new JBuf();
        int[] tmp = new int[4096];
        for (int s = 0; s < SECTIONS; s++) {
            int nonAir = 0;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int st = c.state[idx(x, s * 16 + y, z)];
                tmp[(y << 8) | (z << 4) | x] = st;
                if (st != 0) nonAir++;
            }
            sec.i16(nonAir);
            writeContainer(sec, tmp, 4, 8, 15);
            sec.u8(0).varint(Tables.PLAINS).varint(0); // биомы: одно значение
        }
        p.varint(sec.len).raw(sec.b, 0, sec.len);
        // блок-сущности (сундуки, кровати, таблички, головы — без них Java их не рисует)
        JBuf be = new JBuf(); int beCount = 0;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 128; y++) {
            int i = idx(x, y, z);
            int t = beType(c.pe[i] & 0xff);
            if (t < 0 || c.state[i] == 0) continue;
            be.u8((x << 4) | z).i16(y).varint(t).raw(beNbt(c, i));
            beCount++;
        }
        p.varint(beCount).raw(be.b, 0, be.len);
        // свет: секции -1..8 → 10 бит; ниже и выше мира — пусто
        int total = SECTIONS + 2;
        long skyMask = 0, blkMask = 0, emptySky = 0, emptyBlk = 0;
        for (int i = 0; i < total; i++) {
            if (i == 0 || i == total - 1) { emptySky |= 1L << i; emptyBlk |= 1L << i; }
            else { skyMask |= 1L << i; blkMask |= 1L << i; }
        }
        // над миром небо полностью светлое
        skyMask |= 1L << (total - 1); emptySky &= ~(1L << (total - 1));
        p.varint(1).i64(skyMask);
        p.varint(1).i64(blkMask);
        p.varint(1).i64(emptySky);
        p.varint(1).i64(emptyBlk);
        p.varint(SECTIONS + 1);
        for (int s = 0; s < SECTIONS; s++) { p.varint(2048); p.raw(lightSection(c.sky, s)); }
        byte[] full = new byte[2048]; java.util.Arrays.fill(full, (byte) 0xff);
        p.varint(2048); p.raw(full);
        p.varint(SECTIONS);
        for (int s = 0; s < SECTIONS; s++) { p.varint(2048); p.raw(lightSection(c.blk, s)); }
        return p.bytes();
    }

    private static byte[] lightSection(byte[] src, int s) {
        byte[] out = new byte[2048];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int v = nib(src, idx(x, s * 16 + y, z));
            int j = (y << 8) | (z << 4) | x;
            out[j >> 1] |= (byte) ((j & 1) == 0 ? v : v << 4);
        }
        return out;
    }

    private static long[] heightmap(Chunk c) {
        int bits = 8; // ceil(log2(128+1))
        int per = 64 / bits;
        long[] out = new long[(256 + per - 1) / per];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int h = 0;
            for (int y = 127; y >= 0; y--) if (c.state[idx(x, y, z)] != 0) { h = y + 1; break; }
            int i = z * 16 + x;
            out[i / per] |= ((long) h) << ((i % per) * bits);
        }
        return out;
    }

    /** Палитровый контейнер (блоки: минимум 4 бита, косвенная палитра до 8, иначе прямые id). */
    static void writeContainer(JBuf o, int[] vals, int minBits, int maxIndirect, int directBits) {
        Map<Integer, Integer> pal = new HashMap<>();
        int[] order = new int[vals.length]; int n = 0;
        for (int v : vals) if (!pal.containsKey(v)) { pal.put(v, n); order[n++] = v; }
        if (n == 1) { o.u8(0).varint(order[0]).varint(0); return; }
        int bits = Math.max(minBits, 32 - Integer.numberOfLeadingZeros(n - 1));
        boolean direct = bits > maxIndirect;
        if (direct) bits = directBits;
        o.u8(bits);
        if (!direct) { o.varint(n); for (int i = 0; i < n; i++) o.varint(order[i]); }
        int per = 64 / bits;
        int longs = (vals.length + per - 1) / per;
        o.varint(longs);
        long mask = (1L << bits) - 1;
        for (int li = 0; li < longs; li++) {
            long l = 0;
            for (int k = 0; k < per; k++) {
                int i = li * per + k;
                if (i >= vals.length) break;
                long v = direct ? vals[i] : pal.get(vals[i]);
                l |= (v & mask) << (k * bits);
            }
            o.i64(l);
        }
    }
}
