package com.geniiy.bridge14.proxy;

import com.geniiy.bridge14.pe.Buf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Мини-NBT: чтение little-endian (0.14) и запись big-endian (Java). */
public final class Nbt {
    private Nbt() {}

    /** Прочитать один именованный тег (обычно compound). Возвращает Map для compound. */
    public static Object readLE(Buf b) {
        int t = b.u8();
        if (t == 0) return null;
        int n = b.lu16(); b.skip(n);
        return payloadLE(b, t);
    }

    /** Все подряд идущие корневые теги (так сервер 0.14 шлёт блок-сущности в чанке). */
    public static List<Map<String, Object>> readAllLE(Buf b) {
        List<Map<String, Object>> out = new ArrayList<>();
        while (b.left() > 3) {
            try {
                Object o = readLE(b);
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                    out.add(m);
                } else if (o == null) break;
            } catch (Exception e) { break; }
        }
        return out;
    }

    private static String strLE(Buf b) { int n = b.lu16(); return new String(b.take(n), StandardCharsets.UTF_8); }

    private static Object payloadLE(Buf b, int t) {
        switch (t) {
            case 1: return (byte) b.u8();
            case 2: return (short) b.lu16();
            case 3: return b.li32();
            case 4: return b.li64();
            case 5: return b.lf32();
            case 6: return Double.longBitsToDouble(b.li64());
            case 7: { int n = b.li32(); return b.take(n); }
            case 8: return strLE(b);
            case 9: {
                int et = b.u8(); int n = b.li32();
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(payloadLE(b, et));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int ct = b.u8();
                    if (ct == 0) break;
                    String name = strLE(b);
                    m.put(name, payloadLE(b, ct));
                }
                return m;
            }
            case 11: { int n = b.li32(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = b.li32(); return a; }
            default: throw new RuntimeException("NBT тип " + t);
        }
    }

    // ---------------- запись для 0.14 (LE) ----------------
    public static byte[] writeLE(Map<String, Object> root) {
        Buf b = new Buf();
        b.u8(10); wstrLE(b, "");
        compoundLE(b, root);
        return b.bytes();
    }

    private static void wstrLE(Buf b, String s) { byte[] d = s.getBytes(StandardCharsets.UTF_8); b.li16(d.length); b.raw(d); }

    private static void compoundLE(Buf b, Map<String, Object> m) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) { b.u8(8); wstrLE(b, e.getKey()); wstrLE(b, (String) v); }
            else if (v instanceof Integer) { b.u8(3); wstrLE(b, e.getKey()); b.li32((Integer) v); }
            else if (v instanceof Byte) { b.u8(1); wstrLE(b, e.getKey()); b.u8((Byte) v); }
        }
        b.u8(0);
    }

    // ---------------- запись для Java (BE, сетевой NBT 1.20.1: корень с пустым именем) ----------------
    public static final class W {
        final JBuf o = new JBuf();
        public W() { o.u8(10).i16(0); }
        private void name(int type, String n) { byte[] d = n.getBytes(StandardCharsets.UTF_8); o.u8(type).i16(d.length).raw(d); }
        private void sv(String s) { byte[] d = s.getBytes(StandardCharsets.UTF_8); o.i16(d.length).raw(d); }
        public W str(String n, String v) { name(8, n); sv(v); return this; }
        public W i8(String n, int v) { name(1, n); o.u8(v); return this; }
        public W i32(String n, int v) { name(3, n); o.i32(v); return this; }
        public W begin(String n) { name(10, n); return this; }
        public W end() { o.u8(0); return this; }
        public W strList(String n, String... vals) { name(9, n); o.u8(8).i32(vals.length); for (String s : vals) sv(s); return this; }
        public byte[] done() { o.u8(0); return o.bytes(); }
    }

    /** NBT таблички 1.20 из четырёх строк 0.14. */
    public static byte[] signJava(String[] lines) {
        String[] msgs = new String[4];
        for (int i = 0; i < 4; i++) msgs[i] = Session.chat(lines != null && i < lines.length && lines[i] != null ? lines[i] : "");
        String[] empty = {Session.chat(""), Session.chat(""), Session.chat(""), Session.chat("")};
        return new W()
                .begin("front_text").strList("messages", msgs).str("color", "black").i8("has_glowing_text", 0).end()
                .begin("back_text").strList("messages", empty).str("color", "black").i8("has_glowing_text", 0).end()
                .i8("is_waxed", 0)
                .done();
    }

    public static String[] signLines(Map<String, Object> m) {
        String[] r = new String[4];
        for (int i = 0; i < 4; i++) { Object o = m.get("Text" + (i + 1)); r[i] = o instanceof String ? (String) o : ""; }
        // в некоторых версиях весь текст одной строкой "Text"
        Object t = m.get("Text");
        if (t instanceof String && r[0].isEmpty() && r[1].isEmpty()) {
            String[] p = ((String) t).split("\n", -1);
            for (int i = 0; i < 4; i++) r[i] = i < p.length ? p[i] : "";
        }
        return r;
    }

    static int intOf(Object o, int def) { return o instanceof Number ? ((Number) o).intValue() : def; }
}
