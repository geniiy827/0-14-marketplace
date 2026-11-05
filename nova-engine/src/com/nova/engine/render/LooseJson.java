package com.nova.engine.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON as 0.14 reads it (jsoncpp in non-strict mode): // and /* comments, missing or trailing commas are tolerated
 * (skins/Base/Mobs.json has a cube without the comma before "inflate"). Objects are LinkedHashMap (key order kept),
 * arrays ArrayList, numbers Double.
 */
public final class LooseJson {
    private final String s; private int p;
    private LooseJson(String s) { this.s = s; }

    public static Object parse(String s) { LooseJson j = new LooseJson(s); return j.value(); }

    private void ws() {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') { p++; continue; }
            if (c == '/' && p + 1 < s.length() && s.charAt(p + 1) == '/') { while (p < s.length() && s.charAt(p) != '\n') p++; continue; }
            if (c == '/' && p + 1 < s.length() && s.charAt(p + 1) == '*') { int e = s.indexOf("*/", p + 2); p = e < 0 ? s.length() : e + 2; continue; }
            break;
        }
    }

    private Object value() {
        ws();
        if (p >= s.length()) return null;
        char c = s.charAt(p);
        if (c == '{') {
            p++; Map<String, Object> m = new LinkedHashMap<String, Object>();
            while (true) {
                ws();
                if (p >= s.length()) return m;
                if (s.charAt(p) == '}') { p++; return m; }
                String k = str();
                ws();
                if (p < s.length() && s.charAt(p) == ':') p++;
                m.put(k, value());
            }
        }
        if (c == '[') {
            p++; List<Object> l = new ArrayList<Object>();
            while (true) {
                ws();
                if (p >= s.length()) return l;
                if (s.charAt(p) == ']') { p++; return l; }
                l.add(value());
            }
        }
        if (c == '"') return str();
        if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p)) { p += 4; return null; }
        int b = p;
        while (p < s.length() && "+-.eE0123456789".indexOf(s.charAt(p)) >= 0) p++;
        if (b == p) { p++; return null; }
        return Double.valueOf(s.substring(b, p));
    }

    private String str() {
        if (p >= s.length() || s.charAt(p) != '"') { int b = p; while (p < s.length() && ":{}[], \n\t\r".indexOf(s.charAt(p)) < 0) p++; return s.substring(b, p); }
        p++;
        StringBuilder b = new StringBuilder();
        while (p < s.length()) {
            char c = s.charAt(p++);
            if (c == '"') break;
            if (c == '\\' && p < s.length()) {
                char e = s.charAt(p++);
                if (e == 'n') b.append('\n'); else if (e == 't') b.append('\t');
                else if (e == 'u' && p + 4 <= s.length()) { b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; }
                else b.append(e);
            } else b.append(c);
        }
        return b.toString();
    }

    // ---- helpers
    @SuppressWarnings("unchecked") public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked") public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }
    public static float num(Object o, float d) { return o instanceof Number ? ((Number) o).floatValue() : d; }
    public static float[] vec(Object o, int n) {
        float[] v = new float[n]; List<Object> l = arr(o);
        if (l != null) for (int i = 0; i < n && i < l.size(); i++) v[i] = num(l.get(i), 0);
        return v;
    }
}
