package com.nova.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
 * Mod items ("item name { ... }" in main.mcs):
 *   name: "Меч" | {ru: .., en: ..}
 *   texture: "textures/sword.png" (square, any size) | an item of the game ("apple", "sword_iron")
 *   size: "hd" (default: drawn at the usual item size, extra pixels = more detail)
 *       | "pixel" (one texture pixel = one normal item pixel: 32x32 is twice as big in the hand)
 *   hand: "item" (default) | "tool" (held like a sword / pickaxe)
 *   stack: 1..64   durability: n   category: "tools" | "items" (default by hand)
 *   action: "Выстрелить"   own action button while the item is held -> event action (e.item)
 *
 * Textures go to free rows of the item atlas (512x512, y 176..511): the game's own items.meta is not touched,
 * icons are set by UV (Item::setIcon(TextureUVCoordinateSet)).
 * Native data per item: id, u0 v0 u1 v1, handScale, tool, stack, durability, category.
 */
final class ModItems {
    static final String TAG = "NOVA.items";
    static final int FIRST_ID = 470, LAST_ID = 499;
    static final int ATLAS = 512, STRIP_Y = 176;

    final List<ModBlocks.Extra> extras = new ArrayList<ModBlocks.Extra>();
    final Map<String, String> lang = new LinkedHashMap<String, String>();
    final Map<String, Double> ids = new HashMap<String, Double>();
    double[] data = new double[0];
    String names = "", actions = "";
    int count;

    private final Context ctx;
    private final ModRuntime rt;
    private JSONObject layout;
    private int shelfX, shelfY = STRIP_Y, shelfH;
    private final Map<String, int[]> packed = new HashMap<String, int[]>();

    ModItems(Context c, ModRuntime rt) { this.ctx = c; this.rt = rt; }

    boolean hasAtlasStrip() { return !extras.isEmpty(); }

    void prepare(Map<String, File> dirs) {
        try {
            layout = new JSONObject(new String(readAsset("textures/items.layout.json"), "UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "no item layout", t);
        }
        Map<String, Integer> saved = loadIds();
        java.util.Set<Integer> used = new java.util.HashSet<Integer>();
        List<Double> out = new ArrayList<Double>();
        StringBuilder nm = new StringBuilder(), ac = new StringBuilder();
        for (Object[] d : rt.decls("item")) {
            ModRuntime.Mod m = (ModRuntime.Mod) d[0];
            String name = (String) d[1];
            int line = (Integer) d[3];
            if (!(d[2] instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) d[2];
            try {
                String key = m.id + ":" + name;
                int id = -1;
                Integer have = saved.get(key);
                if (have != null && !used.contains(have)) id = have;
                for (int i = FIRST_ID; id < 0 && i <= LAST_ID; i++) if (!used.contains(i) && !saved.containsValue(i)) id = i;
                if (id < 0) throw new Exception("закончились свободные номера предметов (" + FIRST_ID + ".." + LAST_ID + ")");
                used.add(id);
                saved.put(key, id);
                File dir = dirs.get(m.id);
                Object tex = p.get("texture");
                if (tex == null) throw new Exception("нужен texture: \"textures/....png\" или имя предмета игры");
                boolean pixel = "pixel".equals(p.get("size"));
                float[] uv = new float[4];
                double handScale = icon(dir, String.valueOf(tex), pixel, uv);
                boolean tool = "tool".equals(p.get("hand")) || "weapon".equals(p.get("hand"));
                int stack = (int) clamp(num(p.get("stack"), tool ? 1 : 64), 1, 64);
                int dur = (int) clamp(num(p.get("durability"), 0), 0, 32000);
                Object cat = p.get("category");
                int category = "tools".equals(cat) || (cat == null && tool) ? 3 : "blocks".equals(cat) ? 1 : "decor".equals(cat) ? 2 : 4;
                out.add((double) id);
                for (float f : uv) out.add((double) f);
                out.add(handScale);
                out.add(tool ? 1.0 : 0.0);
                out.add((double) stack);
                out.add((double) dur);
                out.add((double) category);
                String internal = (m.id + "_" + name).toLowerCase(Locale.US).replaceAll("[^a-z0-9_]", "_");
                if (nm.length() > 0) { nm.append('\u001f'); ac.append('\u001f'); }
                nm.append(internal);
                Object act = p.get("action");
                ac.append(act == null ? "" : String.valueOf(act).replace('\u001f', ' '));
                lang.put("item." + internal + ".name", text(p.get("name"), name));
                ids.put(key, (double) id);
                ids.put(name.toLowerCase(Locale.US), (double) id);
                count++;
            } catch (Exception e) {
                rt.reportLater(m, "item " + name + ": " + e.getMessage(), line);
                Log.w(TAG, "item " + name, e);
            }
        }
        saveIds(saved);
        rt.customIds.putAll(ids);
        data = new double[out.size()];
        for (int i = 0; i < data.length; i++) data[i] = out.get(i);
        names = nm.toString();
        actions = ac.toString();
        Log.i(TAG, count + " mod item(s), " + extras.size() + " texture(s)");
    }

    /** fills uv (atlas 0..1); returns the hand scale */
    private double icon(File dir, String ref, boolean pixel, float[] uv) throws Exception {
        ref = ref.trim();
        if (!ref.contains("/") && !ref.endsWith(".png")) {                  // an item texture of the game
            String name = ref, idx = "0";
            int c = ref.lastIndexOf(':');
            if (c > 0) { name = ref.substring(0, c); idx = ref.substring(c + 1); }
            if (layout != null) {
                JSONArray es = layout.getJSONArray("entries");
                for (int i = 0; i < es.length(); i++) {
                    JSONObject e = es.getJSONObject(i);
                    if (!e.getString("name").equals(name)) continue;
                    JSONArray uvs = e.getJSONArray("uvs");
                    JSONArray u = uvs.getJSONArray(Math.min(Integer.parseInt(idx), uvs.length() - 1));
                    double sx = (double) ATLAS / u.getInt(4), sy = (double) ATLAS / u.getInt(5);
                    uv[0] = (float) (u.getInt(0) * sx / ATLAS); uv[1] = (float) (u.getInt(1) * sy / ATLAS);
                    uv[2] = (float) (u.getInt(2) * sx / ATLAS); uv[3] = (float) (u.getInt(3) * sy / ATLAS);
                    return 1;
                }
            }
            throw new Exception("нет текстуры предмета \"" + ref + "\" в игре");
        }
        byte[] png = find(dir, ref);
        if (png == null) throw new Exception("нет картинки " + ref);
        BitmapFactory.Options op = new BitmapFactory.Options();
        op.inPremultiplied = false;
        Bitmap bm = BitmapFactory.decodeByteArray(png, 0, png.length, op);
        if (bm == null) throw new Exception("не удалось прочитать картинку " + ref);
        int w = bm.getWidth(), h = bm.getHeight();
        if (w != h) throw new Exception("картинка " + ref + " должна быть квадратной (сейчас " + w + "x" + h + ")");
        if (w > 128) { bm = Bitmap.createScaledBitmap(bm, 128, 128, false); w = h = 128; }
        // footprint in base atlas pixels: hd = one item tile (16), pixel = the real size
        int fp = pixel ? Math.max(16, w) : 16;
        String key = (dir == null ? "" : dir.getPath()) + "|" + ref + "|" + fp;
        int[] r = packed.get(key);
        if (r == null) {
            if (shelfX + fp > ATLAS) { shelfX = 0; shelfY += shelfH; shelfH = 0; }
            if (shelfY + fp > ATLAS) throw new Exception("не хватает места в атласе предметов для " + ref);
            ModBlocks.Extra e = new ModBlocks.Extra();
            e.bm = bm; e.x = shelfX; e.y = shelfY; e.w = fp; e.h = fp;
            extras.add(e);
            r = new int[]{shelfX, shelfY, fp};
            packed.put(key, r);
            shelfX += fp;
            shelfH = Math.max(shelfH, fp);
        }
        uv[0] = (float) r[0] / ATLAS; uv[1] = (float) r[1] / ATLAS;
        uv[2] = (float) (r[0] + r[2]) / ATLAS; uv[3] = (float) (r[1] + r[2]) / ATLAS;
        return pixel ? w / 16.0 : 1;
    }

    byte[] langLines() {
        StringBuilder b = new StringBuilder("\n");
        for (Map.Entry<String, String> e : lang.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try { return b.toString().getBytes("UTF-8"); } catch (Exception e) { return new byte[0]; }
    }

    // ---- helpers (same rules as ModBlocks)
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

    private File idFile() { return new File(PackManager.root(ctx), "itemids.json"); }

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
