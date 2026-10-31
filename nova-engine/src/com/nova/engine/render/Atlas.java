package com.nova.engine.render;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block texture atlas. Source: the Minecraft PE installed on the device (NOVA client or the original game):
 * images/terrain-atlas.tga + images/terrain.meta. Nothing of Mojang is shipped in the engine APK.
 * Without an installed game a generated placeholder atlas is used.
 * A texture is addressed by a slot: slot(name, index) -> u0 v0 u1 v1 in slotUv.
 */
public final class Atlas {
    static final String TAG = "NOVA.atlas";
    public static final String[] GAME_PACKAGES = {"com.mojang.minecraftpn", "com.mojang.minecraftpe"};

    public int texture;
    public int width, height;
    public String source = "none";
    private int[] pixels;                  // ARGB until uploaded
    private final Map<String, Integer> slots = new HashMap<String, Integer>();
    private final List<float[]> uvs = new ArrayList<float[]>();
    public float[] slotUv = new float[0];  // 4 per slot
    private JSONArray meta;
    public AssetManager gameAssets;        // assets of the installed game (fonts, sounds later)

    public void load(Context c) {
        // 1) textures packed into the engine APK (fan build), 2) the installed game, 3) placeholder
        try {
            AssetManager am = c.getAssets();
            byte[] tga = readAll(am.open("images/terrain-atlas.tga"));
            byte[] m = readAll(am.open("images/terrain.meta"));
            if (decodeTga(tga)) {
                meta = new JSONArray(new String(m, "UTF-8"));
                gameAssets = am;
                source = "apk";
            }
        } catch (Throwable t) {
            Log.i(TAG, "no packed textures: " + t);
        }
        for (String p : GAME_PACKAGES) {
            if (meta != null) break;
            try {
                Context g = c.createPackageContext(p, 0);
                AssetManager am = g.getAssets();
                byte[] tga = readAll(am.open("images/terrain-atlas.tga"));
                byte[] m = readAll(am.open("images/terrain.meta"));
                if (decodeTga(tga)) {
                    meta = new JSONArray(new String(m, "UTF-8"));
                    gameAssets = am;
                    source = p;
                    Log.i(TAG, "atlas from " + p + " " + width + "x" + height);
                    break;
                }
            } catch (Throwable t) {
                Log.i(TAG, p + ": " + t);
            }
        }
        if (meta == null) placeholder();
        slot("missing_tile", 0);
    }

    /** slot of a texture of the game atlas (name as in terrain.meta, index of its uv list) */
    public int slot(String name, int index) {
        String key = name + "#" + index;
        Integer s = slots.get(key);
        if (s != null) return s;
        float[] r = null;
        try {
            for (int i = 0; meta != null && i < meta.length(); i++) {
                JSONObject e = meta.getJSONObject(i);
                if (!e.getString("name").equals(name)) continue;
                JSONArray list = e.getJSONArray("uvs");
                JSONArray u = list.getJSONArray(Math.min(index, list.length() - 1));
                float w = (float) u.getDouble(4), h = (float) u.getDouble(5);
                // half a texel inside: no bleeding from neighbours
                float iu = 0.5f / width, iv = 0.5f / height;
                r = new float[]{(float) u.getDouble(0) / w + iu, (float) u.getDouble(1) / h + iv,
                                (float) u.getDouble(2) / w - iu, (float) u.getDouble(3) / h - iv};
                if (name.startsWith("still_") || name.startsWith("flowing_")) {   // animated strips: first frame
                    float fw = r[2] - r[0];
                    r[3] = r[1] + fw * width / height;
                }
                break;
            }
        } catch (Exception ignored) { }
        if (r == null) {
            Integer miss = slots.get("missing_tile#0");
            if (miss != null) return miss;
            r = new float[]{0, 0, 16f / Math.max(1, width), 16f / Math.max(1, height)};
        }
        s = uvs.size();
        uvs.add(r);
        slots.put(key, s);
        slotUv = new float[uvs.size() * 4];
        for (int i = 0; i < uvs.size(); i++) System.arraycopy(uvs.get(i), 0, slotUv, i * 4, 4);
        return s;
    }

    /** GL thread */
    public void upload() {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        texture = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        ByteBuffer bb = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        for (int p : pixels) { bb.put((byte) (p >> 16)); bb.put((byte) (p >> 8)); bb.put((byte) p); bb.put((byte) (p >>> 24)); }
        bb.position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    /** average colour of a slot (for particles / map later) */
    public int pixel(float u, float v) {
        int x = Math.min(width - 1, Math.max(0, (int) (u * width))), y = Math.min(height - 1, Math.max(0, (int) (v * height)));
        return pixels[y * width + x];
    }

    // ------------------------------------------------------------------ TGA
    private boolean decodeTga(byte[] d) {
        int idLen = d[0] & 255, type = d[2] & 255;
        int w = (d[12] & 255) | (d[13] & 255) << 8, h = (d[14] & 255) | (d[15] & 255) << 8;
        int bpp = d[16] & 255, desc = d[17] & 255;
        if ((type != 2 && type != 10) || (bpp != 32 && bpp != 24)) return false;
        int bytes = bpp / 8, p = 18 + idLen, n = w * h;
        int[] px = new int[n];
        int i = 0;
        while (i < n && p < d.length) {
            if (type == 2) { px[i++] = pix(d, p, bytes); p += bytes; continue; }
            int hdr = d[p++] & 255, cnt = (hdr & 127) + 1;
            if ((hdr & 128) != 0) { int c = pix(d, p, bytes); p += bytes; for (int k = 0; k < cnt && i < n; k++) px[i++] = c; }
            else for (int k = 0; k < cnt && i < n; k++) { px[i++] = pix(d, p, bytes); p += bytes; }
        }
        if ((desc & 0x20) == 0) {                       // bottom-left origin: flip
            int[] f = new int[n];
            for (int y = 0; y < h; y++) System.arraycopy(px, (h - 1 - y) * w, f, y * w, w);
            px = f;
        }
        pixels = px; width = w; height = h;
        return true;
    }

    private static int pix(byte[] d, int p, int bytes) {
        int b = d[p] & 255, g = d[p + 1] & 255, r = d[p + 2] & 255, a = bytes == 4 ? d[p + 3] & 255 : 255;
        return a << 24 | r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------ no game installed
    private void placeholder() {
        String[] names = {"missing_tile", "stone", "dirt", "grass", "sand", "gravel", "log", "leaves", "planks", "cobblestone",
                "glass", "bedrock", "brick", "still_water", "tallgrass", "wool", "coal_ore", "iron_ore", "gold_ore", "diamond_ore",
                "sandstone", "snow", "ice", "obsidian", "glowstone", "flower1", "flower2"};
        int[] col = {0xFF00FF, 0x7F7F7F, 0x866043, 0x5FA040, 0xDBD3A0, 0x857F7E, 0x6B5530, 0x3F8030, 0xB08F55, 0x6A6A6A,
                0xC0E8F0, 0x333333, 0x96503F, 0x2F5FD0, 0x4F9030, 0xE8E8E8, 0x494949, 0x8F7F70, 0x8F8F50, 0x5FAFAF,
                0xD8CF9A, 0xF0FAFA, 0x90B0F0, 0x1A1028, 0xE0C060, 0xE04040, 0xE0E040};
        width = 256; height = 256;
        pixels = new int[width * height];
        meta = new JSONArray();
        try {
            for (int k = 0; k < names.length; k++) {
                int tx = (k % 16) * 16, ty = (k / 16) * 16;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    int c = col[k], v = ((x * 7 + y * 13 + k * 5) % 9) - 4;   // a bit of noise
                    int r = clamp(((c >> 16) & 255) + v * 4), g = clamp(((c >> 8) & 255) + v * 4), b = clamp((c & 255) + v * 4);
                    int a = names[k].equals("glass") && x > 0 && x < 15 && y > 0 && y < 15 ? 40 : 255;
                    pixels[(ty + y) * width + tx + x] = a << 24 | r << 16 | g << 8 | b;
                }
                JSONObject e = new JSONObject();
                e.put("name", names[k]);
                JSONArray u = new JSONArray();
                u.put(new JSONArray(new int[]{tx, ty, tx + 16, ty + 16, width, height}));
                e.put("uvs", u);
                meta.put(e);
            }
        } catch (Exception ignored) { }
        source = "placeholder";
    }

    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }
}
