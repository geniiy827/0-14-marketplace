package com.nova.client;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * .mcres resource packs: storage, import, enable/disable and the asset override index.
 *
 * Layout on disk (app private storage):
 *   files/nova/state.json                 {"enabled":[uuid, ...]}   first = highest priority
 *   files/nova/packs/<uuid>/manifest.json copy of the pack manifest
 *   files/nova/packs/<uuid>/pack_icon.png optional
 *   files/nova/packs/<uuid>/pack.mcres    original archive (used for LAN sharing)
 *   files/nova/packs/<uuid>/nova_meta.json {"source":"user|lan","sha1":..,"size":..,"from":..}
 *   files/nova/packs/<uuid>/files/...     extracted files, same paths as the game's assets/
 */
public final class PackManager {
    private PackManager() { }

    public static final long MAX_PACK_BYTES = 128L * 1024 * 1024;
    private static final Pattern UUID_RE = Pattern.compile("[A-Za-z0-9_-]{4,64}");

    public static final class PackException extends Exception {
        public PackException(String msg) { super(msg); }
    }

    public static final class Pack {
        public String uuid, name, description, author, source, sha1, from;
        public int[] version = {1, 0, 0};
        public long size;
        public File dir;
        public boolean enabled;
        public boolean mod;                  // .mcmod (mod.json) instead of a resource pack
        public String main = "main.mcs";

        public String versionString() { return version[0] + "." + version[1] + "." + version[2]; }
        public File icon() { return new File(dir, "pack_icon.png"); }
        public File archive() { return new File(dir, "pack.mcres"); }
        public File files() { return new File(dir, "files"); }
        public boolean fromLan() { return "lan".equals(source); }
    }

    // ------------------------------------------------------------------ paths & state

    public static File root(Context c) {
        File r = new File(c.getFilesDir(), "nova");
        if (!r.exists()) r.mkdirs();
        return r;
    }

    static File packsDir(Context c) {
        File p = new File(root(c), "packs");
        if (!p.exists()) p.mkdirs();
        return p;
    }

    public static synchronized List<String> enabledOrder(Context c) {
        List<String> out = new ArrayList<String>();
        try {
            JSONObject st = new JSONObject(readText(new File(root(c), "state.json")));
            JSONArray a = st.optJSONArray("enabled");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private static void saveEnabled(Context c, List<String> order) throws IOException {
        try {
            JSONObject st = new JSONObject();
            JSONArray a = new JSONArray();
            for (String s : order) a.put(s);
            st.put("enabled", a);
            writeText(new File(root(c), "state.json"), st.toString(2));
        } catch (org.json.JSONException e) {
            throw new IOException(e.toString());
        }
    }

    public static synchronized void setEnabled(Context c, String uuid, boolean on) throws IOException {
        List<String> order = enabledOrder(c);
        order.remove(uuid);
        if (on) order.add(0, uuid);
        saveEnabled(c, order);
    }

    public static synchronized void moveUp(Context c, String uuid) throws IOException {
        List<String> order = enabledOrder(c);
        int i = order.indexOf(uuid);
        if (i > 0) { Collections.swap(order, i, i - 1); saveEnabled(c, order); }
    }

    public static synchronized void delete(Context c, String uuid) throws IOException {
        setEnabled(c, uuid, false);
        deleteTree(new File(packsDir(c), uuid));
    }

    // ------------------------------------------------------------------ listing

    public static synchronized List<Pack> list(Context c) {
        List<String> order = enabledOrder(c);
        List<Pack> enabled = new ArrayList<Pack>(), rest = new ArrayList<Pack>();
        File[] dirs = packsDir(c).listFiles();
        if (dirs != null) for (File d : dirs) {
            Pack p = load(d);
            if (p == null) continue;
            p.enabled = order.contains(p.uuid);
            (p.enabled ? enabled : rest).add(p);
        }
        // enabled in priority order, the rest by name
        final List<String> ord = order;
        Collections.sort(enabled, new java.util.Comparator<Pack>() {
            public int compare(Pack a, Pack b) { return ord.indexOf(a.uuid) - ord.indexOf(b.uuid); }
        });
        Collections.sort(rest, new java.util.Comparator<Pack>() {
            public int compare(Pack a, Pack b) { return a.name.compareToIgnoreCase(b.name); }
        });
        enabled.addAll(rest);
        return enabled;
    }

    public static Pack find(Context c, String uuid) {
        for (Pack p : list(c)) if (p.uuid.equals(uuid)) return p;
        return null;
    }

    private static Pack load(File dir) {
        try {
            File mj = new File(dir, "mod.json");
            Pack p = mj.isFile() ? parseModJson(readText(mj)) : parseManifest(readText(new File(dir, "manifest.json")));
            p.dir = dir;
            JSONObject meta = new JSONObject(readText(new File(dir, "nova_meta.json")));
            p.source = meta.optString("source", "user");
            p.sha1 = meta.optString("sha1", "");
            p.size = meta.optLong("size", 0);
            p.from = meta.optString("from", "");
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ manifest

    static Pack parseManifest(String json) throws PackException {
        try {
            JSONObject m = new JSONObject(json);
            int fv = m.optInt("format_version", 1);
            if (fv != 1) throw new PackException("Неподдерживаемая версия формата: " + fv);
            JSONObject h = m.getJSONObject("header");
            Pack p = new Pack();
            p.name = h.getString("name").trim();
            p.uuid = h.getString("uuid").trim();
            p.description = h.optString("description", "");
            p.author = h.optString("author", "");
            JSONArray v = h.optJSONArray("version");
            if (v != null && v.length() == 3) p.version = new int[]{v.getInt(0), v.getInt(1), v.getInt(2)};
            if (p.name.isEmpty()) throw new PackException("В manifest.json пустое имя набора");
            if (!UUID_RE.matcher(p.uuid).matches()) throw new PackException("Некорректный uuid в manifest.json");
            return p;
        } catch (org.json.JSONException e) {
            throw new PackException("Ошибка в manifest.json: " + e.getMessage());
        }
    }

    /** mod.json of a .mcmod: {"id", "name", "version": "1.0.0", "author", "description", "main"} */
    static Pack parseModJson(String json) throws PackException {
        try {
            JSONObject m = new JSONObject(json);
            Pack p = new Pack();
            p.mod = true;
            String id = m.optString("id", "").trim().toLowerCase(java.util.Locale.US);
            if (!id.matches("[a-z0-9_-]{2,48}"))
                throw new PackException("В mod.json нужен \"id\": латиница, цифры, _ или - (2-48 символов)");
            p.uuid = "mod-" + id;
            p.name = m.optString("name", id).trim();
            if (p.name.isEmpty()) p.name = id;
            p.description = m.optString("description", "");
            p.author = m.optString("author", "");
            p.main = m.optString("main", "main.mcs");
            String[] v = m.optString("version", "1.0.0").split("\\.");
            for (int i = 0; i < 3 && i < v.length; i++) {
                try { p.version[i] = Integer.parseInt(v[i].replaceAll("[^0-9]", "")); } catch (Exception ignored) { }
            }
            return p;
        } catch (org.json.JSONException e) {
            throw new PackException("Ошибка в mod.json: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports an .mcres archive. The pack is installed DISABLED unless enable=true.
     * @param source "user" or "lan"
     */
    public static synchronized Pack importStream(Context c, InputStream in, String source, String from,
                                                 String expectedSha1, boolean enable, Progress progress)
            throws PackException, IOException {
        File tmp = new File(root(c), "import.tmp");
        String sha1;
        long size;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_PACK_BYTES) throw new PackException("Файл слишком большой (больше 128 МБ)");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    if (progress != null) progress.onBytes(total);
                }
            } finally {
                out.close();
                try { in.close(); } catch (IOException ignored) { }
            }
            sha1 = hex(md.digest());
            size = total;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e.toString());
        }
        if (expectedSha1 != null && !expectedSha1.isEmpty() && !expectedSha1.equalsIgnoreCase(sha1)) {
            tmp.delete();
            throw new PackException("Файл повреждён при передаче (контрольная сумма не совпала)");
        }

        ZipFile zip;
        try {
            zip = new ZipFile(tmp);
        } catch (IOException e) {
            tmp.delete();
            throw new PackException("Файл не является архивом .mcres / .mcmod");
        }
        File dir = null;
        try {
            ZipEntry me = zip.getEntry("manifest.json");
            ZipEntry mm = zip.getEntry("mod.json");
            if (me == null && mm == null) throw new PackException("В архиве нет manifest.json (набор ресурсов) или mod.json (мод)");
            boolean isMod = mm != null;
            String manifestText = new String(readAll(zip.getInputStream(isMod ? mm : me)), "UTF-8");
            Pack p = isMod ? parseModJson(manifestText) : parseManifest(manifestText);
            if (isMod && zip.getEntry(p.main) == null) throw new PackException("В моде нет файла " + p.main);

            dir = new File(packsDir(c), p.uuid);
            boolean wasEnabled = enabledOrder(c).contains(p.uuid);
            if (dir.exists()) deleteTree(dir);
            File files = new File(dir, "files");
            files.mkdirs();
            String canonFiles = files.getCanonicalPath() + File.separator;

            int count = 0;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (name.equals(isMod ? "mod.json" : "manifest.json")) continue;
                if (name.equals("pack_icon.png") || (isMod && name.equals("icon.png"))) {
                    copy(zip.getInputStream(e), new File(dir, "pack_icon.png"));
                    continue;
                }
                if (!isMod && name.startsWith("assets/")) name = name.substring(7);
                if (name.isEmpty() || name.startsWith("/") || name.contains("../") || name.startsWith("..")) continue;
                File target = new File(files, name);
                if (!target.getCanonicalPath().startsWith(canonFiles)) continue; // zip-slip guard
                target.getParentFile().mkdirs();
                copy(zip.getInputStream(e), target);
                count++;
            }
            if (count == 0) throw new PackException("В наборе нет ни одного файла ресурсов (папка assets/ пуста)");

            writeText(new File(dir, isMod ? "mod.json" : "manifest.json"), manifestText);
            JSONObject meta = new JSONObject();
            meta.put("source", source);
            meta.put("sha1", sha1);
            meta.put("size", size);
            meta.put("from", from == null ? "" : from);
            meta.put("files", count);
            meta.put("imported_at", System.currentTimeMillis());
            writeText(new File(dir, "nova_meta.json"), meta.toString(2));
            zip.close();
            zip = null;
            if (!tmp.renameTo(new File(dir, "pack.mcres"))) {
                copy(new FileInputStream(tmp), new File(dir, "pack.mcres"));
                tmp.delete();
            }
            if (enable || wasEnabled) setEnabled(c, p.uuid, true);
            Pack loaded = load(dir);
            loaded.enabled = enable || wasEnabled;
            return loaded;
        } catch (org.json.JSONException e) {
            throw new PackException("Ошибка записи: " + e.getMessage());
        } catch (PackException e) {
            if (dir != null) deleteTree(dir);
            throw e;
        } finally {
            if (zip != null) try { zip.close(); } catch (IOException ignored) { }
            if (tmp.exists()) tmp.delete();
        }
    }

    public interface Progress { void onBytes(long total); }

    // ------------------------------------------------------------------ override index

    /** path relative to assets/ -> file from the highest-priority enabled pack that has it. */
    public static Map<String, File> buildIndex(Context c) {
        Map<String, File> idx = new HashMap<String, File>();
        List<String> order = enabledOrder(c);
        for (int i = order.size() - 1; i >= 0; i--) {  // lowest priority first, higher ones overwrite
            File files = new File(new File(packsDir(c), order.get(i)), "files");
            // mods (mod.json) are run by ModRuntime, they are not resource overrides
            if (files.isDirectory() && !new File(new File(packsDir(c), order.get(i)), "mod.json").isFile()) addTree(idx, files, "");
        }
        return idx;
    }

    private static void addTree(Map<String, File> idx, File dir, String prefix) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) addTree(idx, f, prefix + f.getName() + "/");
            else idx.put(prefix + f.getName(), f);
        }
    }

    // ------------------------------------------------------------------ io helpers

    public static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    public static byte[] readFile(File f) throws IOException { return readAll(new FileInputStream(f)); }

    static String readText(File f) throws IOException { return new String(readFile(f), "UTF-8"); }

    static void writeText(File f, String s) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    static void copy(InputStream in, File to) throws IOException {
        FileOutputStream o = new FileOutputStream(to);
        try {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } finally {
            o.close();
            in.close();
        }
    }

    static void deleteTree(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File x : ch) deleteTree(x);
        f.delete();
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
