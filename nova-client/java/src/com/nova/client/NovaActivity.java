package com.nova.client;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.Map;

/**
 * Launcher activity of the NOVA client. Extends the game's MainActivity so the native
 * library talks to us: asset reads are redirected to enabled .mcres packs, and
 * "nova://" links from the JSON UI open client screens.
 */
public class NovaActivity extends com.mojang.minecraftpe.MainActivity {
    static final String TAG = "NOVA";
    private static volatile Map<String, File> overrides = Collections.emptyMap();
    private LanSync lan;
    private static volatile EntityRegistry entities;
    private static volatile AtlasBuilder atlases;
    static native int nativeSetTexMode(int on);

    private boolean newTexturesPref() {
        try {
            byte[] b = PackManager.readFile(new java.io.File(getFilesDir(), "nova_textures"));
            return b.length > 0 && b[0] == '1';
        } catch (Throwable t) {
            return false;
        }
    }

    private void setNewTexturesPref(boolean on) {
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "nova_textures"));
            o.write(on ? '1' : '0');
            o.close();
        } catch (Throwable t) {
            Log.w(TAG, "textures pref", t);
        }
    }

    static boolean nativeOk;
    private static String nativeError;

    static {
        // libnova hooks the engine (bone trees, animations); the game runs without it
        try {
            System.loadLibrary("nova");
            nativeOk = true;
        } catch (Throwable t) {
            nativeError = String.valueOf(t.getMessage());
            Log.e(TAG, "libnova not loaded", t);
        }
    }

    static native int nativeLoadAnimations(byte[] blob);
    static native int nativeInfo(int what);
    static native int nativeLoadFaceUv(byte[] blob);
    static native int nativeSetDataDir(String dir);
    static native int nativeOpenNova(int kind, String a, String b);
    static native String nativePollNova();
    static native int nativeToast(String title, String sub, int icon);
    static native int nativeCrashFile(String path);

    private static volatile String pendingToastTitle, pendingToastSub;
    private static volatile byte[] toastIcon;
    private static int toastIconId, pendingIcon;

    /** Toast with a picture (pack icon) on the left. */
    public static synchronized void postToast(String title, String sub, java.io.File icon) {
        byte[] b = null;
        try { if (icon != null && icon.exists()) b = PackManager.readFile(icon); } catch (Throwable ignored) { }
        toastIcon = b;
        pendingIcon = b != null ? ++toastIconId : 0;
        postToast(title, sub);
    }

    /** Show a Bedrock-style toast in the game (from any thread). */
    public static void postToast(String title, String sub) {
        String t = sub == null ? "" : sub;
        if (t.length() > 30) t = t.substring(0, 29) + "…";
        pendingToastSub = t;
        pendingToastTitle = title;
    }

    private void sendPendingToast() {
        String t = pendingToastTitle;
        if (t == null || !nativeOk) return;
        try {
            if (nativeInfo(9) != 1) return;
            pendingToastTitle = null;
            int icon = pendingIcon;
            pendingIcon = 0;
            nativeToast(t, pendingToastSub, icon);
        } catch (Throwable ignored) { }
    }

    /** A crash report written by libnova during the previous run -> Download/NOVA-crash.txt */
    private void handleCrashReport() {
        java.io.File f = new java.io.File(getFilesDir(), "nova_crash.txt");
        if (f.exists()) {
            try {
                byte[] data = PackManager.readFile(f);
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("NOVA crash", new String(data, "UTF-8")));
                } catch (Throwable ignored) { }
                java.io.File out = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "NOVA-crash.txt");
                java.io.FileOutputStream o = new java.io.FileOutputStream(out);
                o.write(data);
                o.close();
                postToast("@nova.toast.crash", "Download/NOVA-crash.txt");
            } catch (Throwable t) {
                Log.w(TAG, "crash report copy", t);
                postToast("@nova.toast.crash", "files/nova_crash.txt");
            }
            f.renameTo(new java.io.File(getFilesDir(), "nova_crash_prev.txt"));
        }
        try { nativeCrashFile(f.getAbsolutePath()); } catch (Throwable ignored) { }
    }

    private final java.util.List<PackManager.Pack> novaPacks = new java.util.ArrayList<PackManager.Pack>();
    private android.os.Handler novaHandler;

    private boolean ru() {
        return "ru".equals(java.util.Locale.getDefault().getLanguage());
    }

    /** Open a NOVA menu inside the game; false = native part unavailable. */
    private boolean openNova(int kind) {
        if (!nativeOk) return false;
        try {
            if (nativeInfo(9) != 1) return false;
            String a = "", b = "";
            if (kind == 1) {
                novaPacks.clear();
                novaPacks.addAll(PackManager.list(this));
                StringBuilder names = new StringBuilder(), flags = new StringBuilder();
                boolean ru = ru();
                names.append('#').append(++packIconGen).append('\n');
                for (PackManager.Pack p : novaPacks) {
                    String nm = p.name == null || p.name.length() == 0 ? p.uuid : p.name;
                    // plain ASCII: a mixed Latin/Cyrillic line is drawn too wide by the 0.14 font
                    String sub = "v" + p.versionString()
                            + (p.author == null || p.author.length() == 0 ? "" : "  -  " + p.author);
                    names.append(clean(nm)).append('\t').append(clean(sub)).append(p.mod ? "\tm" : "").append('\n');
                    flags.append(p.enabled ? '1' : '0');
                }
                a = names.toString();
                b = flags.toString();
            } else {
                a = ru() ? "Новый мир" : "My World";
            }
            return nativeOpenNova(kind, a, b) == 0;
        } catch (Throwable t) {
            Log.e(TAG, "nova menu", t);
            return false;
        }
    }

    private static int packIconGen;

    private static String clean(String s) {
        return s.replace('\n', ' ').replace('\t', ' ').replace('\r', ' ');
    }

    /** "nova/pack_icon_<gen>_<k>.png": icon of pack k of the open menu (99 = import) */
    private int[] packIcon(String np) {
        Bitmap b = null;
        try {
            String t = np.substring(np.indexOf("nova/pack_icon_") + 15);
            int k = Integer.parseInt(t.substring(t.indexOf('_') + 1, t.indexOf('.')));
            if (k >= 0 && k < novaPacks.size()) {
                File f = novaPacks.get(k).icon();
                if (f.exists()) b = BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            if (b == null) {
                java.io.InputStream in = getAssets().open(k == 99 ? "images/gui/nova/import.png" : "images/gui/nova/packs.png");
                b = BitmapFactory.decodeStream(in);
                in.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "pack icon " + np, t);
        }
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h + 2];
        px[0] = w;
        px[1] = h;
        b.getPixels(px, 2, w, 0, 0, w, h);
        b.recycle();
        return px;
    }

    private void pollNova() {
        String r;
        try { r = nativePollNova(); } catch (Throwable t) { return; }
        if (r == null) return;
        try {
            if (r.startsWith("R")) {
                setNewTexturesPref(r.length() > 1 && r.charAt(1) == '1');
                android.widget.Toast.makeText(this, ru() ? "Применяем текстуры: перезапуск…" : "Applying textures: restarting…",
                        android.widget.Toast.LENGTH_SHORT).show();
                final android.app.Activity self = this;
                novaHandler.postDelayed(new Runnable() { public void run() { restartGame(self); } }, 800);
            } else if (r.startsWith("I")) {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                startActivityForResult(pick, 7301);
            } else if (r.startsWith("P|")) applyPacks(r);
            else if (r.startsWith("C|")) createWorld(r.substring(2));

        } catch (Throwable t) {
            Log.e(TAG, "nova result", t);
        }
    }

    private void applyPacks(String r) throws Exception {
        String[] parts = r.split("\\|", -1);
        String on = parts.length > 1 ? parts[1] : "", del = parts.length > 2 ? parts[2] : "";
        boolean changed = false, modsChanged = false;
        for (int i = 0; i < novaPacks.size(); i++) {
            PackManager.Pack p = novaPacks.get(i);
            boolean wantDel = i < del.length() && del.charAt(i) == '1';
            boolean wantOn = i < on.length() && on.charAt(i) == '1';
            if (wantDel) {
                PackManager.delete(this, p.uuid);
                if (p.mod) modsChanged |= p.enabled; else changed |= p.enabled;
            } else if (wantOn != p.enabled) {
                PackManager.setEnabled(this, p.uuid, wantOn);
                if (p.mod) modsChanged = true; else changed = true;
            }
        }
        if (modsChanged) changed = true;            // mods may add blocks: they are registered at game start
        if (changed) {
            android.widget.Toast.makeText(this, ru() ? "Наборы ресурсов применяются: перезапуск…"
                    : "Applying resource packs: restarting…", android.widget.Toast.LENGTH_SHORT).show();
            final android.app.Activity self = this;
            novaHandler.postDelayed(new Runnable() { public void run() { restartGame(self); } }, 600);
        }
    }

    private void createWorld(String f) throws Exception {
        String[] v = f.split("\u001f", -1);
        WorldWriter.Settings st = new WorldWriter.Settings();
        String n = v[0].trim();
        st.name = n.length() == 0 ? (ru() ? "Новый мир" : "My World") : n;
        st.seed = WorldWriter.seedFrom(v[1], new java.util.Random());
        st.gameType = num(v[2]);
        st.generator = num(v[3]);
        st.startTime = num(v[4]);
        st.dayCycle = num(v[5]) == 1;
        st.spawnMobs = num(v[6]) == 1;
        st.weather = num(v[7]);
        st.spawnX = Math.max(-1000000, Math.min(1000000, num(v[8])));
        st.spawnZ = Math.max(-1000000, Math.min(1000000, num(v[9])));
        java.io.File worlds = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                "games/com.mojang/minecraftWorlds");
        WorldWriter.create(worlds, st);
        android.widget.Toast.makeText(this, (ru() ? "Мир «" + st.name + "» создан. Сид: " : "World \"" + st.name
                + "\" created. Seed: ") + st.seed, android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 7301) {
            if (res == RESULT_OK && data != null && data.getData() != null) importPack(this, data.getData());
            return;
        }
        super.onActivityResult(req, res, data);
    }

    /** Import a .mcres in the background and report with a toast (pack icon on the left). */
    static void importPack(final android.content.Context c, final android.net.Uri uri) {
        final boolean ru = "ru".equals(java.util.Locale.getDefault().getLanguage());
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                PackManager.Pack pk = null;
                try {
                    String name = uri.getLastPathSegment();
                    java.io.InputStream s = c.getContentResolver().openInputStream(uri);
                    if (s == null) throw new java.io.IOException(ru ? "нет доступа к файлу" : "no access to the file");
                    pk = PackManager.importStream(c, s, "user", name == null ? "pack" : name, null, false, null);
                } catch (PackManager.PackException e) {
                    err = e.getMessage();
                } catch (SecurityException e) {
                    err = ru ? "нет доступа к файлу" : "no access to the file";
                } catch (Throwable e) {
                    err = String.valueOf(e.getMessage());
                }
                if (err == null) postToast("@nova.toast.imported", pk.name, pk.icon());
                else postToast("@nova.toast.importfail", err);
            }
        }).start();
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
    static native int nativeLoadRebuild(byte[] blob);

    /** Short engine report (logcat). */
    static String nativeReport() {
        if (!nativeOk) return "NOVA: движок не загружен (" + nativeError + ")";
        try {
            int st = nativeInfo(0);
            if (st != 1) return "NOVA: движок не подключился (код " + st + ")";
            return "NOVA: движок OK · доп. костей " + nativeInfo(1) + " · анимаций " + nativeInfo(3)
                    + " · мобов анимировано " + nativeInfo(4)
                    + " · из кода игры → JSON " + nativeInfo(6) + " · граней UV " + nativeInfo(7)
                    + (nativeInfo(8) == 1 ? " · настройки NOVA OK" : " · настройки NOVA НЕ подключены")
                    + (nativeInfo(9) == 1 ? " · меню NOVA OK" : " · меню NOVA НЕ подключены")
                    + (nativeInfo(5) == 1 ? "" : " · JSON-файлы НЕ через NOVA")
                    + " · Mobs.json " + (entities != null && entities.mobsServed ? "из папок" : "НЕ из папок");
        } catch (Throwable t) {
            return "NOVA: движок ошибка " + t;
        }
    }

    @Override
    public void onCreate(Bundle state) {
        // must be ready before super.onCreate(): the native lib starts loading assets there
        try {
            overrides = PackManager.buildIndex(this);
            Log.i(TAG, "resource overrides: " + overrides.size() + " files from "
                    + PackManager.enabledOrder(this).size() + " enabled pack(s)");
        } catch (Throwable t) {
            Log.e(TAG, "pack index failed", t);
            overrides = Collections.emptyMap();
        }
        try {
            entities = new EntityRegistry(getAssets(), new EntityRegistry.Source() {
                public File override(String path) { return NovaActivity.override(path); }
            });
            boolean newTex = newTexturesPref();
            atlases = new AtlasBuilder(entities, newTex);
            if (nativeOk) {
                try { nativeSetTexMode(newTex ? 1 : 0); } catch (Throwable t) { Log.w(TAG, "tex mode", t); }
                try { nativeSetDataDir(getFilesDir().getAbsolutePath()); } catch (Throwable t) { Log.w(TAG, "data dir", t); }
                handleCrashReport();
                byte[] mobs = entities.mobsJson();          // also collects per-face UVs
                byte[] fuv = entities.faceUvBlob();
                if (mobs != null && fuv != null) nativeLoadFaceUv(fuv);
                byte[] rb = entities.rebuildBlob();
                if (rb != null) nativeLoadRebuild(rb);
                byte[] blob = entities.animations();
                if (blob != null) nativeLoadAnimations(blob);
                try { ModBridge.nativeModInit(); } catch (Throwable t) { Log.w(TAG, "mods: native part missing", t); }
            }
        } catch (Throwable t) {
            Log.e(TAG, "entity registry failed", t);
        }
        ModBridge.load(this);
        if (atlases != null) atlases.setBlockExtras(ModBridge.blocks);
        super.onCreate(state);
        // targetSdk 24: storage is a runtime permission; worlds live in games/com.mojang on external storage
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7300);
            }
        } catch (Throwable t) {
            Log.w(TAG, "permission request failed", t);
        }
        try {
            lan = new LanSync(this);
            lan.start();
        } catch (Throwable t) {
            Log.e(TAG, "LAN sync failed to start", t);
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        novaHandler = h;
        Log.i(TAG, nativeReport());
        IntroView.attach(this);
        h.postDelayed(new Runnable() {
            public void run() {
                pollNova();
                sendPendingToast();
                novaHandler.postDelayed(this, 300);
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        if (lan != null) lan.stop();
        super.onDestroy();
    }

    private static byte[] readOverride(String path) {
        try { return PackManager.readFile(override(path)); } catch (Throwable t) { return null; }
    }

    /** Pack file overriding a game asset path (relative to assets/), or null. */
    public static File overrideFile(String path) { return override(path); }

    private static File override(String path) {
        if (path == null) return null;
        Map<String, File> m = overrides;
        if (m.isEmpty()) return null;
        File f = m.get(path);
        if (f == null && path.startsWith("/")) f = m.get(path.substring(1));
        if (f == null && path.startsWith("assets/")) f = m.get(path.substring(7));
        return f;
    }

    static String norm(String path) {
        if (path == null) return null;
        if (path.startsWith("/")) path = path.substring(1);
        if (path.startsWith("assets/")) path = path.substring(7);
        return path;
    }

    /** Entity tree: generated Mobs.json or a redirected mob file; null = not ours. */
    private byte[] entityBytes(String path) {
        EntityRegistry e = entities;
        if (e == null) return null;
        String p = norm(path);
        try {
            AtlasBuilder ab = atlases;
            if (ab != null && p.startsWith("images/") && p.endsWith(".tga")) {
                byte[] a = ab.get(p);
                if (a != null) return a;
                if (p.startsWith("images/terrain-atlas") || p.equals("images/items-opaque.tga"))
                    return e.read("images/old_" + p.substring(7));      // stock atlas as a fallback
            }
            if (e.isMobsJson(p)) {
                byte[] m = e.mobsJson();
                if (m != null) e.mobsServed = true;
                return m;
            }
            String r = e.redirect(p);
            if (r != null) return e.read(r);
        } catch (Throwable t) {
            Log.w(TAG, "entity file failed, using game file: " + path, t);
        }
        return null;
    }

    @Override
    public byte[] getFileDataBytes(String path) {
        String np0 = norm(path);
        // mod blocks: the block atlas grows a strip for their textures, and they need names
        ModBlocks mb = ModBridge.blocks;
        if (mb != null && np0 != null) {
            if (np0.equals("images/terrain.meta") && mb.hasAtlasStrip() && override(path) == null) {
                byte[] m = mb.terrainMeta(super.getFileDataBytes(path));
                if (m != null) return m;
            }
            if (np0.startsWith("loc/") && np0.endsWith(".lang") && !mb.lang.isEmpty()) {
                byte[] base = override(path) != null ? readOverride(path) : super.getFileDataBytes(path);
                if (base != null) {
                    byte[] add = mb.langLines();
                    byte[] all = new byte[base.length + add.length];
                    System.arraycopy(base, 0, all, 0, base.length);
                    System.arraycopy(add, 0, all, base.length, add.length);
                    return all;
                }
            }
        }
        // a pack with a whole legacy atlas keeps the stock tile positions
        if ("images/terrain.meta".equals(np0) && entities != null && override(path) == null
                && override("images/terrain-atlas.tga") != null) {
            try { return entities.read("images/old_terrain.meta"); } catch (Throwable ignored) { }
        }
        if (override(path) == null) {
            byte[] b = entityBytes(path);
            if (b != null) return b;
        }
        File f = override(path);
        if (f != null) {
            try {
                return PackManager.readFile(f);
            } catch (Throwable t) {
                Log.w(TAG, "pack file unreadable, using game file: " + path, t);
            }
        }
        byte[] b = super.getFileDataBytes(path);
        if (b == null && entities != null) {
            // JSON reads now come through here (libnova routes readAssetFile to Java):
            // fall back to the APK asset if the stock implementation found nothing
            try {
                b = entities.read(norm(path));
            } catch (Throwable ignored) { }
        }
        return b;
    }

    @Override
    public int[] getImageData(String path, boolean fromAssets) {
        String np = norm(path);
        if (np != null && np.contains("nova/pack_icon_")) {
            int[] px = packIcon(np);
            if (px != null) return px;
        }
        if (np != null && np.contains("nova/toast_icon_") && toastIcon != null) {
            Bitmap tb = BitmapFactory.decodeByteArray(toastIcon, 0, toastIcon.length);
            if (tb != null) {
                int w = tb.getWidth(), h = tb.getHeight();
                int[] px = new int[w * h + 2];
                px[0] = w;
                px[1] = h;
                tb.getPixels(px, 2, w, 0, 0, w, h);
                tb.recycle();
                return px;
            }
        }
        if (fromAssets) {
            File f = override(path);
            if (f != null) {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) {
                    int w = b.getWidth(), h = b.getHeight();
                    int[] px = new int[w * h + 2];
                    px[0] = w;
                    px[1] = h;
                    b.getPixels(px, 2, w, 0, 0, w, h);
                    b.recycle();
                    return px;
                }
                Log.w(TAG, "pack image undecodable, using game file: " + path);
            }
        }
        if (fromAssets && override(path) == null) {
            byte[] data = entityBytes(path);
            if (data != null) {
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b != null) {
                    int w = b.getWidth(), h = b.getHeight();
                    int[] px = new int[w * h + 2];
                    px[0] = w;
                    px[1] = h;
                    b.getPixels(px, 2, w, 0, 0, w, h);
                    b.recycle();
                    return px;
                }
            }
        }
        return super.getImageData(path, fromAssets);
    }

    @Override
    public void launchUri(String uri) {
        if (uri != null && uri.startsWith("nova://")) {
            if (uri.startsWith("nova://packs")) {
                if (!openNova(1)) startActivity(new Intent(this, PacksActivity.class));
            }
            return;
        }
        super.launchUri(uri);
    }

    /** Restart the whole game process (packs are applied at startup). */
    public static void restartGame(android.app.Activity from) {
        Intent i = new Intent(from, RestartActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        from.startActivity(i);
        from.finishAffinity();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
        }, 250);
    }
}
