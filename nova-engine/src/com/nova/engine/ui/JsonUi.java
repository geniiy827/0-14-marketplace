package com.nova.engine.ui;

import android.content.res.AssetManager;
import android.graphics.BitmapFactory;

import com.nova.engine.render.Hud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The data driven UI of 0.14.3 (ui/_ui_defs.json + ui/*.json, read from the game's assets), the subset the screens
 * we have use: namespaces, "name@base" inheritance, "$variables" (element defaults, "variables" with "requires",
 * values handed down to children), controls, anchors (anchor_from / anchor_to, default center), size / offset
 * ("N", "N%", "N%x" of the parent width, "N%y", "Npx", sums, "fill", "default" = parent), layers (parent + own),
 * visible / ignored, bindings with binding_condition "visible", and the control types panel, screen, image (uv,
 * uv_size, nineslice_size, alpha, color), label (localized text, color, alpha, shadow, alignment inside its box),
 * button (default / hover / pressed state children, "$pressed_button_name") and custom renderers (splash text).
 */
public final class JsonUi {
    // ------------------------------------------------------------------ definitions
    private final Map<String, JSONObject> defs = new HashMap<String, JSONObject>();
    private final Map<String, String> bases = new HashMap<String, String>();
    private final Map<String, String> loc = new HashMap<String, String>();
    private final AssetManager am;

    public JsonUi(AssetManager am, String lang) {
        this.am = am;
        try {
            JSONObject index = parse(read("ui/_ui_defs.json"));
            JSONArray files = index.getJSONArray("ui_defs");
            for (int i = 0; i < files.length(); i++) {
                try { addFile(parse(read(files.getString(i)))); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        loadLang("loc/en_US-pocket.lang");
        if (lang != null && !lang.equals("en_US")) loadLang("loc/" + lang + "-pocket.lang");
    }

    private void loadLang(String path) {
        try {
            for (String line : read(path).split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) continue;
                String v = line.substring(eq + 1);
                int tab = v.indexOf('\t');                                  // "value\t#comment"
                if (tab >= 0) v = v.substring(0, tab);
                loc.put(line.substring(0, eq).trim(), v.trim());
            }
        } catch (Exception ignored) { }
    }

    public String tr(String key) { String v = loc.get(key); return v == null ? key : v; }

    private void addFile(JSONObject f) throws Exception {
        String ns = f.optString("namespace", "");
        Iterator<String> it = f.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equals("namespace")) continue;
            Object v = f.get(k);
            if (!(v instanceof JSONObject)) continue;
            int at = k.indexOf('@');
            String name = at < 0 ? k : k.substring(0, at);
            defs.put(ns + "." + name, (JSONObject) v);
            if (at >= 0) bases.put(ns + "." + name, full(k.substring(at + 1), ns));
        }
    }

    static String full(String ref, String ns) { return ref.indexOf('.') >= 0 ? ref : ns + "." + ref; }

    /** the element with all its bases merged (base first, own properties override) */
    JSONObject flat(String fullName) {
        JSONObject own = defs.get(fullName);
        if (own == null) return new JSONObject();
        String b = bases.get(fullName);
        JSONObject out = b == null ? new JSONObject() : copy(flat(b));
        merge(out, own);
        return out;
    }

    static JSONObject copy(JSONObject o) {
        JSONObject c = new JSONObject();
        merge(c, o);
        return c;
    }

    static void merge(JSONObject into, JSONObject from) {
        Iterator<String> it = from.keys();
        while (it.hasNext()) {
            String k = it.next();
            try { into.put(k, from.get(k)); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ JSON reading (with // comments)
    private String read(String path) throws Exception {
        InputStream in = am.open(path);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        String s = bo.toString("UTF-8");
        if (s.length() > 0 && s.charAt(0) == '﻿') s = s.substring(1);
        return s;
    }

    static JSONObject parse(String s) throws Exception {
        StringBuilder b = new StringBuilder(s.length());
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                b.append(c);
                if (c == '\\' && i + 1 < s.length()) { b.append(s.charAt(++i)); continue; }
                if (c == '"') str = false;
                continue;
            }
            if (c == '"') { str = true; b.append(c); continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; b.append('\n'); continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') { i = s.indexOf("*/", i + 2); if (i < 0) break; i++; continue; }
            b.append(c);
        }
        return new JSONObject(b.toString());
    }

    // ------------------------------------------------------------------ controls
    public static final class Node {
        String name, type = "panel";
        JSONObject p;
        Map<String, Object> env;
        String ns;
        final List<Node> kids = new ArrayList<Node>();
        Node parent;
        float x, y, w, h;
        int layer;
        boolean visible = true;
        String pressedName;
        boolean down;
        /** binding values of the tree it was built for (#header, #game_online, ...) */
        Map<String, Object> data;
        public float x() { return x; }
        public float y() { return y; }
        public float w() { return w; }
        public float h() { return h; }
        public String name() { return name; }
        public Node find(String nm) {
            if (nm.equals(name)) return this;
            for (Node c : kids) { Node r = c.find(nm); if (r != null) return r; }
            return null;
        }
        public void hide() { visible = false; }
        public void show() { visible = true; }
        public boolean isVisible() { return visible; }
        public List<Node> children() { return kids; }
        int order;
    }

    /** the global variables of a pocket edition, full game, not holographic, without a gamepad */
    static Map<String, Object> globals() {
        Map<String, Object> g = new HashMap<String, Object>();
        g.put("$pocket_edition", true); g.put("$not_trial", true); g.put("$trial", false);
        g.put("$win10_edition", false); g.put("$osx_edition", false); g.put("$education_edition", false);
        g.put("$is_holographic", false); g.put("$not_holographic", true); g.put("$gamepad", false);
        return g;
    }

    public Node build(String screen) { return build(screen, new HashMap<String, Object>()); }

    private Map<String, Object> building;

    /** build with binding values (strings for labels, booleans for #visible bindings) */
    public Node build(String screen, Map<String, Object> data) {
        int dot = screen.indexOf('.');
        building = data;
        Node n = make(null, screen.substring(dot + 1), flat(screen), globals(), screen.substring(0, dot));
        building = null;
        return n;
    }

    private Object val(Object v, Map<String, Object> env) {
        if (v instanceof String && ((String) v).startsWith("$") && env.containsKey(v)) return env.get(v);
        return v;
    }

    private boolean truth(Object v, Map<String, Object> env) {
        v = val(v, env);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.startsWith("(not ") && s.endsWith(")")) return !truth(s.substring(5, s.length() - 1).trim(), env);
            if (s.startsWith("$")) return false;
            return !s.isEmpty() && !s.equals("false");
        }
        return v != null && v != JSONObject.NULL;
    }

    private Node make(Node parent, String name, JSONObject def, Map<String, Object> penv, String ns) {
        Node n = new Node();
        n.name = name; n.parent = parent; n.ns = ns;
        Map<String, Object> env = new HashMap<String, Object>(penv);
        Iterator<String> it = def.keys();
        while (it.hasNext()) { String k = it.next(); if (k.startsWith("$")) env.put(k, val(def.opt(k), penv)); }
        JSONArray vars = def.optJSONArray("variables");
        if (vars != null) for (int i = 0; i < vars.length(); i++) {
            JSONObject v = vars.optJSONObject(i);
            if (v == null || !truth(v.opt("requires"), env)) continue;
            Iterator<String> vk = v.keys();
            while (vk.hasNext()) { String k = vk.next(); if (k.startsWith("$")) env.put(k, v.opt(k)); }
        }
        n.env = env; n.p = def; n.data = building;
        n.type = def.optString("type", "panel");
        n.layer = (parent == null ? 0 : parent.layer) + def.optInt("layer", 0);
        if (def.has("visible")) n.visible = truth(def.opt("visible"), env);
        JSONArray binds = def.optJSONArray("bindings");
        if (binds != null) for (int i = 0; i < binds.length(); i++) {
            JSONObject b = binds.optJSONObject(i);
            if (b == null) continue;
            Object bn = val(b.opt("binding_name"), env);
            if ("#visible".equals(b.optString("binding_name_override"))) {
                n.visible &= bn instanceof String && binding((String) bn, building);
            } else if ("visible".equals(b.optString("binding_condition")) && bn instanceof String) {
                // binding_condition "visible" = refresh while visible; only a boolean value hides it
                if (building != null && building.get(bn) instanceof Boolean) n.visible &= (Boolean) building.get(bn);
                else if (!((String) bn).startsWith("#") || (building == null || !building.containsKey(bn))) n.visible &= binding((String) bn, building) || building != null && building.containsKey(bn);
            }
        }
        Object pb = val(def.opt("$pressed_button_name"), env);
        if (n.type.equals("button")) n.pressedName = pb instanceof String ? (String) pb : null;
        JSONArray controls = def.optJSONArray("controls");
        if (controls != null) for (int i = 0; i < controls.length(); i++) {
            JSONObject entry = controls.optJSONObject(i);
            if (entry == null) continue;
            Iterator<String> ck = entry.keys();
            if (!ck.hasNext()) continue;
            String key = ck.next();
            JSONObject over = entry.optJSONObject(key);
            if (key.startsWith("$") && env.get(key) instanceof String) key = (String) env.get(key);   // e.g. $icon_image_ref
            if (over == null) over = new JSONObject();
            int at = key.indexOf('@');
            String cname, base = null;
            if (at < 0) cname = key;
            else {
                base = key.substring(at + 1);
                if (base.startsWith("$")) { Object r = env.get(base); base = r instanceof String ? (String) r : null; }
                cname = at == 0 ? (base == null ? "" : base.substring(base.lastIndexOf('.') + 1)) : key.substring(0, at);
            }
            if (over.has("ignored") && truth(over.opt("ignored"), env)) continue;
            if (key.contains("scrolling_panel") || key.contains("gamepad_helper")) continue;     // drawn by the screen
            JSONObject cdef = base == null ? new JSONObject() : flat(full(base, ns));
            if (cdef.has("ignored") && truth(cdef.opt("ignored"), env)) continue;
            merge(cdef, over);
            String cns = base == null ? ns : full(base, ns).substring(0, full(base, ns).indexOf('.'));
            Node c = make(n, cname, cdef, env, cns);
            c.order = i;
            n.kids.add(c);
        }
        return n;
    }

    /** data bindings we can answer */
    private static boolean binding(String b, Map<String, Object> data) {
        if (data != null && data.get(b) instanceof Boolean) return (Boolean) data.get(b);
        return "#version".equals(b);
    }

    // ------------------------------------------------------------------ layout
    static float dim(Object v, float parentW, float parentH, float axis) {
        if (v instanceof Number) return ((Number) v).floatValue();
        if (!(v instanceof String)) return axis;
        String s = ((String) v).replace(" ", "");
        if (s.equals("fill") || s.equals("default") || s.isEmpty()) return axis;
        float sum = 0;
        int i = 0;
        while (i < s.length()) {
            int sign = 1;
            if (s.charAt(i) == '+') i++;
            else if (s.charAt(i) == '-') { sign = -1; i++; }
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            float num = st == i ? 0 : Float.parseFloat(s.substring(st, i));
            if (s.startsWith("%x", i)) { sum += sign * num / 100f * parentW; i += 2; }
            else if (s.startsWith("%y", i)) { sum += sign * num / 100f * parentH; i += 2; }
            else if (s.startsWith("%c", i)) { sum += sign * num / 100f * axis; i += 2; }
            else if (s.startsWith("%", i)) { sum += sign * num / 100f * axis; i += 1; }
            else if (s.startsWith("px", i)) { sum += sign * num; i += 2; }
            else sum += sign * num;
        }
        return sum;
    }

    static float[] anchor(String a, float w, float h) {
        if (a == null) a = "center";
        float ax = a.endsWith("left") ? 0 : a.endsWith("right") ? 1 : 0.5f;
        float ay = a.startsWith("top") ? 0 : a.startsWith("bottom") ? 1 : 0.5f;
        if (a.equals("left_middle")) { ax = 0; ay = 0.5f; }
        if (a.equals("right_middle")) { ax = 1; ay = 0.5f; }
        return new float[]{ax * w, ay * h};
    }

    public void layout(Node n, float px, float py, float pw, float ph) {
        Object size = val(n.p.opt("size"), n.env);
        float w = pw, h = ph;
        if (size instanceof JSONArray) {
            JSONArray a = (JSONArray) size;
            w = dim(val(a.opt(0), n.env), pw, ph, pw);
            h = dim(val(a.opt(1), n.env), pw, ph, ph);
        }
        Object off = val(n.p.opt("offset"), n.env);
        float ox = 0, oy = 0;
        if (off instanceof JSONArray) {
            JSONArray a = (JSONArray) off;
            ox = dim(val(a.opt(0), n.env), pw, ph, pw);
            oy = dim(val(a.opt(1), n.env), pw, ph, ph);
        }
        Object af = val(n.p.opt("anchor_from"), n.env), at = val(n.p.opt("anchor_to"), n.env);
        float[] from = anchor(af instanceof String ? (String) af : null, pw, ph);
        float[] to = anchor(at instanceof String ? (String) at : null, w, h);
        n.x = px + from[0] - to[0] + ox; n.y = py + from[1] - to[1] + oy; n.w = w; n.h = h;
        for (Node c : n.kids) layout(c, n.x, n.y, n.w, n.h);
    }

    // ------------------------------------------------------------------ drawing
    public interface Custom { void render(Node n, Hud hud, float scale); }

    private final Map<String, int[]> textures = new HashMap<String, int[]>();
    public final Map<String, Custom> customs = new HashMap<String, Custom>();

    int[] texture(Hud hud, String path) {
        int[] t = textures.get(path);
        if (t != null) return t;
        t = new int[3];
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = am.open("images/" + path);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            t[0] = hud.loadTex(am, "images/" + path); t[1] = o.outWidth; t[2] = o.outHeight;
        } catch (Exception ignored) { }
        textures.put(path, t);
        return t;
    }

    /** draws a laid out tree; scale = screen pixels per UI unit */
    public void draw(Node root, Hud hud, float scale) {
        List<Node> all = new ArrayList<Node>();
        collect(root, all);
        Collections.sort(all, new Comparator<Node>() {
            public int compare(Node a, Node b) { return a.layer - b.layer; }   // stable: tree order inside a layer
        });
        for (Node n : all) drawOne(n, hud, scale);
    }

    private void collect(Node n, List<Node> out) {
        if (!n.visible) return;
        out.add(n);
        if (n.type.equals("button")) {                      // only the child of the current state
            String state = n.down ? n.p.optString("pressed_control", "pressed") : n.p.optString("default_control", "default");
            for (Node c : n.kids) if (c.name.equals(state)) collect(c, out);
            return;
        }
        for (Node c : n.kids) collect(c, out);
    }

    private static float[] rgb(Object c) {
        if (c instanceof JSONArray) {
            JSONArray a = (JSONArray) c;
            return new float[]{(float) a.optDouble(0, 1), (float) a.optDouble(1, 1), (float) a.optDouble(2, 1)};
        }
        return new float[]{1, 1, 1};
    }

    private static int argb(float[] c, float a) {
        return Math.round(a * 255) << 24 | Math.round(c[0] * 255) << 16 | Math.round(c[1] * 255) << 8 | Math.round(c[2] * 255);
    }

    private void drawOne(Node n, Hud hud, float s) {
        float alpha = (float) n.p.optDouble("alpha", 1.0);
        Object av = val(n.p.opt("alpha"), n.env);
        if (av instanceof Number) alpha = ((Number) av).floatValue();
        int col = argb(rgb(val(n.p.opt("color"), n.env)), alpha);
        if (n.type.equals("image")) {
            Object tx = val(n.p.opt("texture"), n.env);
            if (!(tx instanceof String)) return;
            int[] t = texture(hud, (String) tx);
            if (t[0] == 0) return;
            JSONArray uv = n.p.optJSONArray("uv"), us = n.p.optJSONArray("uv_size");
            float u0 = uv == null ? 0 : (float) uv.optDouble(0), v0 = uv == null ? 0 : (float) uv.optDouble(1);
            float uw = us == null ? t[1] : (float) us.optDouble(0), vh = us == null ? t[2] : (float) us.optDouble(1);
            Object ns = n.p.opt("nineslice_size");
            float l = 0, tp = 0, r = 0, b = 0;
            if (ns instanceof Number) l = tp = r = b = ((Number) ns).floatValue();
            else if (ns instanceof JSONArray) {
                JSONArray a = (JSONArray) ns;
                l = (float) a.optDouble(0); tp = (float) a.optDouble(1); r = (float) a.optDouble(2); b = (float) a.optDouble(3);
            }
            float X = n.x * s, Y = n.y * s, W = n.w * s, H = n.h * s, tw = t[1], th = t[2];
            if (l + r + tp + b == 0) { hud.image(t[0], X, Y, W, H, u0 / tw, v0 / th, (u0 + uw) / tw, (v0 + vh) / th, col); return; }
            float[] xs = {X, X + l * s, X + W - r * s, X + W}, ys = {Y, Y + tp * s, Y + H - b * s, Y + H};
            float[] us2 = {u0, u0 + l, u0 + uw - r, u0 + uw}, vs = {v0, v0 + tp, v0 + vh - b, v0 + vh};
            for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
                if (xs[i + 1] <= xs[i] || ys[j + 1] <= ys[j]) continue;
                hud.image(t[0], xs[i], ys[j], xs[i + 1] - xs[i], ys[j + 1] - ys[j], us2[i] / tw, vs[j] / th, us2[i + 1] / tw, vs[j + 1] / th, col);
            }
        } else if (n.type.equals("label")) {
            Object tv = val(n.p.opt("text"), n.env);
            String text = tv instanceof String ? (String) tv : "";
            if (text.equals("#version")) text = "v0.14.3 alpha";
            else if (text.startsWith("#") && n.data != null && n.data.get(text) != null) text = String.valueOf(n.data.get(text));
            else if (n.p.optBoolean("localize", true)) text = tr(text);
            float tw = hud.fontWidth(text), th = 8;
            String al = n.p.optString("alignment", n.p.optString("text_alignment", "center"));
            float[] a = anchor(al, n.w, n.h), at = anchor(al, tw, th);
            float x = n.x + a[0] - at[0], y = n.y + a[1] - at[1];
            hud.fontText(text, x * s, y * s, s, col, n.p.optBoolean("shadow", false));
        } else if (n.type.equals("custom")) {
            Custom c = customs.get(n.p.optString("renderer"));
            if (c != null) c.render(n, hud, s);
        }
    }

    // ------------------------------------------------------------------ input (touch = select)
    /** pointer down / move / up in UI units; returns the pressed button name when a press is released inside */
    /** the button node of the last fired press */
    public Node lastPressed;

    public String touch(Node root, int action, float x, float y) {
        List<Node> all = new ArrayList<Node>();
        buttons(root, all);
        String fired = null;
        for (Node b : all) {
            boolean in = x >= b.x && y >= b.y && x < b.x + b.w && y < b.y + b.h;
            if (action == 0) b.down = in;
            else if (action == 1) b.down = b.down && in;
            else {
                if (b.down && in) { fired = b.pressedName != null ? b.pressedName : "button.pressed"; lastPressed = b; }
                b.down = false;
            }
        }
        return fired;
    }

    private void buttons(Node n, List<Node> out) {
        if (!n.visible) return;
        if (n.type.equals("button")) out.add(n);
        for (Node c : n.kids) buttons(c, out);
    }
}
