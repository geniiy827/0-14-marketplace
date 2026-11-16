package com.nova.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Skin packs in the Bedrock format (octa3): an .mcpack whose manifest.json has a "skin_pack" module, with
 * skins.json, the skin pictures and texts/*.lang. They go straight to the Skins screen, not to the storage:
 * files/nova/skinpacks/&lt;id&gt;/ (id = the manifest uuid). The game gets them as text through nativeSkinPacks
 * (see native/skinpacks.inc); pictures are served from here as "nova_skins/&lt;id&gt;/&lt;file&gt;".
 */
final class SkinPacks {
    private static final String TAG = "NOVA";

    private SkinPacks() { }

    static File dir(Context c) {
        File d = new File(PackManager.root(c), "skinpacks");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** folder (inside the archive) of a skin pack's manifest, "" for the root; null when it is not a skin pack */
    static String findPrefix(ZipFile zip) {
        String best = null;
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName().replace('\\', '/');
            if (e.isDirectory() || !(n.equals("manifest.json") || n.endsWith("/manifest.json"))) continue;
            String prefix = n.substring(0, n.length() - "manifest.json".length());
            if (best != null && prefix.length() >= best.length()) continue;
            try {
                JSONObject m = json(new String(PackManager.readAll(zip.getInputStream(e)), "UTF-8"));
                JSONArray mods = m.optJSONArray("modules");
                boolean skin = false;
                for (int i = 0; mods != null && i < mods.length(); i++)
                    if ("skin_pack".equals(mods.optJSONObject(i) == null ? null : mods.optJSONObject(i).optString("type"))) skin = true;
                if (skin && zip.getEntry(prefix + "skins.json") != null) best = prefix;
            } catch (Exception ignored) { }
        }
        return best;
    }

    /** unpack a skin pack; returns a Pack for the "imported" toast */
    static PackManager.Pack install(Context c, ZipFile zip, String prefix) throws Exception {
        JSONObject man = json(new String(PackManager.readAll(zip.getInputStream(zip.getEntry(prefix + "manifest.json"))), "UTF-8"));
        JSONObject head = man.optJSONObject("header");
        String uuid = head == null ? "" : head.optString("uuid", "");
        String id = uuid.replaceAll("[^A-Za-z0-9-]", "");
        if (id.isEmpty()) id = "pack" + Long.toHexString(System.currentTimeMillis());
        File d = new File(dir(c), id);
        if (d.exists()) PackManager.deleteTree(d);
        new File(dir(c), id + ".deleted").delete();
        d.mkdirs();
        String canon = d.getCanonicalPath() + File.separator;
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName().replace('\\', '/');
            if (e.isDirectory() || !n.startsWith(prefix)) continue;
            String rel = n.substring(prefix.length());
            if (rel.isEmpty() || rel.contains("../")) continue;
            File t = new File(d, rel);
            if (!t.getCanonicalPath().startsWith(canon)) continue;     // zip-slip guard
            t.getParentFile().mkdirs();
            PackManager.copy(zip.getInputStream(e), t);
        }
        String text = describe(d);
        if (text == null) { PackManager.deleteTree(d); throw new PackManager.PackException("В скин-паке нет скинов"); }
        try { if (NovaActivity.nativeOk) NovaActivity.nativeSkinPacks(text); } catch (Throwable t) { Log.w(TAG, "skin pack to the game", t); }
        PackManager.Pack p = new PackManager.Pack();
        p.uuid = id;
        p.dir = d;
        p.name = packName(d, man);
        return p;
    }

    /** all packs, as text for nativeSkinPacks; deletes the ones the game marked deleted */
    static String describeAll(Context c) {
        StringBuilder sb = new StringBuilder();
        File[] list = dir(c).listFiles();
        if (list == null) return "";
        for (File f : list) {
            if (f.isFile() && f.getName().endsWith(".deleted")) {
                File pd = new File(f.getParentFile(), f.getName().substring(0, f.getName().length() - 8));
                if (pd.exists()) PackManager.deleteTree(pd);
                f.delete();
            }
        }
        list = dir(c).listFiles();
        if (list == null) return "";
        java.util.Arrays.sort(list);
        for (File f : list) {
            if (!f.isDirectory()) continue;
            String t = describe(f);
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    private static String packName(File d, JSONObject man) {
        try {
            JSONObject sk = json(PackManager.readText(new File(d, "skins.json")));
            String loc = sk.optString("localization_name", "");
            String n = lang(d).get("skinpack." + loc);
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) { }
        JSONObject head = man == null ? null : man.optJSONObject("header");
        return head == null ? d.getName() : head.optString("name", d.getName());
    }

    static String describe(File d) {
        try {
            JSONObject sk = json(PackManager.readText(new File(d, "skins.json")));
            JSONObject man = null;
            try { man = json(PackManager.readText(new File(d, "manifest.json"))); } catch (Exception ignored) { }
            String loc = sk.optString("localization_name", "");
            Map<String, String> L = lang(d);
            JSONArray skins = sk.optJSONArray("skins");
            if (skins == null || skins.length() == 0) return null;
            StringBuilder sb = new StringBuilder();
            sb.append("P\t").append(d.getName()).append('\t').append(clean(packName(d, man))).append('\n');
            int n = 0;
            for (int i = 0; i < skins.length(); i++) {
                JSONObject s = skins.optJSONObject(i);
                if (s == null) continue;
                String tex = s.optString("texture", "");
                if (tex.isEmpty() || !new File(d, tex).isFile()) continue;
                String key = s.optString("localization_name", tex.replace(".png", ""));
                String name = L.get("skin." + loc + "." + key);
                if (name == null || name.isEmpty()) name = key;
                String geo = s.optString("geometry", "").toLowerCase(java.util.Locale.ROOT).contains("slim")
                        ? "geometry.humanoid.customSlim" : "geometry.humanoid.custom";
                sb.append("S\t").append(clean(key)).append('\t').append(clean(name)).append('\t').append(geo)
                        .append('\t').append("nova_skins/").append(d.getName()).append('/').append(clean(tex)).append('\n');
                n++;
            }
            return n == 0 ? null : sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "skin pack " + d, e);
            return null;
        }
    }

    /** the picture of a pack skin: 64x64 (HD skins scaled down, old 64x32 ones laid out as 64x64) */
    static int[] image(Context c, String path) {
        int i = path.indexOf("nova_skins/");
        if (i < 0) return null;
        String rel = path.substring(i + "nova_skins/".length());
        if (rel.contains("..")) return null;
        File f = new File(dir(c), rel);
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] src = new int[w * h];
        b.getPixels(src, 0, w, 0, 0, w, h);
        b.recycle();
        int k = Math.max(1, w / 64);
        int sh = h / k;
        int[] out = new int[64 * 64 + 2];
        out[0] = 64; out[1] = 64;
        for (int y = 0; y < Math.min(64, sh); y++)
            for (int x = 0; x < 64; x++) out[2 + y * 64 + x] = src[(y * k) * w + x * k];
        if (sh == 32) {                                   // old layout: the left leg and arm as copies of the right ones
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    out[2 + (48 + y) * 64 + 16 + x] = out[2 + (16 + y) * 64 + x];
                    out[2 + (48 + y) * 64 + 32 + x] = out[2 + (16 + y) * 64 + 40 + x];
                }
        }
        return out;
    }

    private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    private static Map<String, String> lang(File d) {
        Map<String, String> m = new HashMap<String, String>();
        String ru = "ru".equals(java.util.Locale.getDefault().getLanguage()) ? "ru_RU.lang" : null;
        for (String n : new String[]{"en_US.lang", ru}) {         // the user's language over English
            if (n == null) continue;
            File f = new File(new File(d, "texts"), n);
            if (!f.isFile()) continue;
            try {
                for (String line : PackManager.readText(f).split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String v = line.substring(eq + 1);
                    int c = v.indexOf("\t#");
                    if (c >= 0) v = v.substring(0, c);
                    m.put(line.substring(0, eq).trim().replace("﻿", ""), v.trim());
                }
            } catch (Exception ignored) { }
        }
        return m;
    }

    /** Bedrock JSON may have comments and a BOM */
    private static JSONObject json(String s) throws org.json.JSONException {
        s = s.replace("﻿", "");
        StringBuilder sb = new StringBuilder();
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (str) {
                sb.append(ch);
                if (ch == '\\' && i + 1 < s.length()) sb.append(s.charAt(++i));
                else if (ch == '"') str = false;
                continue;
            }
            if (ch == '"') { str = true; sb.append(ch); continue; }
            if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; continue; }
            if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') { i = s.indexOf("*/", i + 2); if (i < 0) break; i++; continue; }
            sb.append(ch);
        }
        return new JSONObject(sb.toString());
    }
}
