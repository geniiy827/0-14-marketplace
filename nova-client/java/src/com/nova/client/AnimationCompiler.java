package com.nova.client;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles Bedrock animations (*.animation.json, format 1.8.0) and the
 * "scripts.animate" lists of *.entity.json into the compact blob libnova
 * plays back: Molang becomes stack bytecode, keyframes are kept as is.
 *
 * Supported Molang: numbers, + - * / , comparisons, && || !, ?:, ( ),
 * math.sin/cos(degrees)/abs/clamp/min/max/lerp/sqrt/floor/ceil/round/mod/pow/trunc/sign,
 * math.pi, query.anim_time, query.life_time, query.modified_distance_moved,
 * query.modified_move_speed, query.target_x_rotation, query.target_y_rotation,
 * query.is_on_ground, query.is_moving.  Unknown names evaluate to 0.
 */
final class AnimationCompiler {
    static final String TAG = "NOVA.anim";

    static final int OP_PUSH = 1, OP_VAR = 2, OP_ADD = 3, OP_SUB = 4, OP_MUL = 5, OP_DIV = 6, OP_NEG = 7,
            OP_LT = 8, OP_GT = 9, OP_LE = 10, OP_GE = 11, OP_EQ = 12, OP_NE = 13, OP_AND = 14, OP_OR = 15,
            OP_NOT = 16, OP_SEL = 17, OP_CALL = 18;

    static final Map<String, Integer> VARS = new HashMap<String, Integer>();
    static final Map<String, Integer> FNS = new HashMap<String, Integer>();
    static {
        VARS.put("anim_time", 0);
        VARS.put("life_time", 1);
        VARS.put("modified_distance_moved", 2);
        VARS.put("distance_moved", 2);
        VARS.put("walk_distance", 2);
        VARS.put("modified_move_speed", 3);
        VARS.put("target_y_rotation", 4);
        VARS.put("head_y_rotation", 4);
        VARS.put("target_x_rotation", 5);
        VARS.put("head_x_rotation", 5);
        VARS.put("is_on_ground", 6);
        VARS.put("nova_arg", 7);
        String[] f = {"sin", "cos", "abs", "clamp", "min", "max", "lerp", "sqrt", "floor", "ceil", "round",
                "mod", "pow", "trunc", "lerprotate", "sign"};
        for (int i = 0; i < f.length; i++) FNS.put(f[i], i);
    }

    // ------------------------------------------------------------------ Molang
    static final class Molang {
        final String s;
        int i;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Molang(String s) { this.s = s.toLowerCase(); }

        static byte[] compile(Object v) {
            if (v == null) return new byte[0];
            if (v instanceof Number) {
                Molang m = new Molang("0");
                m.push(((Number) v).floatValue());
                return m.out.toByteArray();
            }
            String src = String.valueOf(v).trim();
            if (src.length() == 0) return new byte[0];
            // multi-statement Molang: keep the last expression ("return x" -> x)
            if (src.contains(";")) {
                String[] parts = src.split(";");
                String last = "";
                for (String p : parts) if (p.trim().length() > 0) last = p.trim();
                if (last.startsWith("return ")) last = last.substring(7);
                src = last;
            }
            try {
                Molang m = new Molang(src);
                m.ternary();
                m.ws();
                if (m.i < m.s.length()) throw new IllegalArgumentException("unexpected '" + m.s.charAt(m.i) + "'");
                return m.out.toByteArray();
            } catch (RuntimeException e) {
                Log.w(TAG, "molang \"" + src + "\": " + e.getMessage());
                return new byte[0];
            }
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        boolean eat(String t) {
            ws();
            if (s.startsWith(t, i)) { i += t.length(); return true; }
            return false;
        }

        void op(int o) { out.write(o); }

        void push(float f) {
            op(OP_PUSH);
            int b = Float.floatToIntBits(f);
            out.write(b); out.write(b >> 8); out.write(b >> 16); out.write(b >> 24);
        }

        void ternary() {
            or();
            if (eat("??")) { or(); op(OP_ADD); return; }   // rough: null-coalescing
            if (eat("?")) {
                ternary();
                if (eat(":")) ternary(); else push(0);
                op(OP_SEL);
            }
        }

        void or() { and(); while (eat("||")) { and(); op(OP_OR); } }

        void and() { cmp(); while (eat("&&")) { cmp(); op(OP_AND); } }

        void cmp() {
            add();
            while (true) {
                if (eat("==")) { add(); op(OP_EQ); }
                else if (eat("!=")) { add(); op(OP_NE); }
                else if (eat("<=")) { add(); op(OP_LE); }
                else if (eat(">=")) { add(); op(OP_GE); }
                else if (eat("<")) { add(); op(OP_LT); }
                else if (eat(">")) { add(); op(OP_GT); }
                else return;
            }
        }

        void add() {
            mul();
            while (true) {
                if (eat("+")) { mul(); op(OP_ADD); }
                else if (eat("-")) { mul(); op(OP_SUB); }
                else return;
            }
        }

        void mul() {
            unary();
            while (true) {
                if (eat("*")) { unary(); op(OP_MUL); }
                else if (eat("/")) { unary(); op(OP_DIV); }
                else return;
            }
        }

        void unary() {
            if (eat("-")) { unary(); op(OP_NEG); return; }
            if (eat("+")) { unary(); return; }
            if (eat("!")) { unary(); op(OP_NOT); return; }
            atom();
        }

        void atom() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            if (c == '(') { i++; ternary(); if (!eat(")")) throw new IllegalArgumentException("missing )"); return; }
            if (Character.isDigit(c) || c == '.') {
                int st = i;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                push(Float.parseFloat(s.substring(st, i)));
                if (i < s.length() && s.charAt(i) == 'f') i++;
                return;
            }
            if (Character.isLetter(c) || c == '_') {
                int st = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) i++;
                String id = s.substring(st, i);
                int dot = id.indexOf('.');
                String ns = dot < 0 ? "" : id.substring(0, dot), name = dot < 0 ? id : id.substring(dot + 1);
                if (eat("(")) {
                    int argc = 0;
                    if (!eat(")")) {
                        do { ternary(); argc++; } while (eat(","));
                        if (!eat(")")) throw new IllegalArgumentException("missing ) after args");
                    }
                    Integer fn = ("math".equals(ns)) ? FNS.get(name) : null;
                    if (fn == null) {           // unknown call: collapse args, yield 0
                        for (int k = 1; k < argc; k++) op(OP_ADD);
                        if (argc > 0) { push(0); op(OP_MUL); } else push(0);
                        return;
                    }
                    op(OP_CALL); out.write(fn); out.write(argc);
                    return;
                }
                if ("math".equals(ns) && "pi".equals(name)) { push((float) Math.PI); return; }
                if ("true".equals(id)) { push(1); return; }
                if ("false".equals(id)) { push(0); return; }
                if ("query".equals(ns) || "q".equals(ns)) {
                    if ("is_moving".equals(name)) {          // speed > 0.01
                        op(OP_VAR); out.write(3); push(0.01f); op(OP_GT);
                        return;
                    }
                    Integer v = VARS.get(name);
                    if (v != null) { op(OP_VAR); out.write(v); return; }
                }
                push(0);   // variable.* / unknown query: 0
                return;
            }
            throw new IllegalArgumentException("unexpected '" + c + "'");
        }
    }

    // ------------------------------------------------------------- blob build
    private final ByteArrayOutputStream blob = new ByteArrayOutputStream();
    private int sets;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    private static void u8(ByteArrayOutputStream o, int v) { o.write(v); }
    private static void u16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }
    private static void f32(ByteArrayOutputStream o, float f) {
        int b = Float.floatToIntBits(f);
        o.write(b); o.write(b >> 8); o.write(b >> 16); o.write(b >> 24);
    }
    private static void str(ByteArrayOutputStream o, String s) {
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
        int n = Math.min(255, b.length);
        o.write(n);
        o.write(b, 0, n);
    }
    private static void prog(ByteArrayOutputStream o, Object expr) {
        byte[] p = Molang.compile(expr);
        u16(o, p.length);
        o.write(p, 0, p.length);
    }

    /** One entity: geometry id + [animation json, weight expression] in play order. */
    void addSet(String geometry, List<JSONObject> anims, List<Object> weights) throws Exception {
        ByteArrayOutputStream o = body;
        str(o, geometry);
        u16(o, anims.size());
        for (int a = 0; a < anims.size(); a++) {
            JSONObject an = anims.get(a);
            u8(o, an.optBoolean("loop", false) || "hold_on_last_frame".equals(an.optString("loop")) ? 1 : 0);
            f32(o, (float) an.optDouble("animation_length", longestKey(an)));
            prog(o, an.opt("anim_time_update"));
            prog(o, weights.get(a));
        }
        // bone -> channels
        Map<String, List<byte[]>> bones = new LinkedHashMap<String, List<byte[]>>();
        for (int a = 0; a < anims.size(); a++) {
            JSONObject bs = anims.get(a).optJSONObject("bones");
            if (bs == null) continue;
            Iterator<String> it = bs.keys();
            while (it.hasNext()) {
                String bone = it.next();
                JSONObject b = bs.getJSONObject(bone);
                List<byte[]> list = bones.get(bone);
                if (list == null) { list = new ArrayList<byte[]>(); bones.put(bone, list); }
                if (b.has("rotation")) list.add(channel(a, 0, b.get("rotation")));
                if (b.has("position")) list.add(channel(a, 1, b.get("position")));
            }
        }
        u16(o, bones.size());
        for (Map.Entry<String, List<byte[]>> e : bones.entrySet()) {
            str(o, e.getKey());
            u16(o, e.getValue().size());
            for (byte[] ch : e.getValue()) o.write(ch, 0, ch.length);
        }
        sets++;
    }

    private static float longestKey(JSONObject an) {
        float max = 0;
        JSONObject bs = an.optJSONObject("bones");
        if (bs == null) return 0;
        Iterator<String> it = bs.keys();
        while (it.hasNext()) {
            JSONObject b = bs.optJSONObject(it.next());
            if (b == null) continue;
            for (String ch : new String[]{"rotation", "position"}) {
                JSONObject k = b.optJSONObject(ch);
                if (k == null) continue;
                Iterator<String> ki = k.keys();
                while (ki.hasNext()) {
                    try { max = Math.max(max, Float.parseFloat(ki.next())); } catch (NumberFormatException ignored) { }
                }
            }
        }
        return max;
    }

    private static Object[] vec3(Object v) {
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            return new Object[]{a.opt(0), a.opt(1), a.opt(2)};
        }
        if (v instanceof JSONObject) {   // {"pre":..., "post":...} or {"vector":[...]}
            JSONObject o = (JSONObject) v;
            if (o.has("post")) return vec3(o.opt("post"));
            if (o.has("vector")) return vec3(o.opt("vector"));
            if (o.has("pre")) return vec3(o.opt("pre"));
        }
        return new Object[]{v, v, v};
    }

    private static byte[] channel(int anim, int target, Object v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u8(o, anim);
        u8(o, target);
        JSONObject keys = v instanceof JSONObject && !((JSONObject) v).has("post") && !((JSONObject) v).has("pre")
                && !((JSONObject) v).has("vector") ? (JSONObject) v : null;
        if (keys == null) {
            u8(o, 0);
            for (Object x : vec3(v)) prog(o, x);
        } else {
            List<Float> times = new ArrayList<Float>();
            Map<Float, Object> byTime = new HashMap<Float, Object>();
            Iterator<String> it = keys.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    float t = Float.parseFloat(k);
                    times.add(t);
                    byTime.put(t, keys.opt(k));
                } catch (NumberFormatException ignored) { }
            }
            java.util.Collections.sort(times);
            int n = Math.min(255, times.size());
            u8(o, n);
            for (int i = 0; i < n; i++) {
                f32(o, times.get(i));
                for (Object x : vec3(byTime.get(times.get(i)))) prog(o, x);
            }
        }
        return o.toByteArray();
    }

    byte[] build() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('N'); o.write('V'); o.write('A'); o.write('1');
        u16(o, sets);
        byte[] b = body.toByteArray();
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    int sets() { return sets; }
}
