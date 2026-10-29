package com.nova.client;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Block and item atlases from single tiles: assets/textures/{blocks,items}/...
 * (one PNG per tile, see tools/gen_textures.py). The engine still reads
 * images/terrain-atlas.tga (+ mips) and images/items-opaque.tga; those reads
 * are answered with an atlas built here. Tile sources, first match wins:
 * .mcres pack (textures/...), the "new textures" set (textures_new/...), the
 * stock tile. Packs may ship bigger tiles: the atlas grows to fit.
 */
final class AtlasBuilder {
    static final String TAG = "NOVA.atlas";

    private final EntityRegistry files;      // pack-aware reader
    private final boolean useNew;
    private final Map<String, byte[]> cache = new HashMap<String, byte[]>();

    private ModBlocks extras;                // mod block textures: a strip under the block atlas

    private java.util.List<ModBlocks.Extra> itemExtras;   // mod item textures: free rows of the item atlas
    void setItemExtras(java.util.List<ModBlocks.Extra> l) { itemExtras = l == null || l.isEmpty() ? null : l; }
    void setBlockExtras(ModBlocks mb) { extras = mb != null && mb.hasAtlasStrip() ? mb : null; }

    AtlasBuilder(EntityRegistry files, boolean useNew) {
        this.files = files;
        this.useNew = useNew;
    }

    /** TGA bytes for an atlas path the engine asks for, or null if not an atlas. */
    synchronized byte[] get(String path) {
        String layout, name = path;
        int mip = -1;
        if (name.equals("images/items-opaque.tga")) layout = "items";
        else if (name.equals("images/terrain-atlas.tga")) layout = "blocks";
        else if (name.startsWith("images/terrain-atlas_mip") && name.endsWith(".tga")) {
            layout = "blocks";
            try { mip = Integer.parseInt(name.substring(24, name.length() - 4)); } catch (Exception e) { return null; }
        } else return null;
        byte[] c = cache.get(path);
        if (c != null) return c;
        try {
            int[][] base = build(layout);              // {w,h} in [0], pixels in [1]
            int w = base[0][0], h = base[0][1];
            int[] px = base[1];
            for (int k = 0; k <= mip; k++) {           // mip0 is half size, mip1 a quarter...
                int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
                px = half(px, w, h, nw, nh);
                w = nw; h = nh;
            }
            c = tga(px, w, h);
            cache.put(path, c);
            Log.i(TAG, path + " built " + w + "x" + h + (useNew ? " (new textures)" : ""));
            return c;
        } catch (Throwable t) {
            Log.e(TAG, "atlas " + path + " failed, using the stock file", t);
            return null;
        }
    }

    private final Map<String, int[][]> built = new HashMap<String, int[][]>();

    private int[][] build(String layout) throws Exception {
        int[][] b = built.get(layout);
        if (b != null) return b;
        JSONObject lay = new JSONObject(new String(files.read("textures/" + layout + ".layout.json"), "UTF-8"));
        JSONArray size = lay.getJSONArray("size");
        int W = size.getInt(0), H = size.getInt(1);
        JSONArray entries = lay.getJSONArray("entries");
        // pass 1: decode tiles, find the scale (an HD pack makes the atlas bigger)
        int scale = 1;
        java.util.List<Object[]> jobs = new java.util.ArrayList<Object[]>();
        for (int e = 0; e < entries.length(); e++) {
            JSONObject en = entries.getJSONObject(e);
            JSONArray uvs = en.getJSONArray("uvs");
            int n = uvs.length();
            for (int i = 0; i < n; i++) {
                JSONArray uv = uvs.getJSONArray(i);
                double sx = (double) W / uv.getInt(4), sy = (double) H / uv.getInt(5);
                int x0 = (int) Math.round(uv.getInt(0) * sx), y0 = (int) Math.round(uv.getInt(1) * sy);
                int x1 = (int) Math.round(uv.getInt(2) * sx), y1 = (int) Math.round(uv.getInt(3) * sy);
                String rel = n > 1 ? en.getString("name") + "/" + i + ".png" : en.getString("name") + ".png";
                Bitmap bm = tile(layout, rel);
                if (bm == null) continue;
                int tw = x1 - x0;
                if (tw > 0 && bm.getWidth() > tw) scale = Math.max(scale, Math.min(8, bm.getWidth() / tw));
                jobs.add(new Object[]{bm, x0, y0, x1, y1});
            }
        }
        ModBlocks mb = layout.equals("blocks") ? extras : null;
        java.util.List<ModBlocks.Extra> ex = mb != null ? mb.extras : layout.equals("items") ? itemExtras : null;
        if (ex != null) for (ModBlocks.Extra e : ex)            // HD mod textures raise the atlas scale
            if (e.w > 0) scale = Math.max(scale, Math.min(8, (e.bm.getWidth() + e.w - 1) / e.w));
        int AW = W * scale, AH = (mb != null ? H * ModBlocks.ATLAS_H / ModBlocks.STRIP_Y : H) * scale;
        int[] out = new int[AW * AH];
        if (ex != null) {
            for (ModBlocks.Extra e : ex) {
                int ew = e.w * scale, eh = e.h * scale;
                Bitmap bm = ew == e.bm.getWidth() && eh == e.bm.getHeight() ? e.bm : Bitmap.createScaledBitmap(e.bm, ew, eh, false);
                int[] row = new int[ew];
                for (int y = 0; y < eh && (e.y * scale + y) < AH; y++) {
                    bm.getPixels(row, 0, ew, 0, y, ew, 1);
                    System.arraycopy(row, 0, out, (e.y * scale + y) * AW + e.x * scale, Math.min(ew, AW - e.x * scale));
                }
            }
        }
        for (Object[] j : jobs) {
            Bitmap bm = (Bitmap) j[0];
            int x0 = (Integer) j[1] * scale, y0 = (Integer) j[2] * scale;
            int tw = ((Integer) j[3] - (Integer) j[1]) * scale, th = ((Integer) j[4] - (Integer) j[2]) * scale;
            if (tw <= 0 || th <= 0) continue;
            if (bm.getWidth() != tw || bm.getHeight() < th) {
                Bitmap s = Bitmap.createScaledBitmap(bm, tw, th * Math.max(1, bm.getHeight() / Math.max(1, bm.getWidth())), false);
                bm = s;
            }
            int[] row = new int[tw];
            for (int y = 0; y < th && y0 + y < AH; y++) {
                bm.getPixels(row, 0, tw, 0, y, tw, 1);
                System.arraycopy(row, 0, out, (y0 + y) * AW + x0, Math.min(tw, AW - x0));
            }
        }
        b = new int[][]{{AW, AH}, out};
        built.put(layout, b);
        return b;
    }

    private Bitmap tile(String layout, String rel) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPremultiplied = false;
        String[] paths = useNew
                ? new String[]{"textures/" + layout + "/" + rel, "textures_new/" + layout + "/" + rel}
                : new String[]{"textures/" + layout + "/" + rel};
        // a pack file for the stock path wins; then the new set; then the stock tile
        if (files.hasOverride(paths[0])) return decode(paths[0], o);
        if (useNew) {
            Bitmap b = decode(paths[1], o);
            if (b != null) return b;
        }
        return decode(paths[0], o);
    }

    private Bitmap decode(String path, BitmapFactory.Options o) {
        try {
            byte[] d = files.read(path);
            return BitmapFactory.decodeByteArray(d, 0, d.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** box filter to half size, colour weighted by alpha */
    private static int[] half(int[] src, int w, int h, int nw, int nh) {
        int[] dst = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = Math.min(w - 1, x * 2 + dx), sy = Math.min(h - 1, y * 2 + dy);
                        int p = src[sy * w + sx];
                        int pa = p >>> 24;
                        a += pa;
                        r += ((p >> 16) & 0xff) * pa;
                        g += ((p >> 8) & 0xff) * pa;
                        b += (p & 0xff) * pa;
                    }
                }
                int A = (int) (a / 4);
                int R = a > 0 ? (int) (r / a) : 0, G = a > 0 ? (int) (g / a) : 0, B = a > 0 ? (int) (b / a) : 0;
                dst[y * nw + x] = (A << 24) | (R << 16) | (G << 8) | B;
            }
        }
        return dst;
    }

    /** uncompressed 32-bit TGA, top-left origin */
    private static byte[] tga(int[] px, int w, int h) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(18 + px.length * 4);
        byte[] hdr = new byte[18];
        hdr[2] = 2;
        hdr[12] = (byte) w; hdr[13] = (byte) (w >> 8);
        hdr[14] = (byte) h; hdr[15] = (byte) (h >> 8);
        hdr[16] = 32;
        hdr[17] = 0x28;
        o.write(hdr, 0, 18);
        byte[] row = new byte[w * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                row[x * 4] = (byte) p;
                row[x * 4 + 1] = (byte) (p >> 8);
                row[x * 4 + 2] = (byte) (p >> 16);
                row[x * 4 + 3] = (byte) (p >>> 24);
            }
            o.write(row, 0, row.length);
        }
        return o.toByteArray();
    }
}
