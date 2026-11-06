package com.nova.client;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The mods of a world for friends: which .mcmod archives are enabled (by sha1) and which block / item numbers the
 * game gave their blocks and items (files/nova/blockids.json, itemids.json; keys "modid:name"). Two phones with the
 * same signature see the same blocks and items under the same numbers, so the world looks and works the same.
 * The host serves file MANIFEST (this set as JSON) and file i (the i-th archive) over a NovaNet files link.
 */
final class ModSet {
    static final int MANIFEST = 255;

    static final class M {
        String uuid, name, sha1; long size; File archive;
    }

    final List<M> mods = new ArrayList<M>();
    final TreeMap<String, Integer> blocks = new TreeMap<String, Integer>(), items = new TreeMap<String, Integer>();
    String sig = "";
    private byte[] manifest, cached;
    private int cachedIndex = -1;

    /** this phone's enabled mods, in the order they are enabled */
    static ModSet local(Context c) {
        ModSet s = new ModSet();
        for (PackManager.Pack p : PackManager.list(c)) {
            if (!p.mod || !p.enabled) continue;
            M m = new M();
            m.uuid = p.uuid; m.name = p.name; m.sha1 = p.sha1 == null ? "" : p.sha1; m.archive = p.archive();
            m.size = m.archive.length();
            s.mods.add(m);
        }
        s.blocks.putAll(onlyMods(readIds(new File(PackManager.root(c), "blockids.json")), s.mods));
        s.items.putAll(onlyMods(readIds(new File(PackManager.root(c), "itemids.json")), s.mods));
        s.sig = s.signature();
        return s;
    }

    /** the same mods as the world wants? (a world without mods wants nothing) */
    boolean matches(Map<String, Object> world) {
        Object ms = world.get("msig");
        String want = ms == null ? "" : String.valueOf(ms);
        if (want.length() == 0) return true;
        return want.equals(sig);
    }

    private String signature() {
        if (mods.isEmpty()) return "";
        List<String> lines = new ArrayList<String>();
        for (M m : mods) lines.add("m " + m.uuid + "=" + m.sha1.toLowerCase(java.util.Locale.US));
        for (Map.Entry<String, Integer> e : blocks.entrySet()) lines.add("b " + e.getKey() + "=" + e.getValue());
        for (Map.Entry<String, Integer> e : items.entrySet()) lines.add("i " + e.getKey() + "=" + e.getValue());
        Collections.sort(lines);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (String l : lines) md.update((l + "\n").getBytes("UTF-8"));
            return PackManager.hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ host side
    synchronized byte[] file(int index) {
        try {
            if (index == MANIFEST) {
                if (manifest == null) manifest = toJson().getBytes("UTF-8");
                return manifest;
            }
            if (index < 0 || index >= mods.size()) return null;
            if (index == cachedIndex) return cached;          // NovaNet asks chunk by chunk
            File f = mods.get(index).archive;
            if (!f.exists() || f.length() > 64L * 1024 * 1024) return null;
            cached = PackManager.readFile(f);
            cachedIndex = index;
            return cached;
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray a = new JSONArray();
        for (M m : mods) {
            JSONObject j = new JSONObject();
            j.put("uuid", m.uuid); j.put("name", m.name); j.put("sha1", m.sha1); j.put("size", m.size);
            a.put(j);
        }
        o.put("mods", a);
        o.put("blocks", new JSONObject(blocks));
        o.put("items", new JSONObject(items));
        return o.toString();
    }

    // ------------------------------------------------------------------ joining side
    static ModSet parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        ModSet s = new ModSet();
        JSONArray a = o.getJSONArray("mods");
        for (int i = 0; i < a.length() && i < 64; i++) {
            JSONObject j = a.getJSONObject(i);
            M m = new M();
            m.uuid = j.getString("uuid"); m.name = j.optString("name", m.uuid); m.sha1 = j.getString("sha1"); m.size = j.optLong("size", 0);
            if (!m.uuid.matches("mod-[a-z0-9_-]{2,48}") || !m.sha1.matches("[0-9a-fA-F]{40}")) throw new Exception("bad manifest");
            s.mods.add(m);
        }
        s.blocks.putAll(onlyMods(jsonIds(o.optJSONObject("blocks")), s.mods));
        s.items.putAll(onlyMods(jsonIds(o.optJSONObject("items")), s.mods));
        return s;
    }

    /** enable exactly these mods (resource packs stay as they are) and take their block / item numbers */
    void apply(Context c) throws Exception {
        List<String> want = new ArrayList<String>();
        for (M m : mods) want.add(m.uuid);
        for (PackManager.Pack p : PackManager.list(c)) if (p.mod && p.enabled && !want.contains(p.uuid)) PackManager.setEnabled(c, p.uuid, false);
        for (int i = mods.size() - 1; i >= 0; i--) PackManager.setEnabled(c, mods.get(i).uuid, true);   // setEnabled puts it first
        mergeIds(new File(PackManager.root(c), "blockids.json"), blocks);
        mergeIds(new File(PackManager.root(c), "itemids.json"), items);
    }

    private static void mergeIds(File f, Map<String, Integer> host) throws Exception {
        TreeMap<String, Integer> m = readIds(f);
        java.util.Iterator<Map.Entry<String, Integer>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> e = it.next();
            if (host.containsValue(e.getValue()) && !host.containsKey(e.getKey())) it.remove();   // that number is the host's
        }
        m.putAll(host);
        PackManager.writeText(f, new JSONObject(m).toString(2));
    }

    // ------------------------------------------------------------------ helpers
    private static TreeMap<String, Integer> readIds(File f) {
        try { return jsonIds(new JSONObject(PackManager.readText(f))); } catch (Exception e) { return new TreeMap<String, Integer>(); }
    }

    private static TreeMap<String, Integer> jsonIds(JSONObject j) {
        TreeMap<String, Integer> m = new TreeMap<String, Integer>();
        if (j == null) return m;
        java.util.Iterator<String> it = j.keys();
        while (it.hasNext()) {
            String k = it.next();
            int v = j.optInt(k, -1);
            if (v > 0 && v < 4096 && k.length() < 128) m.put(k, v);
        }
        return m;
    }

    private static TreeMap<String, Integer> onlyMods(TreeMap<String, Integer> ids, List<M> mods) {
        TreeMap<String, Integer> out = new TreeMap<String, Integer>();
        for (Map.Entry<String, Integer> e : ids.entrySet())
            for (M m : mods) if (e.getKey().startsWith(m.uuid.substring(4) + ":")) { out.put(e.getKey(), e.getValue()); break; }
        return out;
    }
}
