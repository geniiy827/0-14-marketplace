package com.nova.client.mcmod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON for data.save/load: numbers, strings, bools, null, lists, maps. */
final class Json {
    private Json() { }

    /** deep copy of a storable value; functions are refused */
    static Object copy(Object v, int line) {
        if (v == null || v instanceof Double || v instanceof String || v instanceof Boolean) return v;
        if (v instanceof List) {
            List<Object> l = new ArrayList<Object>();
            for (Object x : (List<?>) v) l.add(copy(x, line));
            return l;
        }
        if (v instanceof Map && !(v instanceof Interp.Ns)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getValue() instanceof Interp.Callable) continue;
                m.put(String.valueOf(e.getKey()), copy(e.getValue(), line));
            }
            return m;
        }
        throw new ScriptError("это нельзя сохранить: " + Interp.typeName(v), line);
    }

    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof Double) b.append(Interp.str(v));
        else if (v instanceof Boolean) b.append(v);
        else if (v instanceof String) {
            b.append('"');
            for (char c : ((String) v).toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c == '\n') b.append("\\n");
                else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            b.append('"');
        } else if (v instanceof List) {
            b.append('[');
            boolean f = true;
            for (Object x : (List<?>) v) { if (!f) b.append(','); f = false; write(b, x); }
            b.append(']');
        } else if (v instanceof Map) {
            b.append('{');
            boolean f = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!f) b.append(',');
                f = false;
                write(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else write(b, String.valueOf(v));
    }

    static Object read(String s) {
        int[] p = {0};
        Object v = value(s, p);
        return v;
    }

    private static void ws(String s, int[] p) { while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++; }

    private static Object value(String s, int[] p) {
        ws(s, p);
        char c = s.charAt(p[0]);
        if (c == '{') {
            p[0]++;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            ws(s, p);
            if (s.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                ws(s, p);
                String k = (String) value(s, p);
                ws(s, p);
                p[0]++;                                  // ':'
                m.put(k, value(s, p));
                ws(s, p);
                if (s.charAt(p[0]++) == '}') return m;
            }
        }
        if (c == '[') {
            p[0]++;
            List<Object> l = new ArrayList<Object>();
            ws(s, p);
            if (s.charAt(p[0]) == ']') { p[0]++; return l; }
            while (true) {
                l.add(value(s, p));
                ws(s, p);
                if (s.charAt(p[0]++) == ']') return l;
            }
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            p[0]++;
            while (true) {
                char d = s.charAt(p[0]++);
                if (d == '"') return b.toString();
                if (d == '\\') {
                    char e = s.charAt(p[0]++);
                    if (e == 'n') b.append('\n');
                    else if (e == 't') b.append('\t');
                    else if (e == 'u') { b.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; }
                    else b.append(e);
                } else b.append(d);
            }
        }
        if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
        int a = p[0];
        while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        return Double.parseDouble(s.substring(a, p[0]));
    }
}
