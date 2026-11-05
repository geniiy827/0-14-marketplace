package com.nova.engine.render;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity geometry as 0.14.3 builds it.
 *  - GeometryGroup::loadModelPackFile reads images/mob/skins/Base/{Vanilla,Mobs}.json; a key "name:parent" starts
 *    from a copy of the parent (Geometry(name, Geometry const&)), then Geometry::_parseNodes applies the bones:
 *    pivot / rotation / mirror / neverRender / inflate override, "reset" drops the inherited cubes, cubes are added.
 *  - ModelPart::load: pos = pivot, rot = rotation * PI / 180, box origin = (o.x - p.x, p.y - o.y - size.y, o.z - p.z),
 *    then pos.y = 24 - pivot.y: legacy model space (y down, 24 px = feet).
 *  - The models still built in code (chicken, rabbit, slime, magma cube, silverfish, blaze, boat, minecart) come from
 *    running their constructors in the emulator (tools/emu_models.py -> assets/nova/native_models.json).
 *  - ModelPart::compile -> the standard box mapping (Java ModelBox): 6 quads with (u, v) offsets d / w / h.
 */
public final class EntityModels {
    public static final class Cube { float[] o, s; float[] uv; float inflate; boolean mirror; }
    public static final class Bone {
        public String name; float[] pivot = new float[3], rot = new float[3]; boolean mirror, never; float inflate;
        final List<Cube> cubes = new ArrayList<Cube>();
        Bone copy() { Bone b = new Bone(); b.name = name; b.pivot = pivot.clone(); b.rot = rot.clone(); b.mirror = mirror; b.never = never; b.inflate = inflate; b.cubes.addAll(cubes); return b; }
    }
    public static final class Geo { int tw, th;   /* 0 = not given: the texture size */ final LinkedHashMap<String, Bone> bones = new LinkedHashMap<String, Bone>(); }

    /** A compiled model: per part a vertex range in one VBO (x y z u v nx ny nz, legacy space / 16, uv in texels; tw/th 0 = texture size) */
    public static final class Model {
        public final List<Part> parts = new ArrayList<Part>();
        public int vbo, verts, tw, th; public FloatBuffer data;
        public Part part(String n) { for (Part p : parts) if (p.name.equals(n)) return p; return null; }
    }
    public static final class Part {
        public String name; public float x, y, z, bx, by, bz;   // pos (px, legacy) and its rotation (rad) from the file
        public float rx, ry, rz, px, py, pz;                    // animated values, set by setupAnim each frame
        public int first, count; public boolean never;
        public void reset() { rx = bx; ry = by; rz = bz; px = x; py = y; pz = z; }
    }

    private final Map<String, Geo> geos = new HashMap<String, Geo>();
    private final Map<String, Model> models = new HashMap<String, Model>();
    private final Map<String, Object> natives = new HashMap<String, Object>();

    public void load(AssetManager am) {
        for (String f : new String[]{"images/mob/skins/Base/Vanilla.json", "images/mob/skins/Base/Mobs.json", "nova/native_models.json"}) {
            try { loadText(f, read(am, f)); } catch (Exception e) { android.util.Log.w("NOVA", f, e); }
        }
    }

    public void loadText(String file, String text) {
        Map<String, Object> m = LooseJson.obj(LooseJson.parse(text));
        if (file.endsWith("native_models.json")) { if (m != null) natives.putAll(m); }
        else file(m);
    }

    static String read(AssetManager am, String f) throws Exception {
        InputStream in = am.open(f); ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int k;
        while ((k = in.read(b)) > 0) o.write(b, 0, k);
        in.close();
        return new String(o.toByteArray(), "UTF-8");
    }

    private void file(Map<String, Object> m) {
        if (m == null) return;
        // parents may come later in the file: resolve repeatedly
        List<String> keys = new ArrayList<String>(m.keySet());
        for (int pass = 0; pass < 4 && !keys.isEmpty(); pass++) {
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                String name = k, parent = null;
                int c = k.indexOf(':');
                if (c > 0) { name = k.substring(0, c); parent = k.substring(c + 1); }
                if (parent != null && !geos.containsKey(parent)) continue;
                Geo g = new Geo();
                if (parent != null) {
                    Geo p = geos.get(parent); g.tw = p.tw; g.th = p.th;
                    for (Bone b : p.bones.values()) g.bones.put(b.name, b.copy());
                }
                parse(g, LooseJson.obj(m.get(k)));
                geos.put(name, g);
                keys.remove(i--);
            }
        }
    }

    private static void parse(Geo g, Map<String, Object> o) {
        if (o == null) return;
        if (o.containsKey("texturewidth")) g.tw = (int) LooseJson.num(o.get("texturewidth"), 0);
        if (o.containsKey("textureheight")) g.th = (int) LooseJson.num(o.get("textureheight"), 0);
        List<Object> bones = LooseJson.arr(o.get("bones"));
        if (bones == null) return;
        for (Object bo : bones) {
            Map<String, Object> b = LooseJson.obj(bo);
            if (b == null) continue;
            String n = String.valueOf(b.get("name"));
            Bone bone = g.bones.get(n);
            if (bone == null) { bone = new Bone(); bone.name = n; g.bones.put(n, bone); }
            if (Boolean.TRUE.equals(b.get("reset"))) bone.cubes.clear();
            if (b.containsKey("pivot")) bone.pivot = LooseJson.vec(b.get("pivot"), 3);
            if (b.containsKey("rotation")) bone.rot = LooseJson.vec(b.get("rotation"), 3);
            if (b.containsKey("mirror")) bone.mirror = Boolean.TRUE.equals(b.get("mirror"));
            if (b.containsKey("neverRender")) bone.never = Boolean.TRUE.equals(b.get("neverRender"));
            if (b.containsKey("inflate")) bone.inflate = LooseJson.num(b.get("inflate"), 0);
            List<Object> cubes = LooseJson.arr(b.get("cubes"));
            if (cubes != null) for (Object co : cubes) {
                Map<String, Object> cm = LooseJson.obj(co);
                if (cm == null) continue;
                Cube cu = new Cube();
                cu.o = LooseJson.vec(cm.get("origin"), 3); cu.s = LooseJson.vec(cm.get("size"), 3); cu.uv = LooseJson.vec(cm.get("uv"), 2);
                cu.inflate = LooseJson.num(cm.get("inflate"), Float.NaN);
                cu.mirror = Boolean.TRUE.equals(cm.get("mirror"));
                bone.cubes.add(cu);
            }
        }
    }

    public boolean has(String geometry) { return geos.containsKey(geometry) || natives.containsKey(geometry); }

    /** compiled model of a geometry (GL thread) */
    public Model model(String geometry) {
        Model m = models.get(geometry);
        if (m != null) return m;
        m = compile(geometry);
        FloatBuffer fb = m.data;
        m.vbo = Gl.buffer();
        android.opengl.GLES30.glBindBuffer(android.opengl.GLES30.GL_ARRAY_BUFFER, m.vbo);
        android.opengl.GLES30.glBufferData(android.opengl.GLES30.GL_ARRAY_BUFFER, Math.max(4, m.verts * 32), fb, android.opengl.GLES30.GL_STATIC_DRAW);
        m.data = null;
        models.put(geometry, m);
        return m;
    }

    /** the vertices of a geometry without GL (tests use it too) */
    public Model compile(String geometry) {
        FloatBuffer fb = java.nio.ByteBuffer.allocateDirect(36 * 8 * 64 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        Model m = new Model();
        Geo g = geos.get(geometry);
        if (g != null) {
            m.tw = g.tw; m.th = g.th;
            for (Bone b : g.bones.values()) {
                Part p = new Part(); p.name = b.name; p.never = b.never;
                p.x = b.pivot[0]; p.y = 24 - b.pivot[1]; p.z = b.pivot[2];
                float k = (float) Math.PI / 180f;
                p.bx = b.rot[0] * k; p.by = b.rot[1] * k; p.bz = b.rot[2] * k;
                p.first = fb.position() / 8;
                for (Cube c : b.cubes) {
                    float inf = Float.isNaN(c.inflate) ? b.inflate : c.inflate;
                    fb = room(fb);
                    box(fb, c.o[0] - b.pivot[0], b.pivot[1] - c.o[1] - c.s[1], c.o[2] - b.pivot[2], c.s[0], c.s[1], c.s[2],
                            c.uv[0], c.uv[1], inf, b.mirror ^ c.mirror, 1, 1);
                }
                p.count = fb.position() / 8 - p.first;
                p.reset();
                m.parts.add(p);
            }
        } else {
            List<Object> parts = LooseJson.arr(natives.get(geometry));
            int idx = 0;
            if (parts != null) for (Object po : parts) {
                Map<String, Object> pm = LooseJson.obj(po);
                if (pm == null || Boolean.FALSE.equals(pm.get("registered"))) continue;
                float[] ts = LooseJson.vec(pm.get("texsize"), 2);
                m.tw = (int) ts[0]; m.th = (int) ts[1];
                Part p = new Part(); p.name = "part" + idx++;
                float[] pos = LooseJson.vec(pm.get("pos"), 3), rot = LooseJson.vec(pm.get("rot"), 3);
                p.x = pos[0]; p.y = pos[1]; p.z = pos[2]; p.bx = rot[0]; p.by = rot[1]; p.bz = rot[2];
                p.first = fb.position() / 8;
                List<Object> boxes = LooseJson.arr(pm.get("boxes"));
                if (boxes != null) for (Object bo : boxes) {
                    Map<String, Object> bm = LooseJson.obj(bo);
                    float[] o = LooseJson.vec(bm.get("origin"), 3), s = LooseJson.vec(bm.get("size"), 3), uv = LooseJson.vec(bm.get("uv"), 2);
                    fb = room(fb);
                    box(fb, o[0], o[1], o[2], s[0], s[1], s[2], uv[0], uv[1], LooseJson.num(bm.get("inflate"), 0), Boolean.TRUE.equals(bm.get("mirror")), 1, 1);
                }
                p.count = fb.position() / 8 - p.first;
                p.reset();
                m.parts.add(p);
            }
        }
        m.verts = fb.position() / 8;
        fb.position(0);
        m.data = fb;
        return m;
    }

    private static FloatBuffer room(FloatBuffer fb) {
        if (fb.remaining() >= 36 * 8) return fb;
        FloatBuffer n = java.nio.ByteBuffer.allocateDirect(fb.capacity() * 8).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        fb.flip(); n.put(fb);
        return n;
    }

    /**
     * Java ModelBox: corners v0..v7 (x mirrored swaps x0 / x1), quads +x, -x, -y (top), +y (bottom), -z (front), +z;
     * TexturedQuad(u1, v1, u2, v2) gives the 4 corners (u2, v1) (u1, v1) (u1, v2) (u2, v2). Units / 16.
     */
    public static void box(FloatBuffer fb, float x, float y, float z, float w, float h, float d, float u, float v, float g, boolean mirror, float tw, float th) {
        float x0 = x - g, y0 = y - g, z0 = z - g, x1 = x + w + g, y1 = y + h + g, z1 = z + d + g;
        if (mirror) { float t = x0; x0 = x1; x1 = t; }
        float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        w = (float) Math.ceil(w); h = (float) Math.ceil(h); d = (float) Math.ceil(d);
        quad(fb, c, 5, 1, 2, 6, u + d + w, v + d, u + d + w + d, v + d + h, tw, th, mirror ? -1 : 1, 0, 0);
        quad(fb, c, 0, 4, 7, 3, u, v + d, u + d, v + d + h, tw, th, mirror ? 1 : -1, 0, 0);
        quad(fb, c, 5, 4, 0, 1, u + d, v, u + d + w, v + d, tw, th, 0, -1, 0);
        quad(fb, c, 2, 3, 7, 6, u + d + w, v + d, u + d + w + w, v, tw, th, 0, 1, 0);
        quad(fb, c, 1, 0, 3, 2, u + d, v + d, u + d + w, v + d + h, tw, th, 0, 0, -1);
        quad(fb, c, 4, 5, 6, 7, u + d + w + d, v + d, u + d + w + d + w, v + d + h, tw, th, 0, 0, 1);
    }

    private static void quad(FloatBuffer fb, float[][] c, int a, int b, int e, int f, float u1, float v1, float u2, float v2, float tw, float th, float nx, float ny, float nz) {
        int[] k = {a, b, e, f};
        float[][] uv = {{u2, v1}, {u1, v1}, {u1, v2}, {u2, v2}};
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int i : order) {
            float[] p = c[k[i]];
            fb.put(p[0] / 16f); fb.put(p[1] / 16f); fb.put(p[2] / 16f);
            fb.put(uv[i][0] / tw); fb.put(uv[i][1] / th);
            fb.put(nx); fb.put(ny); fb.put(nz);
        }
    }
}
