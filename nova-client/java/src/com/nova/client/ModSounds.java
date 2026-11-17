package com.nova.client;

import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.SoundPool;
import android.util.Log;

import com.nova.client.mcmod.ModRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sounds of mods, the Bedrock way: every sounds/sound_definitions.json inside an enabled mod names sounds
 * ("pntmc:pd_spotted" -> files, volume, pitch). They play from a point in the world: loudness falls with the
 * distance to the player (range 16 blocks x volume, like Bedrock), left / right by the direction. The volume of
 * playing sounds follows the player every tick.
 */
final class ModSounds implements ModRuntime.Sounds {
    static final String TAG = "NOVA.sounds";

    private static final class Def {
        final List<String> files = new ArrayList<String>();
        final List<float[]> vp = new ArrayList<float[]>();     // per file: volume, pitch
        float volume = 1, pitch = 1;
    }
    private static final class Playing {
        int stream; String name; double x, y, z; float vol, range; long until;
    }

    private final Map<String, Def> defs = new HashMap<String, Def>();
    private final Map<String, Integer> loaded = new HashMap<String, Integer>();  // file -> SoundPool id
    private final Map<String, Long> durations = new HashMap<String, Long>();
    private final List<Playing> playing = new ArrayList<Playing>();
    private final Random rnd = new Random();
    private SoundPool pool;
    private double lx, ly, lz, lyaw;          // listener

    void prepare(Map<String, File> dirs) {
        for (File d : dirs.values()) walk(d, 0);
        if (defs.isEmpty()) return;
        try {
            pool = new SoundPool.Builder().setMaxStreams(24).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        } catch (Throwable t) {
            Log.e(TAG, "SoundPool", t);
            return;
        }
        int n = 0;
        for (Def df : defs.values()) for (String f : df.files) {
            if (loaded.containsKey(f)) continue;
            try {
                loaded.put(f, pool.load(f, 1));
                MediaMetadataRetriever mr = new MediaMetadataRetriever();
                mr.setDataSource(f);
                String ms = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                mr.release();
                durations.put(f, ms == null ? 10000L : Long.parseLong(ms));
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "load " + f + ": " + t);
            }
        }
        Log.i(TAG, defs.size() + " sound(s), " + n + " file(s)");
    }

    boolean has(String name) { return defs.containsKey(name); }

    private void walk(File d, int depth) {
        File[] list = d.listFiles();
        if (list == null || depth > 8) return;
        for (File x : list) {
            if (x.isDirectory()) { walk(x, depth + 1); continue; }
            if (!x.getName().equalsIgnoreCase("sound_definitions.json")) continue;
            File root = x.getParentFile().getParentFile();          // .../rp/sounds/sound_definitions.json -> rp
            try {
                JSONObject j = new JSONObject(ModMobs.stripComments(PackManager.readText(x)));
                JSONObject sd = j.optJSONObject("sound_definitions");
                if (sd == null) sd = j;
                Iterator<String> it = sd.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    JSONObject o = sd.optJSONObject(name);
                    if (o == null) continue;
                    Def df = new Def();
                    JSONArray ss = o.optJSONArray("sounds");
                    if (ss != null) for (int i = 0; i < ss.length(); i++) {
                        Object e = ss.get(i);
                        String path; float v = 1, p = 1;
                        if (e instanceof JSONObject) {
                            path = ((JSONObject) e).optString("name");
                            v = (float) ((JSONObject) e).optDouble("volume", 1);
                            p = (float) ((JSONObject) e).optDouble("pitch", 1);
                        } else path = String.valueOf(e);
                        File f = null;
                        for (String ext : new String[]{"", ".ogg", ".wav", ".mp3"}) {
                            File c = new File(root, path + ext);
                            if (c.isFile()) { f = c; break; }
                        }
                        if (f == null) { Log.w(TAG, name + ": no file " + path); continue; }
                        df.files.add(f.getAbsolutePath());
                        df.vp.add(new float[]{v, p});
                    }
                    if (!df.files.isEmpty()) defs.put(name, df);
                }
            } catch (Exception e) {
                Log.w(TAG, "sound_definitions " + x + ": " + e);
            }
        }
    }

    // ------------------------------------------------------------ playing
    private float[] gains(double x, double y, double z, float vol, float range) {
        double dx = x - lx, dy = y - ly, dz = z - lz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float g = d >= range ? 0 : (float) (Math.min(1f, vol) * (1 - d / range));
        if (d < 1e-3) return new float[]{g, g};
        // side: + right. Player looks along (-sin yaw, cos yaw) on x/z; right = (-cos yaw, -sin yaw)
        double yr = Math.toRadians(lyaw);
        double side = (dx * -Math.cos(yr) + dz * -Math.sin(yr)) / Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        float l = g * (float) Math.min(1, 1 - side * 0.6), r = g * (float) Math.min(1, 1 + side * 0.6);
        return new float[]{l, r};
    }

    public synchronized boolean play(String name, double x, double y, double z, double volume, double pitch) {
        Def df = defs.get(name);
        if (df == null || pool == null) return false;
        int k = rnd.nextInt(df.files.size());
        String f = df.files.get(k);
        Integer id = loaded.get(f);
        if (id == null) return true;
        float vol = (float) volume * df.volume * df.vp.get(k)[0];
        float range = 16f * Math.max(1f, vol);
        float[] g = gains(x, y, z, vol, range);
        float rate = (float) Math.max(0.5, Math.min(2.0, pitch * df.pitch * df.vp.get(k)[1]));
        int st = pool.play(id, g[0], g[1], 1, 0, rate);
        if (st != 0) {
            Playing p = new Playing();
            p.stream = st; p.name = name; p.x = x; p.y = y; p.z = z; p.vol = vol; p.range = range;
            Long dur = durations.get(f);
            p.until = System.currentTimeMillis() + (long) ((dur == null ? 10000 : dur) / rate) + 200;
            playing.add(p);
        }
        return true;
    }

    public synchronized void stop(String name) {
        if (pool == null) return;
        for (int i = playing.size() - 1; i >= 0; i--) {
            Playing p = playing.get(i);
            if (name == null || name.isEmpty() || p.name.equals(name)) { pool.stop(p.stream); playing.remove(i); }
        }
    }

    /** every tick: the listener moved -> new loudness of the sounds still playing */
    public synchronized void listener(double x, double y, double z, double yaw) {
        lx = x; ly = y; lz = z; lyaw = yaw;
        if (pool == null || playing.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (int i = playing.size() - 1; i >= 0; i--) {
            Playing p = playing.get(i);
            if (now > p.until) { playing.remove(i); continue; }
            float[] g = gains(p.x, p.y, p.z, p.vol, p.range);
            pool.setVolume(p.stream, g[0], g[1]);
        }
    }

    public synchronized void leave() { stop(null); }
}
