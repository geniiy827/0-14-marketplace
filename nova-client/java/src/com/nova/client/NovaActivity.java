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
    }

    @Override
    protected void onDestroy() {
        if (lan != null) lan.stop();
        super.onDestroy();
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

    @Override
    public byte[] getFileDataBytes(String path) {
        File f = override(path);
        if (f != null) {
            try {
                return PackManager.readFile(f);
            } catch (Throwable t) {
                Log.w(TAG, "pack file unreadable, using game file: " + path, t);
            }
        }
        return super.getFileDataBytes(path);
    }

    @Override
    public int[] getImageData(String path, boolean fromAssets) {
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
        return super.getImageData(path, fromAssets);
    }

    @Override
    public void launchUri(String uri) {
        if (uri != null && uri.startsWith("nova://")) {
            if (uri.startsWith("nova://packs")) {
                startActivity(new Intent(this, PacksActivity.class));
            } else if (uri.startsWith("nova://create_world")) {
                startActivity(new Intent(this, CreateWorldActivity.class));
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
