package com.nova.client;

import android.util.Log;

import com.nova.client.mcmod.ModRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mod mobs: declarations "entity name { ... }" of the enabled mods, with the files of a Bedrock resource pack
 * (*.geo.json, *.animation.json, *.animation_controllers.json, *.entity.json, textures) inside the mod folder.
 *
 * Everything is compiled here into one blob for libnova (native/mobs.inc):
 *  - the model becomes a flat list of quads in Bedrock model space, each tied to its bone (cube rotations applied,
 *    UVs resolved: box or per-face), bones keep pivot / bind rotation / parent;
 *  - animations keep their keyframes, Molang becomes the bytecode of AnimationCompiler;
 *  - animation controllers (states, transitions, blend time) and scripts.animate are kept as they are.
 * Molang names: built-in queries have fixed numbers (BUILTIN), every other query.x / variable.x is a value of
 * the entity that the mod script sets: entity.set(e, "x", 1). So Bedrock controllers work unchanged.
 */
final class ModMobs {
    static final String TAG = "NOVA.mobs";

    static final String[] BUILTIN = {
            "query.anim_time", "query.life_time", "query.modified_distance_moved", "query.modified_move_speed",
            "query.target_y_rotation", "query.target_x_rotation", "query.is_on_ground", "query.nova_arg",
            "query.all_animations_finished", "query.any_animation_finished", "query.health", "query.is_alive",
            "query.hurt_time", "query.ground_speed", "query.is_in_water", "query.is_moving"};
    static final int CUSTOM0 = 16, MAX_SLOTS = 64;

    final List<String> names = new ArrayList<String>();     // "modid:name" in blob order
    final Map<String, Integer> index = new HashMap<String, Integer>();
    final List<List<String>> slots = new ArrayList<List<String>>();
    byte[] blob;

    private final ModRuntime rt;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    ModMobs(ModRuntime rt) { this.rt = rt; }

    void prepare(Map<String, File> dirs) {
        int n = 0;
        for (Object[] d : rt.decls("entity")) {
            ModRuntime.Mod m = (ModRuntime.Mod) d[0];
            String name = (String) d[1];
            int line = (Integer) d[3];
            if (!(d[2] instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) d[2];
            File dir = dirs.get(m.id);
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                List<String> sl = compile(m.id, name, p, dir, o);
                byte[] b = o.toByteArray();
                body.write(b, 0, b.length);
                index.put(m.id + ":" + name, names.size());
                index.put(name, names.size());
                names.add(m.id + ":" + name);
                slots.add(sl);
                n++;
            } catch (Throwable e) {
                Log.e(TAG, "entity " + name, e);
                rt.reportLater(m, "entity " + name + ": " + e.getMessage(), line);
            }
        }
        if (n == 0) return;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('N'); o.write('V'); o.write('M'); o.write('1');
        u16(o, n);
        byte[] b = body.toByteArray();
        o.write(b, 0, b.length);
        blob = o.toByteArray();
        Log.i(TAG, n + " mod mob(s), blob " + blob.length + " bytes");
    }

    // ------------------------------------------------------------------ one mob
    private static final class Files {
        final Map<String, JSONObject> geos = new HashMap<String, JSONObject>();          // geometry id -> geometry
        final Map<String, JSONObject> anims = new HashMap<String, JSONObject>();         // animation id -> animation
        final Map<String, JSONObject> ctrls = new HashMap<String, JSONObject>();         // controller id -> controller
        final Map<String, JSONObject> clients = new HashMap<String, JSONObject>();       // entity id -> description
        final Map<String, File> clientRoot = new HashMap<String, File>();                // entity id -> RP root
    }

    private static final Map<File, Files> scanned = new HashMap<File, Files>();

    private static Files scan(File dir) {
        Files f = scanned.get(dir);
        if (f != null) return f;
        f = new Files();
        walk(dir, f, 0);
        scanned.put(dir, f);
        return f;
    }

    private static void walk(File d, Files f, int depth) {
        File[] list = d.listFiles();
        if (list == null || depth > 8) return;
        for (File x : list) {
            if (x.isDirectory()) { walk(x, f, depth + 1); continue; }
            String n = x.getName().toLowerCase();
            if (!n.endsWith(".json")) continue;
            try {
                JSONObject j = new JSONObject(stripComments(PackManager.readText(x)));
                JSONArray g = j.optJSONArray("minecraft:geometry");
                if (g != null) for (int i = 0; i < g.length(); i++) {
                    JSONObject gi = g.getJSONObject(i);
                    f.geos.put(gi.getJSONObject("description").optString("identifier"), gi);
                }
                Iterator<String> it = j.keys();      // old format: "geometry.x": {bones...}
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.startsWith("geometry.") && j.optJSONObject(k) != null) f.geos.put(k.split(":")[0], j.getJSONObject(k));
                }
                JSONObject an = j.optJSONObject("animations");
                if (an != null && j.has("format_version") && !j.has("minecraft:client_entity")) {
                    Iterator<String> ai = an.keys();
                    while (ai.hasNext()) { String k = ai.next(); if (an.optJSONObject(k) != null) f.anims.put(k, an.getJSONObject(k)); }
                }
                JSONObject ac = j.optJSONObject("animation_controllers");
                if (ac != null) {
                    Iterator<String> ci = ac.keys();
                    while (ci.hasNext()) { String k = ci.next(); f.ctrls.put(k, ac.getJSONObject(k)); }
                }
                JSONObject ce = j.optJSONObject("minecraft:client_entity");
                if (ce != null && ce.optJSONObject("description") != null) {
                    JSONObject desc = ce.getJSONObject("description");
                    String id = desc.optString("identifier");
                    f.clients.put(id, desc);
                    f.clientRoot.put(id, x.getParentFile().getParentFile());
                }
            } catch (Exception e) {
                Log.w(TAG, "skip " + x + ": " + e.getMessage());
            }
        }
    }

    static String stripComments(String s) {       // Bedrock JSON may have // and /* */ comments
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
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') { i = s.indexOf("*/", i + 2); if (i < 0) break; i++; continue; }
            b.append(c);
        }
        return b.toString();
    }

    private static String str(Map<String, Object> p, String k, String dflt) {
        Object v = p.get(k);
        return v == null ? dflt : String.valueOf(v);
    }

    private static double num(Map<String, Object> p, String k, double dflt) {
        Object v = p.get(k);
        return v instanceof Double ? (Double) v : dflt;
    }

    private List<String> compile(String modId, String name, Map<String, Object> p, File dir, ByteArrayOutputStream o)
            throws Exception {
        if (dir == null) throw new Exception("нет папки мода");
        Files fs = scan(dir);
        // the client entity (Bedrock RP entity file): by identifier, or the only one
        JSONObject client = null;
        File root = dir;
        String cid = str(p, "client", null);
        if (cid != null) {
            client = fs.clients.get(cid);
            if (client == null) throw new Exception("client: нет сущности \"" + cid + "\" в файлах *.entity.json мода");
            root = fs.clientRoot.get(cid);
        }
        JSONObject animMap = client == null ? new JSONObject() : client.optJSONObject("animations");
        if (animMap == null) animMap = new JSONObject();
        // geometry
        String geoId = str(p, "geometry", null);
        if (geoId == null && client != null && client.optJSONObject("geometry") != null)
            geoId = client.getJSONObject("geometry").optString("default", null);
        JSONObject geo = geoId == null ? null : fs.geos.get(geoId);
        if (geo == null && geoId == null && fs.geos.size() == 1) geo = fs.geos.values().iterator().next();
        if (geo == null) throw new Exception("нет модели " + (geoId == null ? "(geometry: \"geometry.x\")" : geoId));
        // texture
        String tex = str(p, "texture", null);
        File texRoot = dir;
        if (tex == null && client != null && client.optJSONObject("textures") != null) {
            tex = client.getJSONObject("textures").optString("default", null);
            texRoot = root;
        }
        if (tex == null) throw new Exception("нет texture");
        if (!tex.endsWith(".png") && !tex.endsWith(".tga")) tex = tex + ".png";
        File tf = new File(texRoot, tex);
        if (!tf.isFile()) throw new Exception("нет картинки " + tex);
        String texRel = dir.toURI().relativize(tf.toURI()).getPath();
        // scale from scripts.scale
        float scale = (float) num(p, "scale", 1);
        JSONObject scripts = client == null ? null : client.optJSONObject("scripts");
        if (!p.containsKey("scale") && scripts != null && scripts.has("scale")) {
            try { scale = Float.parseFloat(scripts.optString("scale").trim()); } catch (NumberFormatException ignored) { }
        }

        final List<String> slots = new ArrayList<String>();
        AnimationCompiler.mobVars = new AnimationCompiler.MobVars() {
            public int slot(String full) {
                String f = full.toLowerCase();
                if (f.equals("query.distance_moved") || f.equals("query.walk_distance")) f = "query.modified_distance_moved";
                if (f.equals("query.head_y_rotation")) f = "query.target_y_rotation";
                if (f.equals("query.head_x_rotation")) f = "query.target_x_rotation";
                for (int i = 0; i < BUILTIN.length; i++) if (BUILTIN[i].equals(f)) return i;
                String k = f.substring(f.indexOf('.') + 1);           // query.x and variable.x share the value "x"
                int at = slots.indexOf(k);
                if (at < 0) {
                    if (CUSTOM0 + slots.size() >= MAX_SLOTS) return -1;
                    slots.add(k);
                    at = slots.size() - 1;
                }
                return CUSTOM0 + at;
            }
        };
        try {
            str(o, modId + ":" + name);
            str(o, "nova/mod/" + modId + "/" + texRel);
            f32(o, scale);
            f32(o, (float) num(p, "shadow", 0.5));
            List<?> size = p.get("size") instanceof List ? (List<?>) p.get("size") : null;
            f32(o, size != null && size.size() > 0 ? ((Double) size.get(0)).floatValue() : 0.6f);
            f32(o, size != null && size.size() > 1 ? ((Double) size.get(1)).floatValue() : 1.9f);
            f32(o, (float) num(p, "health", 20));
            f32(o, (float) num(p, "speed", 0.25));
            f32(o, (float) num(p, "damage", 3));
            // model
            Map<String, Integer> boneIdx = new HashMap<String, Integer>();
            mesh(geo, o, boneIdx);
            // animations used by this entity: from the client file's map, or every animation of the mod
            List<String> animNames = new ArrayList<String>();       // short names
            List<JSONObject> animList = new ArrayList<JSONObject>();
            List<String> ctrlNames = new ArrayList<String>();
            List<JSONObject> ctrlList = new ArrayList<JSONObject>();
            Iterator<String> it = animMap.keys();
            while (it.hasNext()) {
                String shortName = it.next(), full = animMap.optString(shortName);
                if (fs.anims.containsKey(full)) { animNames.add(shortName); animList.add(fs.anims.get(full)); }
                else if (fs.ctrls.containsKey(full)) { ctrlNames.add(shortName); ctrlList.add(fs.ctrls.get(full)); }
                else Log.w(TAG, name + ": no animation " + full);
            }
            if (client == null) {                       // no client file: every animation / controller by its full id
                for (Map.Entry<String, JSONObject> e : fs.anims.entrySet()) { animNames.add(e.getKey()); animList.add(e.getValue()); }
                for (Map.Entry<String, JSONObject> e : fs.ctrls.entrySet()) { ctrlNames.add(e.getKey()); ctrlList.add(e.getValue()); }
            }
                        u16(o, animList.size());
            for (int a = 0; a < animList.size(); a++) anim(animNames.get(a), animList.get(a), boneIdx, o);
            u16(o, ctrlList.size());
            for (JSONObject c : ctrlList) controller(c, animNames, o);
            // scripts.animate (or animate: [...] in the declaration)
            List<Object[]> animate = new ArrayList<Object[]>();
            Object decl = p.get("animate");
            JSONArray ja = scripts == null ? null : scripts.optJSONArray("animate");
            if (decl instanceof List) for (Object x : (List<?>) decl) animate.add(new Object[]{String.valueOf(x), null});
            else if (ja != null) for (int i = 0; i < ja.length(); i++) {
                Object x = ja.get(i);
                if (x instanceof JSONObject) {
                    Iterator<String> ki = ((JSONObject) x).keys();
                    if (ki.hasNext()) { String k = ki.next(); animate.add(new Object[]{k, ((JSONObject) x).opt(k)}); }
                } else animate.add(new Object[]{String.valueOf(x), null});
            }
            if (animate.isEmpty() && !ctrlNames.isEmpty()) animate.add(new Object[]{ctrlNames.get(0), null});
            ByteArrayOutputStream ao = new ByteArrayOutputStream();
            int na = 0;
            for (Object[] e : animate) {
                String nm = (String) e[0];
                int ai = animNames.indexOf(nm), ci = ctrlNames.indexOf(nm);
                if (ai < 0 && ci < 0) { Log.w(TAG, name + ": animate " + nm + " not found"); continue; }
                u8(ao, ci >= 0 ? 1 : 0);
                u16(ao, ci >= 0 ? ci : ai);
                prog(ao, e[1]);
                na++;
            }
            u16(o, na);
            byte[] ab = ao.toByteArray();
            o.write(ab, 0, ab.length);
            // per-entity values (names the script uses with entity.set / entity.get)
            u16(o, slots.size());
            for (String s : slots) str(o, s);
        } finally {
            AnimationCompiler.mobVars = null;
        }
        return slots;
    }

    // ------------------------------------------------------------------ model
    static final String[] FACES = {"west", "east", "up", "down", "north", "south"};
    // corner indices of Java's ModelBox quads (v0..v7 below), faces in the order above
    static final int[][] QUAD = {{5, 1, 2, 6}, {0, 4, 7, 3}, {5, 4, 0, 1}, {2, 3, 7, 6}, {1, 0, 3, 2}, {4, 5, 6, 7}};
    // where each corner sits in the face's UV rectangle (0 = u1/v1, 1 = u2/v2), Java's TexturedQuad
    static final float[][] TU = {{1, 0, 0, 1}, {1, 0, 0, 1}, {1, 0, 0, 1}, {1, 0, 0, 1}, {1, 0, 0, 1}, {1, 0, 0, 1}};
    static final float[][] TV = {{0, 0, 1, 1}, {0, 0, 1, 1}, {0, 0, 1, 1}, {0, 0, 1, 1}, {0, 0, 1, 1}, {0, 0, 1, 1}};
    static final float[][] NORMAL = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}};

    private static float[] v3(JSONArray a, float d) {
        if (a == null) return new float[]{d, d, d};
        return new float[]{(float) a.optDouble(0, d), (float) a.optDouble(1, d), (float) a.optDouble(2, d)};
    }

    /** rotation in Bedrock model space (x as in the file, y up): Rz(-z) Ry(y) Rx(-x), degrees */
    static float[] rotMat(float[] r) {
        double ax = Math.toRadians(-r[0]), ay = Math.toRadians(r[1]), az = Math.toRadians(-r[2]);
        double cx = Math.cos(ax), sx = Math.sin(ax), cy = Math.cos(ay), sy = Math.sin(ay), cz = Math.cos(az), sz = Math.sin(az);
        // Rz * Ry * Rx
        return new float[]{
                (float) (cz * cy), (float) (cz * sy * sx - sz * cx), (float) (cz * sy * cx + sz * sx),
                (float) (sz * cy), (float) (sz * sy * sx + cz * cx), (float) (sz * sy * cx - cz * sx),
                (float) (-sy), (float) (cy * sx), (float) (cy * cx)};
    }

    static float[] mul(float[] m, float[] v) {
        return new float[]{m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    private void mesh(JSONObject geo, ByteArrayOutputStream o, Map<String, Integer> boneIdx) throws Exception {
        JSONObject desc = geo.optJSONObject("description");
        float tw = desc != null ? (float) desc.optDouble("texture_width", 64) : (float) geo.optDouble("texturewidth", 64);
        float th = desc != null ? (float) desc.optDouble("texture_height", 64) : (float) geo.optDouble("textureheight", 64);
        JSONArray bones = geo.optJSONArray("bones");
        if (bones == null) bones = new JSONArray();
        int nb = bones.length();
        for (int i = 0; i < nb; i++) boneIdx.put(bones.getJSONObject(i).optString("name"), i);
        u16(o, nb);
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        int nq = 0;
        for (int i = 0; i < nb; i++) {
            JSONObject b = bones.getJSONObject(i);
            String par = b.optString("parent", null);
            Integer pi = par == null ? null : boneIdx.get(par);
            if (pi == null && par != null) for (Map.Entry<String, Integer> e : boneIdx.entrySet())
                if (e.getKey().equalsIgnoreCase(par)) pi = e.getValue();
            float[] piv = v3(b.optJSONArray("pivot"), 0), rot = v3(b.optJSONArray("rotation"), 0);
            u16(o, pi == null ? 0xffff : pi);
            for (int k = 0; k < 3; k++) f32(o, piv[k]);
            for (int k = 0; k < 3; k++) f32(o, rot[k]);
            boolean boneMirror = b.optBoolean("mirror", false);
            JSONArray cubes = b.optJSONArray("cubes");
            if (cubes == null) continue;
            for (int c = 0; c < cubes.length(); c++) {
                JSONObject cu = cubes.getJSONObject(c);
                float[] or = v3(cu.optJSONArray("origin"), 0), sz = v3(cu.optJSONArray("size"), 0);
                float inf = (float) cu.optDouble("inflate", 0);
                float x0 = or[0] - inf, y0 = or[1] - inf, z0 = or[2] - inf;
                float x1 = or[0] + sz[0] + inf, y1 = or[1] + sz[1] + inf, z1 = or[2] + sz[2] + inf;
                // Java labels (y down): v0 = (x min, top, z min) ... ; top in Bedrock space = y max
                float[][] v = {{x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0},
                        {x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}};
                float[] crot = cu.optJSONArray("rotation") == null ? null : v3(cu.optJSONArray("rotation"), 0);
                float[] m = null, cp = null;
                if (crot != null && (crot[0] != 0 || crot[1] != 0 || crot[2] != 0)) {
                    m = rotMat(crot);
                    cp = cu.optJSONArray("pivot") == null
                            ? new float[]{or[0] + sz[0] / 2, or[1] + sz[1] / 2, or[2] + sz[2] / 2} : v3(cu.optJSONArray("pivot"), 0);
                    for (int k = 0; k < 8; k++) {
                        float[] d = mul(m, new float[]{v[k][0] - cp[0], v[k][1] - cp[1], v[k][2] - cp[2]});
                        v[k] = new float[]{d[0] + cp[0], d[1] + cp[1], d[2] + cp[2]};
                    }
                }
                // UV rectangles (u1, v1, u2, v2) per face
                float[][] rect = new float[6][];
                Object uvo = cu.opt("uv");
                if (uvo instanceof JSONObject) {
                    JSONObject fu = (JSONObject) uvo;
                    for (int f = 0; f < 6; f++) {
                        JSONObject ff = fu.optJSONObject(FACES[f]);
                        if (ff == null) continue;
                        float[] u = v3(ff.optJSONArray("uv"), 0);
                        JSONArray us = ff.optJSONArray("uv_size");
                        float uw = us == null ? 0 : (float) us.optDouble(0), uh = us == null ? 0 : (float) us.optDouble(1);
                        float u1 = u[0], v1 = u[1], u2 = u[0] + uw, v2 = u[1] + uh;
                        if (f == 2 || f == 3) { float t = u1; u1 = u2; u2 = t; t = v1; v1 = v2; v2 = t; }
                        // corner rule of the face (TU/TV) is relative to Java's box rectangle; re-express in it
                        rect[f] = f == 3 ? new float[]{u1, v2, u2, v1} : new float[]{u1, v1, u2, v2};
                    }
                } else {
                    float[] u = uvo instanceof JSONArray ? v3((JSONArray) uvo, 0) : new float[]{0, 0, 0};
                    float dx = (float) Math.floor(sz[0]), dy = (float) Math.floor(sz[1]), dz = (float) Math.floor(sz[2]);
                    float U = u[0], V = u[1];
                    rect[0] = new float[]{U + dz + dx, V + dz, U + dz + dx + dz, V + dz + dy};
                    rect[1] = new float[]{U, V + dz, U + dz, V + dz + dy};
                    rect[2] = new float[]{U + dz, V, U + dz + dx, V + dz};
                    rect[3] = new float[]{U + dz + dx, V + dz, U + dz + dx + dx, V};
                    rect[4] = new float[]{U + dz, V + dz, U + dz + dx, V + dz + dy};
                    rect[5] = new float[]{U + dz + dx + dz, V + dz, U + dz + dx + dz + dx, V + dz + dy};
                    if (cu.optBoolean("mirror", boneMirror)) {
                        float[] t = rect[0]; rect[0] = rect[1]; rect[1] = t;
                        for (int f = 0; f < 6; f++) { float a = rect[f][0]; rect[f][0] = rect[f][2]; rect[f][2] = a; }
                    }
                }
                for (int f = 0; f < 6; f++) {
                    if (rect[f] == null) continue;
                    float[] r = rect[f];
                    if (r[0] == r[2] && r[1] == r[3] && uvo instanceof JSONObject) continue;      // zero-size face
                    float[] nrm = m == null ? NORMAL[f] : mul(m, NORMAL[f]);
                    u16(q, i);
                    for (int k = 0; k < 3; k++) f32(q, nrm[k]);
                    for (int k = 0; k < 4; k++) {
                        float[] p = v[QUAD[f][k]];
                        f32(q, p[0]); f32(q, p[1]); f32(q, p[2]);
                        float uu = r[0] + TU[f][k] * (r[2] - r[0]), vv = r[1] + TV[f][k] * (r[3] - r[1]);
                        f32(q, uu / tw); f32(q, vv / th);
                    }
                    nq++;
                }
            }
        }
        u32(o, nq);
        byte[] qb = q.toByteArray();
        o.write(qb, 0, qb.length);
    }

    // ------------------------------------------------------------------ animations
    private static void anim(String shortName, JSONObject an, Map<String, Integer> boneIdx, ByteArrayOutputStream o)
            throws Exception {
        str(o, shortName);
        Object loop = an.opt("loop");
        u8(o, "hold_on_last_frame".equals(loop) ? 2 : Boolean.TRUE.equals(loop) || "true".equals(String.valueOf(loop)) ? 1 : 0);
        f32(o, (float) an.optDouble("animation_length", longest(an)));
        prog(o, an.opt("anim_time_update"));
        prog(o, an.opt("blend_weight"));
        JSONObject bs = an.optJSONObject("bones");
        List<byte[]> chans = new ArrayList<byte[]>();
        if (bs != null) {
            Iterator<String> it = bs.keys();
            while (it.hasNext()) {
                String bone = it.next();
                Integer bi = boneIdx.get(bone);
                if (bi == null) for (Map.Entry<String, Integer> e : boneIdx.entrySet()) if (e.getKey().equalsIgnoreCase(bone)) bi = e.getValue();
                if (bi == null) continue;
                JSONObject b = bs.optJSONObject(bone);
                if (b == null) continue;
                String[] kinds = {"rotation", "position", "scale"};
                for (int k = 0; k < 3; k++) if (b.has(kinds[k])) chans.add(channel(bi, k, b.get(kinds[k])));
            }
        }
        u16(o, chans.size());
        for (byte[] c : chans) o.write(c, 0, c.length);
    }

    private static float longest(JSONObject an) {
        float max = 0;
        JSONObject bs = an.optJSONObject("bones");
        if (bs == null) return 0;
        Iterator<String> it = bs.keys();
        while (it.hasNext()) {
            JSONObject b = bs.optJSONObject(it.next());
            if (b == null) continue;
            for (String ch : new String[]{"rotation", "position", "scale"}) {
                JSONObject k = b.optJSONObject(ch);
                if (k == null || k.has("post") || k.has("pre") || k.has("vector")) continue;
                Iterator<String> ki = k.keys();
                while (ki.hasNext()) {
                    try { max = Math.max(max, Float.parseFloat(ki.next())); } catch (NumberFormatException ignored) { }
                }
            }
        }
        return max;
    }

    private static Object[] vec3(Object v, boolean pre) {
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            if (a.length() == 1) return new Object[]{a.opt(0), a.opt(0), a.opt(0)};
            return new Object[]{a.opt(0), a.opt(1), a.opt(2)};
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            if (pre && o.has("pre")) return vec3(o.opt("pre"), false);
            if (o.has("post")) return vec3(o.opt("post"), false);
            if (o.has("pre")) return vec3(o.opt("pre"), false);
            if (o.has("vector")) return vec3(o.opt("vector"), false);
        }
        return new Object[]{v, v, v};
    }

    /** bone, kind (0 rotation, 1 position, 2 scale), nkeys; per key: time, lerp (0 linear, 1 catmullrom, 2 step),
     *  value before the key ("pre") and after it ("post"), 3 progs each; nkeys 0: one value (3 progs). */
    private static byte[] channel(int bone, int kind, Object v) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, bone);
        u8(o, kind);
        JSONObject keys = v instanceof JSONObject && !((JSONObject) v).has("post") && !((JSONObject) v).has("pre")
                && !((JSONObject) v).has("vector") ? (JSONObject) v : null;
        if (keys == null) {
            u8(o, 0);
            for (Object x : vec3(v, false)) prog(o, x);
            return o.toByteArray();
        }
        List<Float> times = new ArrayList<Float>();
        Map<Float, Object> byTime = new HashMap<Float, Object>();
        Iterator<String> it = keys.keys();
        while (it.hasNext()) {
            String k = it.next();
            try { float t = Float.parseFloat(k); times.add(t); byTime.put(t, keys.opt(k)); } catch (NumberFormatException ignored) { }
        }
        java.util.Collections.sort(times);
        int n = Math.min(255, times.size());
        u8(o, n);
        for (int i = 0; i < n; i++) {
            Object kv = byTime.get(times.get(i));
            f32(o, times.get(i));
            String lm = kv instanceof JSONObject ? ((JSONObject) kv).optString("lerp_mode", "linear") : "linear";
            u8(o, "catmullrom".equals(lm) ? 1 : "step".equals(lm) ? 2 : 0);
            for (Object x : vec3(kv, true)) prog(o, x);
            for (Object x : vec3(kv, false)) prog(o, x);
        }
        return o.toByteArray();
    }

    private static void controller(JSONObject c, List<String> animNames, ByteArrayOutputStream o) throws Exception {
        JSONObject states = c.optJSONObject("states");
        List<String> sn = new ArrayList<String>();
        if (states != null) { Iterator<String> it = states.keys(); while (it.hasNext()) sn.add(it.next()); }
        int init = Math.max(0, sn.indexOf(c.optString("initial_state", "default")));
        u16(o, init);
        u16(o, sn.size());
        for (String s : sn) {
            JSONObject st = states.getJSONObject(s);
            str(o, s);
            Object bt = st.opt("blend_transition");
            f32(o, bt instanceof Number ? ((Number) bt).floatValue() : 0);
            List<Object[]> an = new ArrayList<Object[]>();
            JSONArray al = st.optJSONArray("animations");
            if (al != null) for (int i = 0; i < al.length(); i++) {
                Object x = al.get(i);
                String nm; Object w = null;
                if (x instanceof JSONObject) {
                    Iterator<String> ki = ((JSONObject) x).keys();
                    if (!ki.hasNext()) continue;
                    nm = ki.next(); w = ((JSONObject) x).opt(nm);
                } else nm = String.valueOf(x);
                int ai = animNames.indexOf(nm);
                if (ai >= 0) an.add(new Object[]{ai, w});
            }
            u16(o, an.size());
            for (Object[] a : an) { u16(o, (Integer) a[0]); prog(o, a[1]); }
            JSONArray tr = st.optJSONArray("transitions");
            List<Object[]> ts = new ArrayList<Object[]>();
            if (tr != null) for (int i = 0; i < tr.length(); i++) {
                JSONObject t = tr.optJSONObject(i);
                if (t == null) continue;
                Iterator<String> ki = t.keys();
                if (!ki.hasNext()) continue;
                String target = ki.next();
                int ti = sn.indexOf(target);
                if (ti >= 0) ts.add(new Object[]{ti, t.opt(target)});
            }
            u16(o, ts.size());
            for (Object[] t : ts) { u16(o, (Integer) t[0]); prog(o, t[1]); }
        }
    }

    // ------------------------------------------------------------------ bytes
    static void u8(ByteArrayOutputStream o, int v) { o.write(v); }
    static void u16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }
    static void u32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    static void f32(ByteArrayOutputStream o, float f) { u32(o, Float.floatToIntBits(f)); }
    static void str(ByteArrayOutputStream o, String s) {
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
        int n = Math.min(255, b.length);
        o.write(n);
        o.write(b, 0, n);
    }
    static void prog(ByteArrayOutputStream o, Object expr) {
        byte[] p = AnimationCompiler.Molang.compile(expr);
        u16(o, p.length);
        o.write(p, 0, p.length);
    }
}
