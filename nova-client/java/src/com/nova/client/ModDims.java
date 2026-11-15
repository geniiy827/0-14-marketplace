package com.nova.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.nova.client.mcmod.ModRuntime;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dimensions of mods (native/dims.inc):
 *
 *   dimension wood {
 *       name: {ru: "Деревянный мир", en: "Wooden world"}
 *       portal: {frame: "planks", color: "#e8a040"}          // or texture: "textures/portal.png" (16 x 16*n strip)
 *       generator: {type: "islands", height: 62, size: 56, top: "grass", filler: "dirt", filler_depth: 3,
 *                   body: ["planks:0", "planks:1"], roots: "log", trees: 0.035, grass: 0.18, biome: "forest"}
 *       respawn: false
 *   }
 *   generator {type: "flat", layers: [{block: "bedrock"}, {block: "dirt", count: 3}, {block: "grass"}]}
 *
 * The portal is built like the nether's from the frame block and lit by fire. Numbers 2.. are kept in
 * files/nova/dimids.json ("mod:name" -> number) so a world keeps its dimensions. At most 4.
 */
final class ModDims {
    static final String TAG = "NOVA-dims";
    static final int MAX = 4;
    private static final Map<String, Bitmap> strips = new HashMap<String, Bitmap>();

    final List<Double> data = new ArrayList<Double>();
    final StringBuilder names = new StringBuilder();
    int count;
    private final Context ctx;
    private final ModRuntime rt;

    ModDims(Context c, ModRuntime rt) { ctx = c; this.rt = rt; }

    /** the portal strip of a dimension for the game's texture loader ("nova/dimportal/<n>.png") */
    static Bitmap strip(String path) {
        int i = path.indexOf("nova/dimportal/");
        if (i < 0) return null;
        synchronized (strips) { return strips.get(path.substring(i + 15)); }
    }

    void prepare(Map<String, File> dirs, ModBlocks mb) {
        Map<String, Integer> ids = loadIds();
        java.util.Set<Integer> used = new java.util.HashSet<Integer>();
        for (Object[] d : rt.decls("dimension")) {
            ModRuntime.Mod m = (ModRuntime.Mod) d[0];
            String name = (String) d[1];
            int line = (Integer) d[3];
            if (!(d[2] instanceof Map)) continue;
            Map<?, ?> p = (Map<?, ?>) d[2];
            try {
                if (count >= MAX) throw new Exception("не больше " + MAX + " измерений из модов");
                String key = m.id + ":" + name;
                Integer have = ids.get(key);
                int id = have != null && !used.contains(have) ? have : -1;
                for (int i = 2; id < 0 && i < 100; i++) if (!used.contains(i) && !ids.containsValue(i)) id = i;
                if (id < 0) throw new Exception("нет свободного номера измерения");
                used.add(id);
                ids.put(key, id);

                Map<?, ?> portal = p.get("portal") instanceof Map ? (Map<?, ?>) p.get("portal") : new HashMap<String, Object>();
                int[] frame = block(portal.get("frame") == null ? "obsidian" : portal.get("frame"), "portal.frame");
                Map<?, ?> g = p.get("generator") instanceof Map ? (Map<?, ?>) p.get("generator") : new HashMap<String, Object>();
                String type = g.get("type") == null ? "islands" : String.valueOf(g.get("type"));
                if (!type.equals("islands") && !type.equals("flat")) throw new Exception("generator.type: \"islands\" или \"flat\"");
                int[] top = block(g.get("top") == null ? "grass" : g.get("top"), "generator.top");
                int[] fill = block(g.get("filler") == null ? "dirt" : g.get("filler"), "generator.filler");
                int[] roots = g.get("roots") == null ? new int[]{0, -1} : block(g.get("roots"), "generator.roots");
                if (g.get("roots") != null && !String.valueOf(g.get("roots")).contains(":")) roots[1] = -1;   // any kind
                List<int[]> body = new ArrayList<int[]>();
                if (g.get("body") instanceof List) for (Object o : (List<?>) g.get("body")) body.add(block(o, "generator.body"));
                else if (g.get("body") != null) body.add(block(g.get("body"), "generator.body"));
                List<int[]> layers = new ArrayList<int[]>();
                if (g.get("layers") instanceof List) for (Object o : (List<?>) g.get("layers")) {
                    Object b = o instanceof Map ? ((Map<?, ?>) o).get("block") : o;
                    int c = o instanceof Map && ((Map<?, ?>) o).get("count") instanceof Double ? (int) (double) (Double) ((Map<?, ?>) o).get("count") : 1;
                    int[] bd = block(b, "generator.layers");
                    layers.add(new int[]{bd[0], bd[1], Math.max(1, Math.min(127, c))});
                }
                if (type.equals("flat") && layers.isEmpty()) throw new Exception("flat: нужен layers: [{block: ..., count: ...}]");

                String tex = "nova_portal_tex_" + id;
                Bitmap strip = portalStrip(dirs.get(m.id), portal);
                synchronized (strips) { strips.put(id + ".png", strip); }
                int w = strip.getWidth();
                Bitmap first = ModBlocks.crop(strip, 0, 0, w, w);
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                first.compress(Bitmap.CompressFormat.PNG, 100, o);
                mb.addAnimated(tex, o.toByteArray(), "nova/dimportal/" + id + ".png", 1);

                data.add((double) id); data.add((double) frame[0]); data.add(type.equals("flat") ? 1.0 : 0.0);
                data.add(clamp(num(g.get("height"), 62), 8, 110)); data.add(clamp(num(g.get("size"), 56), 8, 512));
                data.add(clamp(num(g.get("density"), 0.56), 0, 0.98));
                data.add((double) top[0]); data.add((double) top[1]); data.add((double) fill[0]); data.add((double) fill[1]);
                data.add(clamp(num(g.get("filler_depth"), 3), 0, 20));
                data.add((double) roots[0]); data.add((double) roots[1]);
                data.add(clamp(num(g.get("trees"), 0), 0, 1)); data.add(clamp(num(g.get("grass"), 0), 0, 1));
                data.add(Boolean.TRUE.equals(p.get("respawn")) ? 1.0 : 0.0);
                data.add((double) body.size()); data.add((double) layers.size());
                for (int[] b : body) { data.add((double) b[0]); data.add((double) b[1]); }
                for (int[] l : layers) { data.add((double) l[0]); data.add((double) l[1]); data.add((double) l[2]); }
                if (names.length() > 0) names.append('\u001e');
                String biome = g.get("biome") == null ? "forest" : String.valueOf(g.get("biome")).replaceAll("[^A-Za-z]", "");
                names.append(tex).append('\u001f').append(biome);
                count++;
            } catch (Exception e) {
                rt.reportLater(m, "dimension " + name + ": " + e.getMessage(), line);
                Log.w(TAG, "dimension " + name, e);
            }
        }
        saveIds(ids);
    }

    double[] numbers() {
        double[] a = new double[data.size()];
        for (int i = 0; i < a.length; i++) a[i] = data.get(i);
        return a;
    }

    /** "planks", "planks:2", "minecraft:log", a mod's block (packed: number + 65536 * data), a number */
    private int[] block(Object v, String what) throws Exception {
        int data = 0;
        Object q = v;
        if (v instanceof String) {
            String s = ((String) v).trim();
            int c = s.lastIndexOf(':');
            if (c > 0 && s.substring(c + 1).matches("[0-9]{1,2}")) { data = Integer.parseInt(s.substring(c + 1)); q = s.substring(0, c); }
        }
        double code = rt.blockId(q);
        if (code < 0) throw new Exception(what + ": неизвестный блок " + v);
        int id = (int) (code % 65536), d = (int) (code / 65536);
        if (id <= 0 || id > 255) throw new Exception(what + ": " + v + " - не блок");
        return new int[]{id, (d > 0 ? d : data) & 15};
    }

    /** the mod's strip, or the game's portal (images/portal.png) in the given colour */
    private Bitmap portalStrip(File dir, Map<?, ?> portal) throws Exception {
        BitmapFactory.Options op = new BitmapFactory.Options();
        op.inPremultiplied = false;
        if (portal.get("texture") != null && dir != null) {
            File f = new File(dir, String.valueOf(portal.get("texture")));
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath())) throw new Exception("portal.texture вне мода");
            byte[] b = PackManager.readFile(f);
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, op);
            if (bm == null || bm.getHeight() % bm.getWidth() != 0) throw new Exception("portal.texture: полоса квадратных кадров");
            return bm;
        }
        java.io.InputStream in = ctx.getAssets().open("images/portal.png");
        byte[] b;
        try { b = PackManager.readAll(in); } finally { in.close(); }
        Bitmap src = BitmapFactory.decodeByteArray(b, 0, b.length, op);
        int col = 0xe8a040;
        if (portal.get("color") != null) {
            String c = String.valueOf(portal.get("color")).replace("#", "");
            try { col = Integer.parseInt(c, 16) & 0xffffff; } catch (Exception e) { throw new Exception("portal.color: \"#rrggbb\""); }
        }
        float cr = ((col >> 16) & 255) / 255f, cg = ((col >> 8) & 255) / 255f, cb = (col & 255) / 255f;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24, r = (px[i] >> 16) & 255, gg = (px[i] >> 8) & 255, bb = px[i] & 255;
            float v = Math.max(r, Math.max(gg, bb)) / 255f * 1.15f;
            px[i] = (a << 24) | (Math.min(255, (int) (255 * v * cr)) << 16) | (Math.min(255, (int) (255 * v * cg)) << 8) | Math.min(255, (int) (255 * v * cb));
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPremultiplied(false);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static double num(Object o, double d) { return o instanceof Double ? (Double) o : d; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private File idFile() { return new File(PackManager.root(ctx), "dimids.json"); }
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
}
