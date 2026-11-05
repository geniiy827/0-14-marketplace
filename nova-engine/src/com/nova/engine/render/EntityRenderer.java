package com.nova.engine.render;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import com.nova.engine.game.Ent;
import com.nova.engine.world.Blocks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Entities of a server, drawn the way 0.14.3 does:
 *  - MobRenderer::renderModel / setupRotations: translate(pos), rotate(180 - bodyRot, Y), death: rotate Z by
 *    min(1, sqrt((deathTime + a - 1) / 20 * 1.6)) * 90, scale(-1, -1, 1), MobRenderer::scale, translate(0, -24 * 1/16
 *    - 0.0078125, 0); walk speed min(1, ...), walk pos pos - speed * (1 - a)
 *  - ModelPart::render: translate(pos / 16), rotate Z, Y, X
 *  - HumanoidModel::setupAnim (constants checked in the disassembly: 0.6662, 1.4, 0.5, 0.09, 0.067, 0.05, 1.2, 0.7,
 *    0.75, 0.4, 0.2, 5), QuadrupedModel / Creeper / Spider / Zombie arms as their setupAnim
 *  - entity shader light (FANCY): (1 + Ny) / 2 * 0.55 + Nx^2 * -0.1 + Nz^2 * 0.1 + 0.45 times TILE_LIGHT_COLOR, hurt
 *    overlay red, fog as the terrain
 *  - item entities: ItemRenderer-style bob sin((age + a) / 10) * 0.1 + 0.1 and spin (age + a) / 20 rad; blocks as a
 *    1/4 cube, falling blocks / primed TNT full size
 *  - name tags (EntityRenderer::renderText): above the head at height + 0.5, black 25 % box, white text
 */
public final class EntityRenderer {
    static final String VS = "#version 300 es\nlayout(location=0) in vec3 aPos; layout(location=1) in vec2 aUv; layout(location=2) in vec3 aN; layout(location=3) in vec4 aCol;\n"
            + "uniform mat4 uVp; uniform mat4 uModel; uniform vec2 uUv; out vec2 vUv; out float vL; out float vD; out vec4 vCol;\n"
            + "void main(){ vec4 w = uModel * vec4(aPos, 1.0); gl_Position = uVp * w; vUv = aUv * uUv; vD = length(w.xyz); vCol = aCol;\n"
            + " vec3 N = normalize((uModel * vec4(aN, 0.0)).xyz);\n"
            + " vL = (1.0 + N.y) * 0.5 * (1.0 - 0.45) + N.x * N.x * -0.1 + N.z * N.z * 0.1 + 0.45; }\n";
    static final String FS = "#version 300 es\nprecision mediump float; uniform sampler2D uTex; uniform vec3 uTile; uniform vec4 uOverlay;\n"
            + "uniform vec3 uFog; uniform vec2 uFogR; in vec2 vUv; in float vL; in float vD; in vec4 vCol; out vec4 o;\n"
            + "void main(){ vec4 c = texture(uTex, vUv) * vCol; if (c.a < 0.5) discard; vec3 col = c.rgb * vL * uTile;\n"
            + " col = mix(col, uOverlay.rgb, uOverlay.a);\n"
            + " float f = clamp((vD - uFogR.x) / max(uFogR.y - uFogR.x, 0.001), 0.0, 1.0); o = vec4(mix(col, uFog, f), 1.0); }\n";

    private final EntityModels models = new EntityModels();
    private AssetManager am;
    private int prog, uVp, uModel, uUv, uTex, uTile, uOverlay, uFog, uFogR;
    private final Map<String, Integer> textures = new HashMap<String, Integer>();
    private final Map<Integer, int[]> texSize = new HashMap<Integer, int[]>();
    private final Map<Long, Integer> skins = new HashMap<Long, Integer>();
    private final float[] m = new float[16], t = new float[16], vp = new float[16];
    private int cubeVbo;
    private FloatBuffer cubeBuf;
    public int drawn;

    public void init(AssetManager am) {
        this.am = am;
        models.load(am);
        prog = Gl.program(VS, FS);
        uVp = GLES30.glGetUniformLocation(prog, "uVp");
        uModel = GLES30.glGetUniformLocation(prog, "uModel");
        uUv = GLES30.glGetUniformLocation(prog, "uUv");
        uTex = GLES30.glGetUniformLocation(prog, "uTex");
        uTile = GLES30.glGetUniformLocation(prog, "uTile");
        uOverlay = GLES30.glGetUniformLocation(prog, "uOverlay");
        uFog = GLES30.glGetUniformLocation(prog, "uFog");
        uFogR = GLES30.glGetUniformLocation(prog, "uFogR");
        cubeVbo = Gl.buffer();
        cubeBuf = Gl.floats(36 * 12);
    }

    // ------------------------------------------------------------------ what each type looks like
    /** {geometry, texture, scale} by EntityType (EntityTypeToString, checked in the emulator) */
    static Object[] look(int type) {
        switch (type) {
            case 10: return new Object[]{"chicken", "images/mob/chicken.png", 1f};
            case 11: return new Object[]{"geometry.cow", "images/mob/cow.png", 1f};
            case 12: return new Object[]{"geometry.pig", "images/mob/pig.png", 1f};
            case 13: return new Object[]{"geometry.sheep", "images/mob/sheep.tga", 1f};
            case 14: return new Object[]{"geometry.wolf", "images/mob/wolf.png", 1f};
            case 15: return new Object[]{"geometry.villager", "images/mob/villager/farmer.png", 1f};
            case 16: return new Object[]{"geometry.cow", "images/mob/mooshroom.png", 1f};
            case 17: return new Object[]{"geometry.squid", "images/mob/squid.png", 1f};
            case 18: return new Object[]{"rabbit", "images/mob/rabbit/brown.png", 1f};
            case 19: return new Object[]{"geometry.bat", "images/mob/bat.png", 0.35f};
            case 20: return new Object[]{"geometry.irongolem", "images/mob/iron_golem.png", 1f};
            case 21: return new Object[]{"geometry.snowgolem", "images/mob/snow_golem.png", 1f};
            case 22: return new Object[]{"geometry.ocelot", "images/mob/cat/ocelot.png", 1f};
            case 32: return new Object[]{"geometry.zombie", "images/mob/zombie.png", 1f};
            case 33: return new Object[]{"geometry.creeper", "images/mob/creeper.png", 1f};
            case 34: return new Object[]{"geometry.skeleton", "images/mob/skeleton.png", 1f};
            case 35: return new Object[]{"geometry.spider", "images/mob/spider.tga", 1f};
            case 36: return new Object[]{"geometry.pigzombie", "images/mob/pigzombie.png", 1f};
            case 37: return new Object[]{"slime", "images/mob/slime.png", 1f};
            case 38: return new Object[]{"geometry.enderman", "images/mob/enderman.tga", 1f};
            case 39: return new Object[]{"silverfish", "images/mob/silverfish.png", 1f};
            case 40: return new Object[]{"geometry.spider", "images/mob/cave_spider.tga", 0.7f};
            case 41: return new Object[]{"geometry.ghast", "images/mob/ghast.png", 4.5f};
            case 42: return new Object[]{"magma_cube", "images/mob/magmacube.tga", 1f};
            case 43: return new Object[]{"blaze", "images/mob/blaze.tga", 1f};
            case 44: return new Object[]{"geometry.zombie.villager", "images/mob/zombie_villager/zombie_villager.png", 1f};
            case 45: return new Object[]{"geometry.villager.witch", "images/mob/witch.png", 1f};
            case Ent.PLAYER: return new Object[]{"geometry.humanoid.custom", "images/mob/skins/Base/steve.png", 1f};
            default: return null;
        }
    }

    /** bounding box {width, height} (Entity::setSize of each mob) for picking and name tags */
    public static float[] size(int type) {
        switch (type) {
            case 10: return new float[]{0.4f, 0.7f};
            case 11: case 13: case 16: return new float[]{0.9f, 1.3f};
            case 12: return new float[]{0.9f, 0.9f};
            case 14: return new float[]{0.6f, 0.8f};
            case 17: return new float[]{0.95f, 0.95f};
            case 18: return new float[]{0.4f, 0.5f};
            case 19: return new float[]{0.5f, 0.9f};
            case 20: return new float[]{1.4f, 2.9f};
            case 21: return new float[]{0.7f, 1.9f};
            case 22: return new float[]{0.6f, 0.7f};
            case 35: return new float[]{1.4f, 0.9f};
            case 37: case 42: return new float[]{1.02f, 1.02f};
            case 38: return new float[]{0.6f, 2.9f};
            case 39: return new float[]{0.4f, 0.3f};
            case 40: return new float[]{0.7f, 0.5f};
            case 41: return new float[]{4f, 4f};
            case Ent.ITEM: return new float[]{0.25f, 0.25f};
            case Ent.FALLING: case Ent.TNT: return new float[]{0.98f, 0.98f};
            default: return new float[]{0.6f, 1.8f};
        }
    }

    private int texture(String path) {
        Integer k = textures.get(path);
        if (k != null) return k;
        int id = 0;
        try {
            byte[] d = readAll(am.open(path));
            if (path.endsWith(".tga")) {
                int[] px = Atlas.tga(d);
                if (px != null) { id = upload(px[0], px[1], px, 2); texSize.put(id, new int[]{px[0], px[1]}); }
            } else {
                Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
                id = glTex();
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
                texSize.put(id, new int[]{b.getWidth(), b.getHeight()});
                b.recycle();
            }
        } catch (Exception e) { android.util.Log.w("NOVA", "texture " + path + ": " + e); }
        textures.put(path, id);
        return id;
    }

    private static int glTex() {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** ARGB ints (from offset) -> RGBA texture */
    private static int upload(int w, int h, int[] px, int off) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < w * h; i++) { int c = px[off + i]; b.put((byte) (c >> 16)); b.put((byte) (c >> 8)); b.put((byte) c); b.put((byte) (c >>> 24)); }
        b.position(0);
        int id = glTex();
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b);
        return id;
    }

    /** skin of a player: raw RGBA bytes 64 x 32 (8192) or 64 x 64 (16384) as PlayerListPacket sends them */
    private int skin(Ent e) {
        if (e.skin == null || (e.skin.length != 8192 && e.skin.length != 16384)) return texture("images/mob/skins/Base/steve.png");
        Integer k = skins.get(e.id);
        if (k != null) return k;
        int h = e.skin.length / 256;
        ByteBuffer b = ByteBuffer.allocateDirect(e.skin.length);
        b.put(e.skin); b.position(0);
        int id = glTex();
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 64, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b);
        skins.put(e.id, id);
        texSize.put(id, new int[]{64, h});
        return id;
    }

    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int k;
        while ((k = in.read(b)) > 0) o.write(b, 0, k);
        in.close();
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ drawing
    public interface Light { void color(double x, double y, double z, float[] out); }

    private final float[] tile = new float[3];

    /** vpRel: projection * view without the camera translation; positions are made camera-relative */
    public void render(Collection<Ent> ents, float[] vpRel, double cx, double cy, double cz, float a, Light light, float[] fog, float fogStart, float fogEnd, Atlas atlas) {
        drawn = 0;
        if (ents.isEmpty()) return;
        GLES30.glUseProgram(prog);
        GLES30.glUniformMatrix4fv(uVp, 1, false, vpRel, 0);
        GLES30.glUniform1i(uTex, 0);
        GLES30.glUniform3f(uFog, fog[0], fog[1], fog[2]);
        GLES30.glUniform2f(uFogR, fogStart, fogEnd);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glEnableVertexAttribArray(2);
        for (Ent e : ents) {
            double x = e.ox + (e.x - e.ox) * a, y = e.oy + (e.y - e.oy) * a - e.heightOffset(), z = e.oz + (e.z - e.oz) * a;
            double dx = x - cx, dy = y - cy, dz = z - cz;
            if (dx * dx + dy * dy + dz * dz > fogEnd * fogEnd + 64) continue;
            light.color(x, y + 0.5, z, tile);
            GLES30.glUniform3f(uTile, tile[0], tile[1], tile[2]);
            if (e.hurt > 0 || e.death > 0) GLES30.glUniform4f(uOverlay, 1f, 0f, 0f, 0.4f);
            else GLES30.glUniform4f(uOverlay, 0f, 0f, 0f, 0f);
            if (e.type == Ent.ITEM || e.type == Ent.FALLING || e.type == Ent.TNT) { blockEntity(e, (float) dx, (float) dy, (float) dz, a, atlas); continue; }
            Object[] lk = look(e.type);
            if (lk == null) continue;
            String geo = (String) lk[0];
            if (e.type == Ent.PLAYER && e.skin != null && e.skin.length == 8192) geo = "geometry.humanoid";
            else if (e.type == Ent.PLAYER && e.skinName != null && e.skinName.toLowerCase().contains("slim")) geo = "geometry.humanoid.customSlim";
            if (!models.has(geo)) continue;
            EntityModels.Model mo = models.model(geo);
            int tex = e.type == Ent.PLAYER ? skin(e) : texture((String) lk[1]);
            if (tex == 0) continue;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            int[] ts = texSize.get(tex);
            float uw = mo.tw > 0 ? mo.tw : ts != null ? ts[0] : 64, uh = mo.th > 0 ? mo.th : ts != null ? ts[1] : 32;
            GLES30.glUniform2f(uUv, 1f / uw, 1f / uh);
            float body = e.oyaw + wrap(e.yaw - e.oyaw) * a;
            float head = e.ohead + wrap(e.head - e.ohead) * a;
            float pitch = e.opitch + (e.pitch - e.opitch) * a;
            float speed = Math.min(1f, e.owalkSpeed + (e.walkSpeed - e.owalkSpeed) * a);
            float pos = e.walkPos - e.walkSpeed * (1 - a);
            float bob = e.age + a;
            float att = e.swinging ? Math.max(0, (e.swingT + a) / 6f) : 0f;
            anim(e.type, mo, pos, speed, bob, wrap(head - body), pitch, att, e.itemId != 0);
            // MobRenderer::setupRotations + renderModel
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, (float) dx, (float) dy, (float) dz);
            Matrix.rotateM(m, 0, 180f - body, 0, 1, 0);
            if (e.death > 0) {
                float f = (float) Math.sqrt(Math.max(0, (e.death + a - 1f) / 20f * 1.6f));
                Matrix.rotateM(m, 0, Math.min(1f, f) * 90f, 0, 0, 1);
            }
            float sc = (Float) lk[2];
            Matrix.scaleM(m, 0, -sc, -sc, sc);
            Matrix.translateM(m, 0, 0, -24f / 16f - 0.0078125f, 0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mo.vbo);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 32, 12);
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 32, 20);
            GLES30.glDisableVertexAttribArray(3);
            GLES30.glVertexAttrib4f(3, 1, 1, 1, 1);
            for (EntityModels.Part p : mo.parts) {
                if (p.never || p.count == 0) continue;
                System.arraycopy(m, 0, t, 0, 16);
                Matrix.translateM(t, 0, p.px / 16f, p.py / 16f, p.pz / 16f);
                if (p.rz != 0) Matrix.rotateM(t, 0, (float) Math.toDegrees(p.rz), 0, 0, 1);
                if (p.ry != 0) Matrix.rotateM(t, 0, (float) Math.toDegrees(p.ry), 0, 1, 0);
                if (p.rx != 0) Matrix.rotateM(t, 0, (float) Math.toDegrees(p.rx), 1, 0, 0);
                GLES30.glUniformMatrix4fv(uModel, 1, false, t, 0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, p.first, p.count);
            }
            drawn++;
        }
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
    }

    private static float wrap(float d) { d %= 360f; if (d >= 180f) d -= 360f; if (d < -180f) d += 360f; return d; }

    private static final float PI = (float) Math.PI;
    private static float cos(float v) { return (float) Math.cos(v); }
    private static float sin(float v) { return (float) Math.sin(v); }

    /** the setupAnim of each model (legacy angles, radians) */
    public static void anim(int type, EntityModels.Model mo, float t, float s, float bob, float headYaw, float pitch, float att, boolean holding) {
        for (EntityModels.Part p : mo.parts) p.reset();
        float hy = headYaw / (180f / PI), hx = pitch / (180f / PI);
        EntityModels.Part head = mo.part("head");
        if (head != null) { head.ry = hy; head.rx = hx; }
        for (String n : new String[]{"hat", "nose"}) { EntityModels.Part q = mo.part(n); if (q != null && head != null) { q.ry = head.ry; q.rx = head.rx; } }
        boolean humanoid = mo.part("rightArm") != null && mo.part("leftLeg") != null;
        if (humanoid) {
            EntityModels.Part ra = mo.part("rightArm"), la = mo.part("leftArm"), rl = mo.part("rightLeg"), ll = mo.part("leftLeg"), bd = mo.part("body");
            ra.rx = cos(t * 0.6662f + PI) * 2f * s * 0.5f;
            la.rx = cos(t * 0.6662f) * 2f * s * 0.5f;
            ra.rz = 0; la.rz = 0;
            rl.rx = cos(t * 0.6662f) * 1.4f * s; ll.rx = cos(t * 0.6662f + PI) * 1.4f * s;
            rl.ry = 0; ll.ry = 0;
            if (holding) ra.rx = ra.rx * 0.5f - PI / 10f;
            ra.ry = 0; la.ry = 0;
            if (att > 0) {                                      // HumanoidModel::setupAnim attack swing
                float by = sin((float) Math.sqrt(att) * PI * 2f) * 0.2f;
                if (bd != null) bd.ry = by;
                ra.pz = sin(by) * 5f; ra.px = -cos(by) * 5f;
                la.pz = -sin(by) * 5f; la.px = cos(by) * 5f;
                ra.ry += by; la.ry += by; la.rx += by;
                float f1 = 1f - att; f1 *= f1; f1 *= f1; f1 = 1f - f1;
                float f2 = sin(f1 * PI), f3 = sin(att * PI) * -((head != null ? head.rx : 0) - 0.7f) * 0.75f;
                ra.rx -= f2 * 1.2f + f3;
                ra.ry += by * 2f;
                ra.rz = sin(att * PI) * -0.4f;
            }
            boolean zombieArms = type == 32 || type == 34 || type == 36 || type == 44;
            if (zombieArms) {                                   // ZombieModel / SkeletonModel: arms held forward
                float f6 = sin(att * PI), f7 = sin((1f - (1f - att) * (1f - att)) * PI);
                ra.rz = 0; la.rz = 0;
                ra.ry = -(0.1f - f6 * 0.6f); la.ry = 0.1f - f6 * 0.6f;
                ra.rx = -PI / 2f; la.rx = -PI / 2f;
                ra.rx -= f6 * 1.2f - f7 * 0.4f; la.rx -= f6 * 1.2f - f7 * 0.4f;
            }
            ra.rz += cos(bob * 0.09f) * 0.05f + 0.05f; la.rz -= cos(bob * 0.09f) * 0.05f + 0.05f;
            ra.rx += sin(bob * 0.067f) * 0.05f; la.rx -= sin(bob * 0.067f) * 0.05f;
            // the second layer follows its limb (geometry.humanoid.custom)
            copy(mo, "rightSleeve", ra); copy(mo, "leftSleeve", la); copy(mo, "rightPants", rl); copy(mo, "leftPants", ll);
            if (bd != null) copy(mo, "jacket", bd);
            return;
        }
        EntityModels.Part l0 = mo.part("leg0"), l1 = mo.part("leg1"), l2 = mo.part("leg2"), l3 = mo.part("leg3");
        if (type == 35 || type == 40) {                         // SpiderModel::setupAnim
            float f6 = PI / 4f, f8 = PI / 8f;
            float[] zr = {-f6, f6, -f6 * 0.74f, f6 * 0.74f, -f6 * 0.74f, f6 * 0.74f, -f6, f6};
            float[] yr = {f8 * 2, -f8 * 2, f8, -f8, -f8, f8, -f8 * 2, f8 * 2};
            float[] ph = {0, PI, PI / 2, PI * 1.5f};
            for (int i = 0; i < 8; i++) {
                EntityModels.Part l = mo.part("leg" + i);
                if (l == null) continue;
                int k = i / 2; float sg = (i & 1) == 0 ? 1 : -1;
                float fy = -(cos(t * 0.6662f * 2f + ph[k]) * 0.4f) * s;
                float fz = Math.abs(sin(t * 0.6662f + ph[k]) * 0.4f) * s;
                l.rz = zr[i] + sg * fz; l.ry = yr[i] + sg * fy;
            }
            return;
        }
        // QuadrupedModel::setupAnim: body.xRot = PI * 0.5 (cow, pig, sheep, mooshroom); WolfModel / OcelotModel lay
        // their body (and the wolf's mane) the same way
        if (type == 11 || type == 12 || type == 13 || type == 16 || type == 14 || type == 22) {
            EntityModels.Part bd = mo.part("body"); if (bd != null) bd.rx = PI * 0.5f;
            EntityModels.Part ub = mo.part("upperBody"); if (ub != null && type == 14) ub.rx = PI * 0.5f;
        }
        if (l0 != null && l1 != null && l2 != null && l3 != null) {   // QuadrupedModel / CreeperModel / wolf
            l0.rx = cos(t * 0.6662f) * 1.4f * s;
            l1.rx = cos(t * 0.6662f + PI) * 1.4f * s;
            l2.rx = cos(t * 0.6662f + PI) * 1.4f * s;
            l3.rx = cos(t * 0.6662f) * 1.4f * s;
        } else if (l0 != null && l1 != null) {                  // villager / golems: two legs
            l0.rx = cos(t * 0.6662f) * 1.4f * s * 0.5f;
            l1.rx = cos(t * 0.6662f + PI) * 1.4f * s * 0.5f;
        }
        if (type == 10 && mo.parts.size() >= 8) {               // ChickenModel: head 0..2, legs 4 5, wings 6 7
            for (int i = 0; i < 3; i++) { mo.parts.get(i).rx = hx; mo.parts.get(i).ry = hy; }
            mo.parts.get(4).rx = cos(t * 0.6662f) * 1.4f * s;
            mo.parts.get(5).rx = cos(t * 0.6662f + PI) * 1.4f * s;
        }
    }

    private static void copy(EntityModels.Model mo, String n, EntityModels.Part from) {
        EntityModels.Part q = mo.part(n);
        if (q == null) return;
        q.rx = from.rx; q.ry = from.ry; q.rz = from.rz; q.px = from.px; q.py = from.py; q.pz = from.pz;
    }

    // ------------------------------------------------------------------ dropped items and falling blocks
    private void blockEntity(Ent e, float dx, float dy, float dz, float a, Atlas atlas) {
        int id = e.itemId;
        if (id <= 0 || id >= Blocks.MAX || !Blocks.exists[id]) {
            if (e.type != Ent.ITEM) return;
            id = 0;
        }
        Matrix.setIdentityM(m, 0);
        float s;
        if (e.type == Ent.ITEM) {
            float bobY = sin((e.age + a) / 10f) * 0.1f + 0.1f;
            Matrix.translateM(m, 0, dx, dy + bobY + 0.125f, dz);
            Matrix.rotateM(m, 0, (float) Math.toDegrees((e.age + a) / 20f), 0, 1, 0);
            s = 0.25f;
        } else {
            Matrix.translateM(m, 0, dx, dy + 0.5f, dz);
            s = 1f;
        }
        Matrix.scaleM(m, 0, s, s, s);
        GLES30.glUniformMatrix4fv(uModel, 1, false, m, 0);
        GLES30.glUniform2f(uUv, 1f, 1f);
        float[] uv = atlas.slotUv;
        cubeBuf.position(0);
        // faces: 0 down 1 up 2 north 3 south 4 west 5 east
        float[][][] F = {
                {{-.5f, -.5f, .5f}, {.5f, -.5f, .5f}, {.5f, -.5f, -.5f}, {-.5f, -.5f, -.5f}},
                {{-.5f, .5f, -.5f}, {.5f, .5f, -.5f}, {.5f, .5f, .5f}, {-.5f, .5f, .5f}},
                {{.5f, .5f, -.5f}, {-.5f, .5f, -.5f}, {-.5f, -.5f, -.5f}, {.5f, -.5f, -.5f}},
                {{-.5f, .5f, .5f}, {.5f, .5f, .5f}, {.5f, -.5f, .5f}, {-.5f, -.5f, .5f}},
                {{-.5f, .5f, -.5f}, {-.5f, .5f, .5f}, {-.5f, -.5f, .5f}, {-.5f, -.5f, -.5f}},
                {{.5f, .5f, .5f}, {.5f, .5f, -.5f}, {.5f, -.5f, -.5f}, {.5f, -.5f, .5f}}};
        float[][] N = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        int[] ord = {0, 1, 2, 0, 2, 3};
        for (int f = 0; f < 6; f++) {
            int slot = id == 0 ? 0 : Blocks.texFor(id, e.itemData, f) * 4;
            if (slot + 3 >= uv.length) slot = 0;
            float u0 = uv[slot], v0 = uv[slot + 1], u1 = uv[slot + 2], v1 = uv[slot + 3];
            float[][] tuv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            int tint = id == 0 ? 0xFFFFFF : Blocks.tint[id][f];
            if (tint < 0) tint = tint == Blocks.TINT_FOLIAGE ? 0x59AE30 : tint == Blocks.TINT_WATER ? 0x3F76E4 : 0x79C05A;
            float r = ((tint >> 16) & 255) / 255f, g = ((tint >> 8) & 255) / 255f, b = (tint & 255) / 255f;
            for (int k : ord) {
                cubeBuf.put(F[f][k]); cubeBuf.put(tuv[k]); cubeBuf.put(N[f]); cubeBuf.put(r); cubeBuf.put(g); cubeBuf.put(b); cubeBuf.put(1f);
            }
        }
        cubeBuf.position(0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas.texture);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubeVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 36 * 12 * 4, cubeBuf, GLES30.GL_STREAM_DRAW);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 48, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 48, 12);
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 48, 20);
        GLES30.glEnableVertexAttribArray(3);
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 48, 32);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 36);
        GLES30.glDisableVertexAttribArray(3);
        drawn++;
    }

    // ------------------------------------------------------------------ name tags (2D, after the world)
    private final float[] v4 = new float[4], o4 = new float[4];

    /** screen position {x, y, pixel scale} of a point (camera-relative) or null when behind */
    public float[] project(float[] vpRel, float x, float y, float z, int w, int h) {
        v4[0] = x; v4[1] = y; v4[2] = z; v4[3] = 1;
        Matrix.multiplyMV(o4, 0, vpRel, 0, v4, 0);
        if (o4[3] <= 0.1f) return null;
        return new float[]{(o4[0] / o4[3] * 0.5f + 0.5f) * w, (0.5f - o4[1] / o4[3] * 0.5f) * h, o4[3]};
    }
}
