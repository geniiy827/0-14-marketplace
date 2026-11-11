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
    /** item numbers the game 0.14.3 leaves free (every registerItem of Item::initItems, items.json; 498 = camera): 79 */
    static final int[] POOL = pool();
    private static int[] pool() {
        List<Integer> l = new ArrayList<Integer>();
        for (int i = 470; i <= 497; i++) l.add(i);
        l.add(499);
        for (int i = 500; i <= 511; i++) l.add(i);
        for (int i = 467; i <= 469; i++) l.add(i);
        for (int i = 439; i <= 456; i++) l.add(i);
        for (int i = 432; i <= 437; i++) l.add(i);
        for (int i = 416; i <= 426; i++) l.add(i);          // horse armor / lead / name tag / mutton: not in 0.14
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
    /** items by data: id, aux, u0 v0 u1 v1 per variant; their names (\u001f) */
    final List<Double> varData = new ArrayList<Double>();
    final StringBuilder varNames = new StringBuilder();
    final Map<String, List<Integer>> variants = new HashMap<String, List<Integer>>();
    /** armor textures: layer 1 / layer 2 per armor material, "nova/mod/<id>/<file>" */
    final List<String> armorTex = new ArrayList<String>();
    private final Map<String, Integer> armorIdx = new HashMap<String, Integer>();
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

    /** The game has 512 item numbers and about 80 are free. Plain items (a picture and a name: no tool, armor, durability,
     *  action button, own stack size) do not need a number each: all plain items of a mod share one number and differ by
     *  data, like dyes - so a mod can have thousands. Scripts do not notice: the item's name is number + 65536 * data,
     *  and player.item(), events, recipes, drops, tabs speak the same. pack: false keeps an item on its own number. */
    private List<Object[]> packPlain(List<Object[]> decls) {
        List<Object[]> out = new ArrayList<Object[]>();
        Map<String, Object[]> carrier = new java.util.LinkedHashMap<String, Object[]>();
        for (Object[] d : decls) {
            if (!(d[2] instanceof Map)) { out.add(d); continue; }
            Map<?, ?> p = (Map<?, ?>) d[2];
            boolean plain = p.get("variants") == null && p.get("tool") == null && p.get("armor") == null && p.get("type") == null
                    && p.get("durability") == null && p.get("action") == null && p.get("hand") == null && p.get("stack") == null
                    && p.get("frames") == null && p.get("size") == null && p.get("tabIcon") == null && p.get("category") == null
                    && !Boolean.FALSE.equals(p.get("pack")) && p.get("texture") != null;
            if (!plain) { out.add(d); continue; }
            ModRuntime.Mod m = (ModRuntime.Mod) d[0];
            String tab = p.get("tab") == null ? "" : String.valueOf(p.get("tab"));
            String ck = m.id + "\u0000" + tab;
            Object[] c = carrier.get(ck);
            if (c == null || ((List<?>) ((Map<?, ?>) c[2]).get("variants")).size() >= MAX_VARIANTS) {
                Map<String, Object> cp = new java.util.LinkedHashMap<String, Object>();
                cp.put("variants", new ArrayList<Object>());
                if (p.get("tab") != null) cp.put("tab", p.get("tab"));
                int n = 0;
                for (Object[] o : out) if (o[0] == m && String.valueOf(o[1]).startsWith("items_")) n++;
                c = new Object[]{m, "items_" + n, cp, d[3]};
                carrier.put(ck, c);
                out.add(c);                                   // where the first plain item stood (order in the tab)
            }
            Map<String, Object> v = new java.util.LinkedHashMap<String, Object>();
            v.put("name", d[1]);
            v.put("title", p.get("name") == null ? d[1] : p.get("name"));
            v.put("texture", p.get("texture"));
            @SuppressWarnings("unchecked") List<Object> vs = (List<Object>) ((Map<?, ?>) c[2]).get("variants");
            vs.add(v);
        }
        return out;
    }
    static final int MAX_VARIANTS = 1024;

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
        for (Object[] d : packPlain(rt.decls("item"))) {
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
                for (int i : POOL) if (id < 0 && !used.contains(i) && !saved.containsValue(i)) id = i;
                if (id < 0) for (int i : POOL) if (id < 0 && !used.contains(i)) id = i;     // numbers of mods that are off
                if (id < 0) throw new Exception("закончились свободные номера предметов (" + POOL.length + ")");
                used.add(id);
                saved.put(key, id);
                File dir = dirs.get(m.id);
                Object tex = p.get("texture");
                List<?> vars = p.get("variants") instanceof List ? (List<?>) p.get("variants") : null;
                if (tex == null && vars != null && !vars.isEmpty() && vars.get(0) instanceof Map) tex = ((Map<?, ?>) vars.get(0)).get("texture");
                if (tex == null) throw new Exception("нужен texture: \"textures/....png\" или имя предмета игры");
                boolean pixel = "pixel".equals(p.get("size"));
                float[] uv = new float[4];
                double handScale = icon(dir, String.valueOf(tex), pixel, uv);
                // real tools / armor of the game: tool: "pickaxe" | "axe" | "shovel" | "sword" | "hoe", tier: {...}
                //                               armor: "helmet" | "chestplate" | "leggings" | "boots", defense, ...
                String toolKind = p.get("tool") == null ? "" : String.valueOf(p.get("tool"));
                String armorKind = p.get("armor") == null ? "" : String.valueOf(p.get("armor"));
                int kind = 0;
                switch (toolKind) {
                    case "pickaxe": kind = 1; break; case "axe": kind = 2; break; case "shovel": kind = 3; break;
                    case "sword": kind = 4; break; case "hoe": kind = 5; break; case "shears": kind = 10; break;
                    case "": break;
                    default: throw new Exception("tool: \"pickaxe\", \"axe\", \"shovel\", \"sword\" или \"hoe\"");
                }
                switch (armorKind) {
                    case "helmet": kind = 6; break; case "chestplate": kind = 7; break;
                    case "leggings": kind = 8; break; case "boots": kind = 9; break;
                    case "": break;
                    default: throw new Exception("armor: \"helmet\", \"chestplate\", \"leggings\" или \"boots\"");
                }
                String itype = p.get("type") == null ? "" : String.valueOf(p.get("type"));
                if (itype.equals("bow")) kind = 11; else if (itype.equals("bucket")) kind = 12; else if (itype.equals("door")) kind = 13;
                else if (!itype.isEmpty()) throw new Exception("type: \"bow\", \"bucket\" или \"door\"");
                double doorBlock = 0;
                if (kind == 13) {
                    Double db = rt.customIds.get(m.id + ":" + p.get("door"));
                    if (db == null) throw new Exception("door: блок-дверь этого мода (block .. { type: \"door\" })");
                    doorBlock = db;
                }
                if (p.get("frames") instanceof List) {         // bow: 3 pulling pictures; bucket: empty, water, lava, milk
                    List<?> fr = (List<?>) p.get("frames");
                    for (int k = 0; k < fr.size() && k < 4; k++) {
                        float[] fuv = new float[4];
                        icon(dir, String.valueOf(fr.get(k)), "pixel".equals(p.get("size")), fuv);
                        varData.add((double) id); varData.add(1000.0 + k);
                        for (float f : fuv) varData.add((double) f);
                        if (varNames.length() > 0) varNames.append('\u001f');
                        String title = text(p.get("name"), name);
                        if (kind == 12 && k > 0) {             // bucket: the name by contents
                            boolean ru = "ru".equals(Locale.getDefault().getLanguage());
                            String[] add = ru ? new String[]{"", " с водой", " с лавой", " с молоком"} : new String[]{"", " of Water", " of Lava", " of Milk"};
                            title += add[k];
                        }
                        varNames.append(title.replace('\u001f', ' '));
                    }
                }
                Map<?, ?> tier = p.get("tier") instanceof Map ? (Map<?, ?>) p.get("tier") : new HashMap<String, Object>();
                if (kind == 11) {                              // a bow's built-in enchantments
                    Map<String, Object> bt = new HashMap<String, Object>();
                    bt.put("level", num(p.get("power"), 0)); bt.put("damage", Boolean.TRUE.equals(p.get("flame")) ? 1.0 : 0.0);
                    bt.put("enchant", num(p.get("infinity"), 0)); tier = bt;
                }
                double tLevel = kind == 13 ? doorBlock : num(tier.get("level"), 1), tUses = num(tier.get("uses"), kind >= 10 && p.get("durability") instanceof Double ? (Double) p.get("durability") : 250), tSpeed = num(tier.get("speed"), 6),
                       tDamage = num(tier.get("damage"), 2), tEnch = num(tier.get("enchant"), 10);
                double aDef = 0, aMul = 0, aIdx = -1;
                if (kind >= 6 && kind <= 9) {
                    aDef = num(p.get("defense"), 2);
                    aMul = num(p.get("durability"), 15);
                    tEnch = num(p.get("enchant"), 10);
                    Object at = p.get("armorTexture");
                    if (at == null) throw new Exception("для брони нужен armorTexture: \"textures/armor/имя\" (имя_layer_1.png и имя_layer_2.png)");
                    String base = String.valueOf(at).replaceAll("(_layer_[12])?\\.png$", "");
                    String k = m.id + "|" + base;
                    Integer ai = armorIdx.get(k);
                    if (ai == null) {
                        for (String layer : new String[]{"_layer_1.png", "_layer_2.png"})
                            if (find(dir, base + layer) == null) throw new Exception("нет картинки " + base + layer);
                        ai = armorIdx.size();
                        if (ai >= 16) throw new Exception("слишком много видов брони (16)");
                        armorIdx.put(k, ai);
                        armorTex.add("nova/mod/" + m.id + "/" + base + "_layer_1.png");
                        armorTex.add("nova/mod/" + m.id + "/" + base + "_layer_2.png");
                    }
                    aIdx = ai;
                }
                boolean tool = "tool".equals(p.get("hand")) || "weapon".equals(p.get("hand")) || (kind >= 1 && kind <= 5) || kind == 10 || kind == 11;
                int stack = (int) clamp(num(p.get("stack"), tool ? 1 : 64), 1, 64);
                int dur = (int) clamp(num(p.get("durability"), 0), 0, 32000);
                Object cat = p.get("category");
                int category = "tools".equals(cat) || (cat == null && (tool || (kind >= 6 && kind <= 9))) ? 3 : "blocks".equals(cat) ? 1 : "decor".equals(cat) ? 2 : 4;
                out.add((double) id);
                for (float f : uv) out.add((double) f);
                out.add(handScale);
                out.add(tool ? 1.0 : 0.0);
                out.add((double) stack);
                out.add((double) dur);
                out.add((double) category);
                out.add((double) kind); out.add(tLevel); out.add(tUses); out.add(tSpeed); out.add(tDamage); out.add(tEnch);
                out.add(aDef); out.add(aIdx); out.add(aMul);
                String internal = (m.id + "_" + name).toLowerCase(Locale.US).replaceAll("[^a-z0-9_]", "_");
                if (nm.length() > 0) { nm.append('\u001f'); ac.append('\u001f'); }
                nm.append(internal);
                Object act = p.get("action");
                ac.append(act == null ? "" : String.valueOf(act).replace('\u001f', ' '));
                lang.put("item." + internal + ".name", text(p.get("name"), name));
                ids.put(key, (double) id);
                ids.put(name.toLowerCase(Locale.US), (double) id);
                if (vars != null) {                          // one number, many items by data (like dyes): name = id + 65536 * data
                    List<Integer> auxes = new ArrayList<Integer>();
                    for (int v = 0; v < vars.size() && v < MAX_VARIANTS; v++) {
                        if (!(vars.get(v) instanceof Map)) throw new Exception("variants: список {name: .., title: .., texture: ..}");
                        Map<?, ?> vm = (Map<?, ?>) vars.get(v);
                        String vn = String.valueOf(vm.get("name"));
                        if (vm.get("name") == null || vm.get("texture") == null) throw new Exception("variants: нужны name и texture");
                        float[] vuv = new float[4];
                        icon(dir, String.valueOf(vm.get("texture")), pixel, vuv);
                        varData.add((double) id); varData.add((double) v);
                        for (float f : vuv) varData.add((double) f);
                        if (varNames.length() > 0) varNames.append('\u001f');
                        varNames.append(text(vm.get("title"), vn).replace('\u001f', ' '));
                        double code = id + 65536.0 * v;
                        ids.put(m.id + ":" + vn, code);
                        ids.put(vn.toLowerCase(Locale.US), code);
                        auxes.add(v);
                    }
                    variants.put(key, auxes);
                    rt.carriers.add(id);
                }
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
