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

    /** mod blocks of this run (atlas strip, lang lines); null until load() */
    static volatile ModBlocks blocks;

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
            } else {
                rt.customIds.putAll(blocks.ids());
            }
            runtime = rt;
            Log.i(TAG, n + " mod(s) loaded");
        } catch (Throwable t) {
            Log.e(TAG, "mod runtime failed", t);
        }
    }
}
