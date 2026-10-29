package com.nova.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.nova.client.mcmod.ModRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mod blocks: `block name { ... }` declarations of the enabled mods.
 *
 *   classic:  texture: "stone" | "textures/ruby.png"            one texture for every face
 *             textures: {up: .., down: .., side: .., north: .., south: .., west: .., east: ..}
 *             box: [x0, y0, z0, x1, y1, z1]                       optional, pixels 0..16
 *   model:    model: "models/lamp.bbmodel" | "models/lamp.geo.json"   Blockbench, any number of cubes,
 *             texture: "textures/lamp.png"                        needed for .geo.json (bbmodel has its own)
 *   common:   name: "Лампа" | {ru: .., en: ..}, light: 0..15, hardness: 1.5, resistance: 5,
 *             sound: "stone" | "wood" | "glass" | ..., transparent: true/false, id: 220 (optional)
 *
 * Texture references: a block texture name of the game atlas ("stone", "planks:2"), or a PNG path
 * looked up in the mod, then in the resource packs, then in the game.
 * New PNGs are packed into a strip added under the block atlas (512x256 -> 512x512); AtlasBuilder
 * draws them and terrain.meta is served with the new height. Collision is always a full block.
 */
final class ModBlocks {
    static final String TAG = "NOVA.blocks";
    static final int FIRST_ID = 210, LAST_ID = 242;
    static final int CUBE = 36;   // box 6, uv 24, rotation 3 (degrees), pivot 3 (block units) - native MB_CUBE
    static final int STRIP_Y = 256, ATLAS_W = 512, ATLAS_H = 512;

    /** an image to draw into the atlas strip (base atlas pixels) */
    static final class Extra {
        Bitmap bm;
        int x, y, w, h;
    }

    final List<Extra> extras = new ArrayList<Extra>();
    final Map<String, String> lang = new LinkedHashMap<String, String>();      // key -> display name
    double[] data = new double[0];
    String names = "";
    int count;

    private final Context ctx;
    private final ModRuntime rt;
    private JSONObject layout;
    private int baseW = 512, baseH = 256;
    private final Map<String, int[]> packed = new HashMap<String, int[]>();      // image key -> x, y, w, h
    private int shelfX, shelfY = STRIP_Y + 16, shelfH;   // flowing water/lava tiles reach y 272

    ModBlocks(Context c, ModRuntime rt) {
        this.ctx = c;
        this.rt = rt;
    }

    boolean hasAtlasStrip() { return !extras.isEmpty(); }

    private final Map<String, Double> idsOut = new HashMap<String, Double>();
    Map<String, Double> ids() { return idsOut; }

    /** reads every block declaration; errors are reported to the mod */
    void prepare(Map<String, File> modDirs) {
        try {
            layout = new JSONObject(new String(readAsset("textures/blocks.layout.json"), "UTF-8"));
            JSONArray size = layout.getJSONArray("size");
            baseW = size.getInt(0);
            baseH = size.getInt(1);
        } catch (Throwable t) {
            Log.e(TAG, "no block layout", t);
            return;
        }
        Map<String, Integer> ids = loadIds();
        List<Double> out = new ArrayList<Double>();
        StringBuilder nm = new StringBuilder();
        for (Object[] d : rt.blockDecls()) {
            ModRuntime.Mod m = (ModRuntime.Mod) d[0];
            String name = (String) d[1];
            int line = (Integer) d[3];
            if (!(d[2] instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) d[2];
            try {
                File dir = modDirs.get(m.id);
                String key = m.id + ":" + name;
                int id = pickId(ids, key, p.get("id"));
                if (id < 0) throw new Exception("закончились свободные номера блоков (" + FIRST_ID + ".." + LAST_ID + ")");
                List<Double> one = new ArrayList<Double>();
                build(one, dir, id, p);
                out.addAll(one);
                String internal = (m.id + "_" + name).toLowerCase(Locale.US).replaceAll("[^a-z0-9_]", "_");
                if (nm.length() > 0) nm.append('\u001f');
                nm.append(internal);
                String display = text(p.get("name"), name);
                lang.put("tile." + internal + ".name", display);
                lang.put("item." + internal + ".name", display);
                idsOut.put(key, (double) id);
                idsOut.put(name.toLowerCase(Locale.US), (double) id);
                rt.customIds.putAll(idsOut);
                count++;
            } catch (Exception e) {
                rt.reportLater(m, "block " + name + ": " + e.getMessage(), line);
                Log.w(TAG, "block " + name, e);
            }
        }
        saveIds(ids);
        data = new double[out.size()];
        for (int i = 0; i < data.length; i++) data[i] = out.get(i);
        names = nm.toString();
        Log.i(TAG, count + " mod block(s), " + extras.size() + " new texture(s)");
    }

    // ------------------------------------------------------------------ one block
    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    private void build(List<Double> o, File dir, int id, Map<String, Object> p) throws Exception {
        boolean model = p.get("model") != null;
        boolean transparent = p.get("transparent") instanceof Boolean ? (Boolean) p.get("transparent") : model;
        double[] box = {0, 0, 0, 1, 1, 1};
        if (p.get("box") instanceof List) {
            List<?> b = (List<?>) p.get("box");
            if (b.size() != 6) throw new Exception("box: нужно 6 чисел [x0, y0, z0, x1, y1, z1]");
            for (int i = 0; i < 6; i++) box[i] = clamp(num(b.get(i)) / 16.0, 0, 1);
        }
        boolean full = box[0] == 0 && box[1] == 0 && box[2] == 0 && box[3] == 1 && box[4] == 1 && box[5] == 1;
        Object colDecl = p.get("collision");
        boolean solid = !model && !transparent && full && (colDecl == null || "full".equals(colDecl));
        o.add((double) id);
        o.add(model ? 1.0 : 0.0);
        o.add(solid ? 1.0 : 0.0);
        o.add(clamp(num(p.get("light"), 0), 0, 15));
        o.add(solid ? 255.0 : 0.0);
        o.add(num(p.get("hardness"), 1.5));
        o.add(num(p.get("resistance"), 0));
        o.add((double) soundBlock(p.get("sound")));
        o.add(transparent ? 3.0 : 0.0);                       // render layer: opaque / alpha
        for (double v : box) o.add(v);
        float[][] faces = new float[6][];
        if (!model) {
            Object all = p.get("texture");
            Map<?, ?> per = p.get("textures") instanceof Map ? (Map<?, ?>) p.get("textures") : null;
            if (all == null && per == null) throw new Exception("нужен texture: \"...\" или textures: {...}");
            for (int f = 0; f < 6; f++) {
                Object t = all;
                if (per != null) {
                    Object v = per.get(FACES[f]);
                    if (v == null && f >= 2) v = per.get("side");
                    if (v == null && f == 1) v = per.get("top");
                    if (v == null && f == 0) v = per.get("bottom");
                    if (v == null) v = per.get("all");
                    if (v != null) t = v;
                }
                if (t == null) throw new Exception("нет текстуры для стороны " + FACES[f]);
                faces[f] = rect(dir, String.valueOf(t));
            }
        }
        List<float[]> cubes = new ArrayList<float[]>();
        if (model) {
            String path = String.valueOf(p.get("model"));
            byte[] src = find(dir, path);
            if (src == null) throw new Exception("нет файла модели " + path);
            JSONObject j = new JSONObject(new String(src, "UTF-8"));
            if (j.has("elements")) bbmodel(j, dir, p, cubes);
            else geo(j, dir, p, cubes);
            if (cubes.isEmpty()) throw new Exception("в модели нет кубов");
            float[] first = null;                                // particles: first textured face
            for (float[] c : cubes) for (int f = 0; f < 6 && first == null; f++) if (c[6 + f * 4] >= 0) first = new float[]{c[6 + f * 4], c[7 + f * 4], c[8 + f * 4], c[9 + f * 4]};
            for (int f = 0; f < 6; f++) faces[f] = first;
        }
        for (int f = 0; f < 6; f++) for (int k = 0; k < 4; k++) o.add(faces[f] == null ? -1.0 : faces[f][k]);
        o.add((double) cubes.size());
        for (float[] c : cubes) for (float v : c) o.add((double) v);
        // collision: list of boxes (block units 0..1) + selection box (their union)
        List<double[]> col = new ArrayList<double[]>();
        Object cv = p.get("collision");
        if ("none".equals(cv)) { /* walk through */ }
        else if ("full".equals(cv)) col.add(new double[]{0, 0, 0, 1, 1, 1});
        else if (cv instanceof List) {
            for (Object bo : (List<?>) cv) {
                if (!(bo instanceof List) || ((List<?>) bo).size() != 6) throw new Exception("collision: нужен список боксов [[x0, y0, z0, x1, y1, z1], ...] в пикселях 0..16");
                List<?> b = (List<?>) bo;
                double[] q = new double[6];
                for (int i = 0; i < 6; i++) q[i] = clamp(num(b.get(i)) / 16.0, 0, 1);
                col.add(order(q));
            }
        } else if (model) {                                       // default for models: every cube (rotated -> its bounds)
            for (float[] c : cubes) col.add(cubeBounds(c));
        } else col.add(box.clone());
        double[] sel = null;
        for (double[] q : col) sel = union(sel, q);
        if (sel == null) for (float[] c : cubes) sel = union(sel, cubeBounds(c));
        if (sel == null) sel = box.clone();
        o.add((double) col.size());
        for (double[] q : col) for (double v : q) o.add(v);
        for (double v : sel) o.add(v);
    }

    private static double[] order(double[] q) {
        return new double[]{Math.min(q[0], q[3]), Math.min(q[1], q[4]), Math.min(q[2], q[5]), Math.max(q[0], q[3]), Math.max(q[1], q[4]), Math.max(q[2], q[5])};
    }

    private static double[] union(double[] a, double[] b) {
        if (a == null) return b.clone();
        return new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5])};
    }

    /** bounds of a (rotated) model cube, clamped to the block */
    private static double[] cubeBounds(float[] c) {
        double rx = Math.toRadians(c[30]), ry = Math.toRadians(c[31]), rz = Math.toRadians(c[32]);
        double cx = Math.cos(rx), sx = Math.sin(rx), cy = Math.cos(ry), sy = Math.sin(ry), cz = Math.cos(rz), sz = Math.sin(rz);
        double[] m = {cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
                      sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
                      -sy, cy * sx, cy * cx};
        double[] r = null;
        for (int k = 0; k < 8; k++) {
            double x = (k & 1) != 0 ? c[3] : c[0], y = (k & 2) != 0 ? c[4] : c[1], z = (k & 4) != 0 ? c[5] : c[2];
            double a = x - c[33], b = y - c[34], d = z - c[35];
            double X = m[0] * a + m[1] * b + m[2] * d + c[33], Y = m[3] * a + m[4] * b + m[5] * d + c[34], Z = m[6] * a + m[7] * b + m[8] * d + c[35];
            r = union(r, new double[]{X, Y, Z, X, Y, Z});
        }
        for (int i = 0; i < 6; i++) r[i] = clamp(r[i], 0, 1);
        return r;
    }

    /** UV rect (atlas 0..1) of a texture reference, cropped to nothing: whole tile / image */
    private float[] rect(File dir, String ref) throws Exception {
        ref = ref.trim();
        if (!ref.contains("/") && !ref.endsWith(".png")) {
            String name = ref;
            int idx = 0, c = ref.lastIndexOf(':');
            if (c > 0) { name = ref.substring(0, c); idx = Integer.parseInt(ref.substring(c + 1)); }
            if (name.startsWith("minecraft:")) name = name.substring(10);
            JSONArray es = layout.getJSONArray("entries");
            for (int i = 0; i < es.length(); i++) {
                JSONObject e = es.getJSONObject(i);
                if (!e.getString("name").equals(name)) continue;
                JSONArray uvs = e.getJSONArray("uvs");
                JSONArray u = uvs.getJSONArray(Math.min(idx, uvs.length() - 1));
                double sx = (double) baseW / u.getInt(4), sy = (double) baseH / u.getInt(5);
                double x0 = u.getInt(0) * sx, y0 = u.getInt(1) * sy, x1 = u.getInt(2) * sx, y1 = u.getInt(3) * sy;
                if (name.startsWith("flowing_")) { x1 = x0 + (x1 - x0) / 2; y1 = y0 + (y1 - y0) / 2; }
                return new float[]{(float) (x0 / ATLAS_W), (float) (y0 / ATLAS_H), (float) (x1 / ATLAS_W), (float) (y1 / ATLAS_H)};
            }
            throw new Exception("нет текстуры \"" + ref + "\" в атласе игры");
        }
        int[] r = pack(dir, ref, null);
        return new float[]{(float) r[0] / ATLAS_W, (float) r[1] / ATLAS_H, (float) (r[0] + r[2]) / ATLAS_W, (float) (r[1] + r[3]) / ATLAS_H};
    }

    /** places an image into the atlas strip once; returns x, y, w, h (base pixels) */
    private int[] pack(File dir, String ref, byte[] png) throws Exception {
        String key = (dir == null ? "" : dir.getPath()) + "|" + ref;
        int[] have = packed.get(key);
        if (have != null) return have;
        if (png == null) png = find(dir, ref);
        if (png == null) throw new Exception("нет картинки " + ref);
        BitmapFactory.Options op = new BitmapFactory.Options();
        op.inPremultiplied = false;
        Bitmap bm = BitmapFactory.decodeByteArray(png, 0, png.length, op);
        if (bm == null) throw new Exception("не удалось прочитать картинку " + ref);
        int w = bm.getWidth(), h = bm.getHeight();
        if (w > 256 || h > 256) {
            double k = 256.0 / Math.max(w, h);
            w = Math.max(1, (int) (w * k));
            h = Math.max(1, (int) (h * k));
            bm = Bitmap.createScaledBitmap(bm, w, h, false);
        }
        if (shelfX + w > ATLAS_W) { shelfX = 0; shelfY += shelfH; shelfH = 0; }
        if (shelfY + h > ATLAS_H) throw new Exception("не хватает места в атласе для " + ref);
        Extra e = new Extra();
        e.bm = bm; e.x = shelfX; e.y = shelfY; e.w = w; e.h = h;
        extras.add(e);
        shelfX += w;
        shelfH = Math.max(shelfH, h);
        int[] r = {e.x, e.y, w, h};
        packed.put(key, r);
        return r;
    }

    // ------------------------------------------------------------------ models
    /** cube = x0 y0 z0 x1 y1 z1 (0..1) + 6 faces (u0 v0 u1 v1 atlas, u0 = -1 no face) */
    private void bbmodel(JSONObject j, File dir, Map<String, Object> p, List<float[]> out) throws Exception {
        JSONObject meta = j.optJSONObject("meta");
        String format = meta == null ? "" : meta.optString("model_format", "");
        boolean centered = !format.startsWith("java");
        JSONObject res = j.optJSONObject("resolution");
        double rw = res == null ? 16 : res.optDouble("width", 16), rh = res == null ? 16 : res.optDouble("height", 16);
        JSONArray texs = j.optJSONArray("textures");
        List<int[]> rects = new ArrayList<int[]>();
        List<double[]> uvSize = new ArrayList<double[]>();
        for (int i = 0; texs != null && i < texs.length(); i++) {
            JSONObject t = texs.getJSONObject(i);
            String src = t.optString("source", "");
            int[] r;
            if (p.get("texture") != null && i == 0) r = pack(dir, String.valueOf(p.get("texture")), null);
            else if (src.startsWith("data:")) {
                byte[] png = Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT);
                r = pack(dir, "bbmodel#" + j.hashCode() + "#" + i, png);
            } else r = pack(dir, "textures/" + t.optString("name", "texture.png"), null);
            rects.add(r);
            uvSize.add(new double[]{t.optDouble("uv_width", rw), t.optDouble("uv_height", rh)});
        }
        if (rects.isEmpty() && p.get("texture") != null) {
            rects.add(pack(dir, String.valueOf(p.get("texture")), null));
            uvSize.add(new double[]{rw, rh});
        }
        if (rects.isEmpty()) throw new Exception("у модели нет текстуры (укажите texture: \"...\")");
        JSONArray els = j.getJSONArray("elements");
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            if (!e.optString("type", "cube").equals("cube")) continue;
            if (e.optBoolean("visibility", true) == false) continue;
            JSONArray fr = e.getJSONArray("from"), to = e.getJSONArray("to");
            JSONArray rot = e.optJSONArray("rotation"), org = e.optJSONArray("origin");
            double off = centered ? 8 : 0;
            float[] c = new float[CUBE];
            if (rot != null) {                                   // any angle, Blockbench order, about "origin"
                c[30] = (float) rot.optDouble(0); c[31] = (float) rot.optDouble(1); c[32] = (float) rot.optDouble(2);
                if (org != null) { c[33] = (float) ((org.optDouble(0) + off) / 16); c[34] = (float) (org.optDouble(1) / 16); c[35] = (float) ((org.optDouble(2) + off) / 16); }
            }
            c[0] = (float) ((fr.getDouble(0) + off) / 16); c[1] = (float) (fr.getDouble(1) / 16); c[2] = (float) ((fr.getDouble(2) + off) / 16);
            c[3] = (float) ((to.getDouble(0) + off) / 16); c[4] = (float) (to.getDouble(1) / 16); c[5] = (float) ((to.getDouble(2) + off) / 16);
            JSONObject faces = e.optJSONObject("faces");
            for (int f = 0; f < 6; f++) {
                JSONObject fc = faces == null ? null : faces.optJSONObject(FACES[f]);
                int ti = fc == null || fc.isNull("texture") ? -1 : fc.optInt("texture", 0);
                JSONArray uv = fc == null ? null : fc.optJSONArray("uv");
                if (fc == null || ti < 0 || uv == null || ti >= rects.size()) { c[6 + f * 4] = -1; continue; }
                double[] q = {uv.getDouble(0), uv.getDouble(1), uv.getDouble(2), uv.getDouble(3)};
                if (fc.optInt("rotation", 0) == 180) { double t = q[0]; q[0] = q[2]; q[2] = t; t = q[1]; q[1] = q[3]; q[3] = t; }
                put(c, f, q, rects.get(ti), uvSize.get(ti));
            }
            out.add(c);
        }
    }

    /** Bedrock geometry (.geo.json): box UV or per-face UV */
    private void geo(JSONObject j, File dir, Map<String, Object> p, List<float[]> out) throws Exception {
        JSONArray gs = j.optJSONArray("minecraft:geometry");
        if (gs == null || gs.length() == 0) throw new Exception("это не модель Blockbench (.bbmodel или .geo.json)");
        JSONObject g = gs.getJSONObject(0);
        JSONObject desc = g.optJSONObject("description");
        double tw = desc == null ? 16 : desc.optDouble("texture_width", 16), th = desc == null ? 16 : desc.optDouble("texture_height", 16);
        if (p.get("texture") == null) throw new Exception("для .geo.json нужен texture: \"...png\"");
        int[] rect = pack(dir, String.valueOf(p.get("texture")), null);
        double[] size = {tw, th};
        JSONArray bones = g.optJSONArray("bones");
        for (int b = 0; bones != null && b < bones.length(); b++) {
            JSONArray cs = bones.getJSONObject(b).optJSONArray("cubes");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject cu = cs.getJSONObject(i);
                JSONArray o = cu.getJSONArray("origin"), s = cu.getJSONArray("size");
                double w = s.getDouble(0), h = s.getDouble(1), d = s.getDouble(2);
                double x0 = -(o.getDouble(0) + w) + 8, y0 = o.getDouble(1), z0 = o.getDouble(2) + 8;
                float[] c = new float[CUBE];
                JSONArray rot = cu.optJSONArray("rotation"), pv = cu.optJSONArray("pivot");
                if (rot != null) {                               // geo x is mirrored: rotations about y and z flip
                    c[30] = (float) rot.optDouble(0); c[31] = (float) -rot.optDouble(1); c[32] = (float) -rot.optDouble(2);
                    if (pv != null) { c[33] = (float) ((-pv.optDouble(0) + 8) / 16); c[34] = (float) (pv.optDouble(1) / 16); c[35] = (float) ((pv.optDouble(2) + 8) / 16); }
                }
                c[0] = (float) (x0 / 16); c[1] = (float) (y0 / 16); c[2] = (float) (z0 / 16);
                c[3] = (float) ((x0 + w) / 16); c[4] = (float) ((y0 + h) / 16); c[5] = (float) ((z0 + d) / 16);
                Object uv = cu.opt("uv");
                if (uv instanceof JSONArray) {
                    double u = ((JSONArray) uv).getDouble(0), v = ((JSONArray) uv).getDouble(1);
                    double[][] q = boxUv(u, v, w, h, d);
                    for (int f = 0; f < 6; f++) put(c, f, q[f], rect, size);
                } else if (uv instanceof JSONObject) {
                    for (int f = 0; f < 6; f++) {
                        JSONObject fc = ((JSONObject) uv).optJSONObject(FACES[f]);
                        if (fc == null) { c[6 + f * 4] = -1; continue; }
                        JSONArray a = fc.getJSONArray("uv"), sz = fc.optJSONArray("uv_size");
                        double u0 = a.getDouble(0), v0 = a.getDouble(1);
                        double u1 = u0 + (sz == null ? 0 : sz.getDouble(0)), v1 = v0 + (sz == null ? 0 : sz.getDouble(1));
                        put(c, f, new double[]{u0, v0, u1, v1}, rect, size);
                    }
                } else for (int f = 0; f < 6; f++) c[6 + f * 4] = -1;
                out.add(c);
            }
        }
    }

    /** Blockbench box UV layout (down, up, north, south, west, east) */
    static double[][] boxUv(double u, double v, double w, double h, double d) {
        return new double[][]{
                {u + d + 2 * w, v, u + d + w, v + d},          // down
                {u + d + w, v + d, u + d, v},                  // up
                {u + d, v + d, u + d + w, v + d + h},          // north
                {u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h}, // south
                {u + d + w, v + d, u + 2 * d + w, v + d + h},  // west
                {u, v + d, u + d, v + d + h},                  // east
        };
    }

    private static void put(float[] c, int f, double[] q, int[] rect, double[] size) {
        for (int k = 0; k < 4; k++) {
            boolean isU = k % 2 == 0;
            double t = q[k] / (isU ? size[0] : size[1]);
            double px = isU ? rect[0] + t * rect[2] : rect[1] + t * rect[3];
            c[6 + f * 4 + k] = (float) (px / (isU ? ATLAS_W : ATLAS_H));
        }
    }

    // ------------------------------------------------------------------ helpers
    private byte[] find(File dir, String path) {
        path = path.replace('\\', '/');
        if (path.startsWith("/")) path = path.substring(1);
        try {
            if (dir != null) {
                File f = new File(dir, path);
                if (f.isFile() && f.getCanonicalPath().startsWith(dir.getCanonicalPath())) return PackManager.readFile(f);
            }
            File o = NovaActivity.overrideFile(path);
            if (o != null) return PackManager.readFile(o);
        } catch (Exception ignored) { }
        return readAsset(path);
    }

    private byte[] readAsset(String path) {
        try {
            java.io.InputStream in = ctx.getAssets().open(path);
            try { return PackManager.readAll(in); } finally { in.close(); }
        } catch (Exception e) {
            return null;
        }
    }

    private static int soundBlock(Object s) {
        String n = s == null ? "stone" : String.valueOf(s).toLowerCase(Locale.US);
        switch (n) {
            case "wood": return 5;
            case "dirt": case "grass": return 3;
            case "sand": return 12;
            case "gravel": return 13;
            case "glass": return 20;
            case "wool": case "cloth": return 35;
            case "metal": case "iron": return 42;
            case "leaves": return 18;
            default: return 1;
        }
    }

    private static double num(Object o) { return o instanceof Double ? (Double) o : 0; }
    private static double num(Object o, double def) { return o instanceof Double ? (Double) o : def; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String text(Object o, String def) {
        if (o instanceof String) return (String) o;
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            Object v = m.get(Locale.getDefault().getLanguage());
            if (v == null) v = m.get("en");
            if (v == null && !m.isEmpty()) v = m.values().iterator().next();
            if (v != null) return String.valueOf(v);
        }
        return def;
    }

    // stable ids: files/nova/blockids.json {"mod:name": id}
    private File idFile() { return new File(PackManager.root(ctx), "blockids.json"); }

    private Map<String, Integer> loadIds() {
        Map<String, Integer> m = new HashMap<String, Integer>();
        try {
            JSONObject j = new JSONObject(PackManager.readText(idFile()));
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) { String k = it.next(); m.put(k, j.getInt(k)); }
        } catch (Exception ignored) { }
        return m;
    }

    private void saveIds(Map<String, Integer> m) {
        try { PackManager.writeText(idFile(), new JSONObject(m).toString(2)); } catch (Exception ignored) { }
    }

    private final java.util.Set<Integer> usedNow = new java.util.HashSet<Integer>();

    private int pickId(Map<String, Integer> ids, String key, Object want) {
        if (want instanceof Double) {
            int w = (int) (double) (Double) want;
            if (w >= 1 && w <= 255 && !usedNow.contains(w)) { usedNow.add(w); ids.put(key, w); return w; }
        }
        Integer have = ids.get(key);
        if (have != null && !usedNow.contains(have)) { usedNow.add(have); return have; }
        for (int i = FIRST_ID; i <= LAST_ID; i++) {
            if (usedNow.contains(i) || ids.containsValue(i)) continue;
            usedNow.add(i);
            ids.put(key, i);
            return i;
        }
        return -1;
    }

    /** stock terrain.meta with the atlas height doubled (pixel coordinates stay) */
    byte[] terrainMeta(byte[] stock) {
        try {
            JSONArray es = new JSONArray(new String(stock, "UTF-8"));
            for (int i = 0; i < es.length(); i++) {
                JSONArray uvs = es.getJSONObject(i).getJSONArray("uvs");
                for (int k = 0; k < uvs.length(); k++) {
                    JSONArray u = uvs.getJSONArray(k);
                    u.put(5, u.getInt(5) * ATLAS_H / baseH);
                }
            }
            return es.toString().getBytes("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "terrain.meta", e);
            return null;
        }
    }

    /** "key=value" lines appended to every .lang file the game reads */
    byte[] langLines() {
        StringBuilder b = new StringBuilder("\n");
        for (Map.Entry<String, String> e : lang.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append("\t#\n");
        try { return b.toString().getBytes("UTF-8"); } catch (Exception e) { return new byte[0]; }
    }
}
