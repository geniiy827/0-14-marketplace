package com.nova.client;

import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * NOVA entity tree: assets/entities/<id>/ holds a mob's textures, its model
 * (<id>.geo.json, Bedrock/Blockbench 1.12 geometry) and animations.
 *
 * - every legacy texture path (images/mob/...) is redirected into the folder;
 * - the engine's images/mob/skins/Base/Mobs.json is generated from the
 *   *.geo.json files, so editing a model in Blockbench changes the game.
 *   Rotated cubes (not supported by the 0.14 loader) become child bones.
 *
 * Every file is read through the .mcres pack overrides first, so packs can
 * replace entities/<id>/<file> directly.
 */
final class EntityRegistry {
    static final String TAG = "NOVA.entities";
    static final String MOBS_JSON = "images/mob/skins/Base/Mobs.json";

    interface Source { File override(String path); }

    private final AssetManager am;
    private final Source packs;
    private final Map<String, String> redirect = new HashMap<String, String>();
    private final List<String> geoFiles = new ArrayList<String>();
    private final List<String> animFiles = new ArrayList<String>();
    private final List<JSONObject> entityDescs = new ArrayList<JSONObject>();
    private final List<JSONObject> rebuilds = new ArrayList<JSONObject>();
    /** per-face UV of every cube that has one, collected while building Mobs.json */
    private final java.io.ByteArrayOutputStream faceUv = new java.io.ByteArrayOutputStream();
    private int faceUvCount;
    private byte[] mobsJson;
    volatile boolean mobsServed;
    private JSONObject nativeBones = new JSONObject();

    EntityRegistry(AssetManager am, Source packs) {
        this.am = am;
        this.packs = packs;
        try {
            String[] dirs = am.list("entities");
            if (dirs != null) for (String d : dirs) scan("entities/" + d);
        } catch (Throwable t) {
            Log.e(TAG, "entity scan failed", t);
        }
        Log.i(TAG, "entities: " + redirect.size() + " textures, " + geoFiles.size() + " model files");
    }

    private void scan(String dir) throws Exception {
        String[] files = am.list(dir);
        if (files == null) return;
        boolean jsonModel = false;
        List<String> geos = new ArrayList<String>();
        for (String f : files) {
            if (f.endsWith(".geo.json")) geos.add(dir + "/" + f);
            if (f.endsWith(".animation.json")) animFiles.add(dir + "/" + f);
            if (!f.endsWith(".entity.json")) continue;
            JSONObject desc = new JSONObject(new String(read(dir + "/" + f), "UTF-8"))
                    .getJSONObject("minecraft:client_entity").getJSONObject("description");
            entityDescs.add(desc);
            JSONObject leg = desc.optJSONObject("nova:legacy");
            if (leg == null) continue;
            String model = leg.optString("model");
            jsonModel = "json".equals(model) || "rebuild".equals(model);
            JSONArray rb = leg.optJSONArray("rebuild");
            for (int i = 0; rb != null && i < rb.length(); i++) rebuilds.add(rb.getJSONObject(i));
            JSONObject tex = leg.optJSONObject("textures");
            if (tex != null) {
                Iterator<String> it = tex.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    redirect.put(k, dir + "/" + tex.getString(k));
                }
            }
        }
        // folders without an entity.json (shared templates) feed the engine too
        boolean hasEntity = false;
        for (String f : files) if (f.endsWith(".entity.json")) hasEntity = true;
        if (jsonModel || !hasEntity) geoFiles.addAll(geos);
    }

    /** entities/... path that replaces a legacy game path, or null. */
    String redirect(String path) {
        return redirect.get(path);
    }

    boolean isMobsJson(String path) {
        return MOBS_JSON.equals(path);
    }

    boolean hasOverride(String path) { return packs.override(path) != null; }

    /** Pack file first, then the APK asset. */
    byte[] read(String path) throws Exception {
        File f = packs.override(path);
        if (f != null) return PackManager.readFile(f);
        InputStream in = am.open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Legacy Mobs.json built from the entity models; null = use the game file. */
    synchronized byte[] mobsJson() {
        if (mobsJson != null) return mobsJson;
        JSONObject out = new JSONObject();
        int n = 0;
        try {
            nativeBones = new JSONObject(new String(read("entities/_shared/native_bones.json"), "UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "native_bones.json missing", t);
        }
        for (String file : geoFiles) {
            try {
                JSONObject root = new JSONObject(stripComments(new String(read(file), "UTF-8")));
                JSONArray geos = root.getJSONArray("minecraft:geometry");
                for (int i = 0; i < geos.length(); i++) {
                    JSONObject g = geos.getJSONObject(i);
                    String id = g.getJSONObject("description").getString("identifier");
                    out.put(id, toLegacy(g, nativeBones.optJSONArray(id), this));
                    n++;
                }
            } catch (Throwable t) {
                Log.e(TAG, "bad model file " + file, t);
            }
        }
        if (n == 0) return null;
        try {
            mobsJson = out.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
        Log.i(TAG, "Mobs.json generated: " + n + " geometries, " + mobsJson.length + " bytes");
        return mobsJson;
    }

    /**
     * Bones the C++ model loads by name keep their name; any other bone with a
     * parent is stored as "<parent key>><name>" so libnova attaches it as a
     * child ModelPart of its parent (the 0.14 loader ignores "parent").
     */
    /** Bedrock animations of every entity, compiled for libnova; null if none. */
    byte[] animations() {
        Map<String, JSONObject> defs = new HashMap<String, JSONObject>();
        for (String file : animFiles) {
            try {
                JSONObject a = new JSONObject(stripComments(new String(read(file), "UTF-8"))).getJSONObject("animations");
                Iterator<String> it = a.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    defs.put(id, a.getJSONObject(id));
                }
            } catch (Throwable t) {
                Log.e(TAG, "bad animation file " + file, t);
            }
        }
        AnimationCompiler c = new AnimationCompiler();
        java.util.HashSet<String> done = new java.util.HashSet<String>();
        for (JSONObject d : entityDescs) {
            try {
                JSONObject geo = d.optJSONObject("geometry");
                JSONObject anims = d.optJSONObject("animations");
                JSONObject scripts = d.optJSONObject("scripts");
                JSONArray animate = scripts == null ? null : scripts.optJSONArray("animate");
                if (geo == null || anims == null || animate == null) continue;
                String g = geo.optString("default", null);
                if (g == null || !done.add(g)) continue;
                List<JSONObject> list = new ArrayList<JSONObject>();
                List<Object> weights = new ArrayList<Object>();
                for (int i = 0; i < animate.length(); i++) {
                    Object e = animate.get(i);
                    String shortName;
                    Object weight = null;
                    if (e instanceof JSONObject) {
                        JSONObject o = (JSONObject) e;
                        if (o.length() == 0) continue;
                        shortName = (String) o.keys().next();
                        weight = o.opt(shortName);
                    } else shortName = String.valueOf(e);
                    String full = anims.optString(shortName, null);
                    JSONObject def = full == null ? null : defs.get(full);
                    if (def == null) {
                        if (full != null && !full.startsWith("controller.")) Log.w(TAG, g + ": animation not found " + full);
                        continue;
                    }
                    list.add(def);
                    weights.add(weight);
                }
                if (!list.isEmpty()) c.addSet(g, list, weights);
            } catch (Throwable t) {
                Log.e(TAG, "entity animations failed", t);
            }
        }
        Log.i(TAG, "animation sets: " + c.sets());
        return c.sets() == 0 ? null : c.build();
    }

    static final String[] FACES = {"west", "east", "up", "down", "north", "south"};   // libnova quad order

    /** record per-face UVs of one cube for libnova */
    void faceUv(String geo, String boneKey, int cube, JSONObject faces) throws Exception {
        java.io.ByteArrayOutputStream o = faceUv;
        str(o, geo);
        str(o, boneKey);
        o.write(cube);
        int mask = 0;
        for (int i = 0; i < 6; i++) if (faces.optJSONObject(FACES[i]) != null) mask |= 1 << i;
        o.write(mask);
        for (int i = 0; i < 6; i++) {
            JSONObject f = faces.optJSONObject(FACES[i]);
            if (f == null) continue;
            JSONArray uv = f.optJSONArray("uv"), sz = f.optJSONArray("uv_size");
            f32(o, uv == null ? 0 : (float) uv.optDouble(0));
            f32(o, uv == null ? 0 : (float) uv.optDouble(1));
            f32(o, sz == null ? 0 : (float) sz.optDouble(0));
            f32(o, sz == null ? 0 : (float) sz.optDouble(1));
        }
        faceUvCount++;
    }

    byte[] faceUvBlob() {
        if (faceUvCount == 0) return null;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write('N'); o.write('V'); o.write('F'); o.write('1');
        o.write(faceUvCount); o.write(faceUvCount >> 8);
        byte[] b = faceUv.toByteArray();
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    /** C++ models libnova rebuilds from geometry: class vtable, geometry, condition, parts. */
    byte[] rebuildBlob() {
        if (rebuilds.isEmpty()) return null;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write('N'); o.write('V'); o.write('R'); o.write('1');
        o.write(rebuilds.size()); o.write(rebuilds.size() >> 8);
        for (JSONObject r : rebuilds) {
            String cls = r.optString("class");
            str(o, "_ZTV" + cls.length() + cls);
            str(o, r.optString("geometry"));
            JSONArray when = r.optJSONArray("when");
            i32(o, when == null ? -1 : when.optInt(0));
            i32(o, when == null ? 0 : when.optInt(1));
            JSONObject parts = r.optJSONObject("parts");
            int n = parts == null ? 0 : parts.length();
            o.write(n); o.write(n >> 8);
            if (parts != null) {
                Iterator<String> it = parts.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    str(o, k);
                    int off = parts.optInt(k);
                    o.write(off); o.write(off >> 8);
                }
            }
        }
        return o.toByteArray();
    }

    static void str(java.io.ByteArrayOutputStream o, String s) {
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
        int n = Math.min(255, b.length);
        o.write(n);
        o.write(b, 0, n);
    }

    static void f32(java.io.ByteArrayOutputStream o, float f) { i32(o, Float.floatToIntBits(f)); }

    static void i32(java.io.ByteArrayOutputStream o, int b) {
        o.write(b); o.write(b >> 8); o.write(b >> 16); o.write(b >> 24);
    }

    static JSONObject toLegacy(JSONObject g, JSONArray nativeNames, EntityRegistry sink) throws Exception {
        String geoId = g.getJSONObject("description").optString("identifier");
        java.util.HashSet<String> nat = new java.util.HashSet<String>();
        for (int i = 0; nativeNames != null && i < nativeNames.length(); i++) nat.add(nativeNames.getString(i));
        Map<String, String> key = new HashMap<String, String>();
        JSONArray all = g.optJSONArray("bones");
        for (int pass = 0; all != null && pass < all.length(); pass++) {   // resolve chains in any order
            for (int i = 0; i < all.length(); i++) {
                JSONObject b = all.getJSONObject(i);
                String nm = b.getString("name"), par = b.optString("parent", null);
                if (par == null || par.length() == 0 || nat.contains(nm)) key.put(nm, nm);
                else if (key.containsKey(par)) key.put(nm, key.get(par) + ">" + nm);
            }
        }
        JSONObject d = g.getJSONObject("description");
        JSONObject o = new JSONObject();
        o.put("texturewidth", d.optInt("texture_width", 64));
        o.put("textureheight", d.optInt("texture_height", 32));
        JSONArray bones = new JSONArray();
        JSONArray src = g.optJSONArray("bones");
        for (int i = 0; src != null && i < src.length(); i++) {
            JSONObject b = src.getJSONObject(i);
            String name = key.containsKey(b.getString("name")) ? key.get(b.getString("name")) : b.getString("name");
            boolean boneMirror = b.optBoolean("mirror", false);
            JSONObject nb = new JSONObject();
            nb.put("name", name);
            copy(b, nb, "parent", "parent");
            copy(b, nb, "pivot", "pivot");
            copy(b, nb, "rotation", "rotation");
            copy(b, nb, "inflate", "inflate");
            copy(b, nb, "material", "material");
            copy(b, nb, "pos", "pos");
            if (boneMirror) nb.put("mirror", true);
            if (b.optBoolean("never_render", false)) nb.put("neverRender", true);
            if (b.optBoolean("reset", false)) nb.put("reset", "true");
            JSONArray plain = new JSONArray();
            List<JSONObject> extra = new ArrayList<JSONObject>();
            JSONArray cubes = b.optJSONArray("cubes");
            for (int c = 0; cubes != null && c < cubes.length(); c++) {
                JSONObject cu = cubes.getJSONObject(c);
                JSONObject nc = new JSONObject();
                nc.put("origin", cu.getJSONArray("origin"));
                nc.put("size", cu.getJSONArray("size"));
                Object uv = cu.opt("uv");
                JSONObject perFace = uv instanceof JSONObject ? (JSONObject) uv : null;
                if (uv instanceof JSONArray) nc.put("uv", uv);
                else nc.put("uv", new JSONArray("[0,0]"));   // per-face: libnova rewrites the UVs
                copy(cu, nc, "inflate", "inflate");
                boolean cubeMirror = cu.optBoolean("mirror", boneMirror);
                JSONArray rot = cu.optJSONArray("rotation");
                boolean rotated = rot != null && (rot.optDouble(0) != 0 || rot.optDouble(1) != 0 || rot.optDouble(2) != 0);
                if (!rotated && cubeMirror == boneMirror) {
                    if (perFace != null && sink != null) sink.faceUv(geoId, name, plain.length(), perFace);
                    plain.put(nc);
                    continue;
                }
                // the 0.14 loader cannot rotate a cube: wrap it in its own child bone
                JSONObject cb = new JSONObject();
                cb.put("name", name + ">~" + c);
                cb.put("parent", name);
                JSONArray piv = cu.optJSONArray("pivot");
                if (piv == null) {
                    JSONArray or = cu.getJSONArray("origin"), sz = cu.getJSONArray("size");
                    piv = new JSONArray();
                    for (int k = 0; k < 3; k++) piv.put(or.getDouble(k) + sz.getDouble(k) / 2.0);
                }
                cb.put("pivot", piv);
                if (rotated) cb.put("rotation", rot);
                if (cubeMirror) cb.put("mirror", true);
                copy(b, cb, "material", "material");
                cb.put("cubes", new JSONArray().put(nc));
                if (perFace != null && sink != null) sink.faceUv(geoId, name + ">~" + c, 0, perFace);
                extra.add(cb);
            }
            if (plain.length() > 0) nb.put("cubes", plain);
            bones.put(nb);
            for (JSONObject e : extra) bones.put(e);
        }
        o.put("bones", bones);
        return o;
    }

    private static void copy(JSONObject from, JSONObject to, String a, String b) throws Exception {
        if (from.has(a)) to.put(b, from.get(a));
    }

    static String stripComments(String s) {
        StringBuilder o = new StringBuilder(s.length());
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                o.append(c);
                if (c == '\\' && i + 1 < s.length()) o.append(s.charAt(++i));
                else if (c == '"') str = false;
            } else if (c == '"') {
                str = true;
                o.append(c);
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                o.append('\n');
            } else {
                o.append(c);
            }
        }
        return o.toString();
    }
}
