package com.nova.client;

import android.content.Context;
import android.util.Log;

import com.nova.client.mcmod.ModRuntime;

import java.io.File;
import java.io.InputStream;

/** Glue between libnova (game thread) and the McMod Script runtime. */
public final class ModBridge {
    static final String TAG = "NOVA.mods";
    private ModBridge() { }

    private static volatile ModRuntime runtime;

    static native int nativeModInit();                                  // libnova keeps this class for event()
    static native double[] nativeApi(int op, double[] args, String strs);
    static native String nativeApiStr(int op, double[] args, String strs);
    static native int nativeDefineBlocks(double[] data, String names);
    static native int nativeStructures(byte[] data);

    /** mod blocks of this run (atlas strip, lang lines); null until load() */
    static volatile ModBlocks blocks;
    /** mod items of this run (atlas strip, lang lines); null until load() */
    static volatile ModItems items;
    static native int nativeDefineItems(double[] data, String names, String actions);

    // ---- the game itself for mods (native/hook.inc)
    static native int nhBase();
    static native int nhHook(int addr, int mode, int[] sites);
    static native void nhUnhook(int idx);
    static native long nhCall(int fn, int[] words);
    static native long nhCallOrig(int idx, int[] words);
    static native int nhPeek(int addr, int kind);
    static native void nhPoke(int addr, int kind, int value);
    static native int nhAlloc(int n);
    static native void nhFree(int p);
    static native int nhStrNew(String s);
    static native String nhStrGet(int p);
    static native String nhCStr(int p);
    static native int nhGlobal(int which);

    /** libnova: a hooked game function was called (any thread) */
    public static long onHook(int idx, int[] words) {
        ModRuntime rt = runtime;
        if (rt == null) return nhCallOrig(idx, words);
        try {
            return rt.onHook(idx, words);
        } catch (Throwable t) {
            Log.e(TAG, "hook " + idx, t);
            return nhCallOrig(idx, words);
        }
    }

    static final class Mach implements com.nova.client.mcmod.Machine {
        final Context c;
        Mach(Context c) { this.c = c; }
        public int base() { return nhBase(); }
        public int hook(int addr, int mode, int[] sites) { return nhHook(addr, mode, sites); }
        public void unhook(int idx) { nhUnhook(idx); }
        public long call(int fn, int[] w) { return nhCall(fn, w); }
        public long callOrig(int idx, int[] w) { return nhCallOrig(idx, w); }
        public int peek(int addr, int kind) { return nhPeek(addr, kind); }
        public void poke(int addr, int kind, int v) { nhPoke(addr, kind, v); }
        public int alloc(int n) { return nhAlloc(n); }
        public void free(int p) { nhFree(p); }
        public int strNew(String s) { return nhStrNew(s); }
        public String strGet(int p) { return nhStrGet(p); }
        public String cstr(int p) { return nhCStr(p); }
        public int global(int which) { return nhGlobal(which); }
        public InputStream symbols() throws java.io.IOException { return c.getAssets().open("nova/symbols.tsv"); }
    }

    /** live mods: every *.mcs in games/com.mojang/nova_mods (edit on the phone, saved = reloaded in a second) */
    static final java.util.Map<String, Long> liveSeen = new java.util.HashMap<String, Long>();
    static java.util.List<String[]> liveScan() {
        File dir = new File(android.os.Environment.getExternalStorageDirectory(), "games/com.mojang/nova_mods");
        File[] fs = dir.listFiles();
        if (fs == null) {
            try { if (dir.mkdirs()) PackManager.writeText(new File(dir, "README.txt"),
                    "Файлы .mcs в этой папке - живые моды NOVA: сохранил файл - через секунду мод перезапущен в игре.\n"); }
            catch (Exception ignored) { }
            return null;
        }
        java.util.List<String[]> out = null;
        for (File f : fs) {
            File src = f.isDirectory() ? new File(f, "main.mcs") : f;
            if (!src.getName().endsWith(".mcs") || !src.isFile()) continue;
            String id = "live_" + (f.isDirectory() ? f.getName() : f.getName().substring(0, f.getName().length() - 4));
            long t = src.lastModified() ^ src.length();
            Long seen = liveSeen.get(id);
            if (seen != null && seen == t) continue;
            liveSeen.put(id, t);
            try {
                String text = PackManager.readText(src);
                if (out == null) out = new java.util.ArrayList<String[]>();
                out.add(new String[]{id, f.getName(), text});
            } catch (Exception e) { Log.w(TAG, "live " + src, e); }
        }
        return out;
    }

    /** libnova -> scripts (game thread). 1 = cancel the game action */
    public static int event(int ev, String text, double[] args) {
        ModRuntime rt = runtime;
        if (rt == null) return 0;
        try {
            return rt.event(ev, text, args);
        } catch (Throwable t) {
            Log.e(TAG, "event " + ev, t);
            return 0;
        }
    }

    /** (re)load the enabled mods; they start on the next world join */
    static void load(final Context c) {
        try {
            final File dataDir = new File(PackManager.root(c), "moddata");
            String prelude = "";
            try {
                InputStream in = c.getAssets().open("nova/mcmod/prelude.mcs");
                prelude = new String(PackManager.readAll(in), "UTF-8");
                in.close();
            } catch (Exception e) {
                Log.e(TAG, "prelude.mcs missing", e);
            }
            final String pre = prelude;
            final Mach mach = new Mach(c.getApplicationContext());
            liveSeen.clear();
            try {                                       // the folder for live mods, with a readme
                File dir = new File(android.os.Environment.getExternalStorageDirectory(), "games/com.mojang/nova_mods");
                if (!dir.isDirectory() && dir.mkdirs()) PackManager.writeText(new File(dir, "README.txt"),
                        "Файлы .mcs в этой папке - живые моды NOVA: сохранил файл - через секунду мод перезапущен в игре.\n");
            } catch (Exception ignored) { }
            ModRuntime rt = new ModRuntime(new ModRuntime.Host() {
                public double[] nativeApi(int op, double[] args, String strs) { return ModBridge.nativeApi(op, args, strs); }
                public String nativeApiStr(int op, double[] args, String strs) { return ModBridge.nativeApiStr(op, args, strs); }
                public void log(String s) { Log.i(TAG, s); }
                public void toast(String title, String text) { NovaActivity.postToast(title, text); }
                public String loadData(String id) {
                    try { return PackManager.readText(new File(dataDir, id + ".json")); } catch (Exception e) { return null; }
                }
                public void saveData(String id, String json) {
                    try { dataDir.mkdirs(); PackManager.writeText(new File(dataDir, id + ".json"), json); }
                    catch (Exception e) { Log.w(TAG, "save " + id, e); }
                }
                public String prelude() { return pre; }
                public com.nova.client.mcmod.Machine machine() { return mach; }
                public java.util.List<String[]> liveScan() { return ModBridge.liveScan(); }
            });
            int n = 0;
            java.util.Map<String, File> dirs = new java.util.HashMap<String, File>();
            for (PackManager.Pack p : PackManager.list(c)) {
                if (!p.mod || !p.enabled) continue;
                try {
                    String src = PackManager.readText(new File(p.files(), p.main));
                    rt.load(p.uuid.substring(4), p.name, p.main, src);
                    dirs.put(p.uuid.substring(4), p.files());
                    n++;
                } catch (Exception e) {
                    Log.e(TAG, "mod " + p.uuid, e);
                }
            }
            if (blocks == null) {                       // blocks are registered once per game process
                ModBlocks mb = new ModBlocks(c, rt);
                mb.prepare(dirs);
                if (mb.count > 0) {
                    try { nativeDefineBlocks(mb.data, mb.names); } catch (Throwable t) { Log.e(TAG, "native blocks", t); }
                }
                blocks = mb;
                ModItems mi = new ModItems(c, rt);
                mi.prepare(dirs);
                if (mi.count > 0) {
                    try { nativeDefineItems(mi.data, mi.names, mi.actions); } catch (Throwable t) { Log.e(TAG, "native items", t); }
                }
                items = mi;
                for (File d : dirs.values()) {             // structures.bin of mods (tools/port_rs.py): world generation
                    File sb = new File(d, "structures.bin");
                    if (!sb.isFile()) continue;
                    try { nativeStructures(PackManager.readFile(sb)); } catch (Throwable t) { Log.e(TAG, "structures " + d, t); }
                }
            } else {
                rt.customIds.putAll(blocks.ids());
                if (items != null) rt.customIds.putAll(items.ids);
            }
            runtime = rt;
            Log.i(TAG, n + " mod(s) loaded");
        } catch (Throwable t) {
            Log.e(TAG, "mod runtime failed", t);
        }
    }
}
