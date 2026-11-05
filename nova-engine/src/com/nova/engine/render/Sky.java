package com.nova.engine.render;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.Matrix;

import com.nova.engine.world.McRandom;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Sky, time of day, fog and the light texture of 0.14.3, ported from the code (no weather yet):
 *  - Dimension::getTimeOfDay(t, a): x = (t % 24000 + a) / 24000 - 0.25 wrapped to 0..1, x += ((1 - (cos(x pi) + 1) / 2) - x) / 3
 *  - Dimension::getSkyColor: Biome::getSkyColor(temperature) = HSB(0.62222224 - t/3 * 0.05, 0.5 + t/3 * 0.1, 1),
 *    t/3 clamped to -1..1, times clamp(cos(tod 2pi) * 2 + 0.5, 0, 1)
 *  - Dimension::getFogColor(b) = (0.6705883 (0.94 b + 0.06), 0.8235295 (0.94 b + 0.06), 0.91 b + 0.09), b = the same clamp
 *  - Dimension::getSunriseColor: when -0.4 <= cos <= 0.4: f = cos / 0.4 * 0.5 + 0.5,
 *    (0.7 + f 0.3, 0.2 + f f 0.7, 0.2, (1 - (1 - sin(f pi)) 0.99)^2)
 *  - Dimension::getSkyDarken(a) = 0.2 + 0.8 (1 - clamp(0.8 - 2 cos, 0, 1)); getStarBrightness = clamp(0.25 - 2 cos, 0, 1)^2 / 2
 *  - Dimension::getCloudColor = (0.1 + 0.9 b, 0.1 + 0.9 b, 0.15 + 0.85 b)
 *  - Dimension::updateLightRamp: ramp[i] = (i/15) / ((1 - i/15) 3 + 1)
 *  - LightTexture::refresh (16 x 16, u = block, v = sky)
 *  - LevelRenderer::renderSky: sky plane (centre (0,128,0) = sky colour x getAmbientBrightness - darkening, ring of
 *    radius 2000 = fog colour, sky.vertex mix), sun (30, at 120, sun.png, additive) and moon (20, moon_phases.png)
 *    rotated sunAngle about X (hidden between 105 and 255 degrees), stars (1500, Random 10842, rotated sunAngle)
 *  - LevelRenderer::renderClouds: clouds.png voxels (TextureTessellator), cells 16 x 4 x 16 at y 128.33, moving
 *    0.02 blocks a tick, colour mix(cloudColour, sunrise, sunrise.a 0.35), alpha 0.7, fading past 0.9 x far
 *  - LevelRenderer::updateFarChunksDistance / _setupFog: fog start / end
 */
public final class Sky {
    // ------------------------------------------------------------------ Dimension
    private static final float PI = (float) Math.PI, TAU = (float) (Math.PI * 2);

    public static float timeOfDay(long time, float a) {
        float x = ((int) (time % 24000L) + a) / 24000f - 0.25f;
        if (x < 0) x += 1;
        if (x > 1) x -= 1;
        float c = (float) Math.cos(x * PI);
        return x + ((1f - (c + 1f) * 0.5f) - x) / 3f;
    }

    static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** LevelRenderer::getFogBrightness and the factor of Dimension::getSkyColor */
    public static float dayBrightness(float tod) { return clamp01((float) Math.cos(tod * TAU) * 2f + 0.5f); }

    public static void fogColor(float b, float[] out) {
        out[0] = 0.6705883f * (b * 0.94f + 0.06f);
        out[1] = 0.8235295f * (b * 0.94f + 0.06f);
        out[2] = b * 0.91f + 0.09f;
    }

    /** sunrise (r g b a) or false */
    public static boolean sunrise(float tod, float[] out) {
        float c = (float) Math.cos(tod * TAU);
        if (c < -0.4f || c > 0.4f) { out[0] = out[1] = out[2] = out[3] = 0; return false; }
        float f = (c * 2.5f + 1f) * 0.5f;
        float s = (float) Math.sin(f * PI);
        float al = 1f - (1f - s) * 0.99f;
        out[0] = f * 0.3f + 0.7f; out[1] = f * f * 0.7f + 0.2f; out[2] = 0.2f; out[3] = al * al;
        return true;
    }

    public static float skyDarken(float tod) {
        float s = clamp01(0.8f - (float) Math.cos(tod * TAU) * 2f);
        return 0.2f + 0.8f * (1f - s);
    }

    public static float starBrightness(float tod) {
        float s = clamp01(0.25f - (float) Math.cos(tod * TAU) * 2f);
        return s * s * 0.5f;
    }

    /** Biome::getSkyColor(temperature): Color::fromHSB */
    public static void biomeSky(float temperature, float[] out) {
        float t = temperature / 3f;
        if (t < -1) t = -1;
        if (t > 1) t = 1;
        int rgb = hsb(0.62222224f - t * 0.05f, 0.5f + t * 0.1f, 1f);
        out[0] = ((rgb >> 16) & 255) / 255f; out[1] = ((rgb >> 8) & 255) / 255f; out[2] = (rgb & 255) / 255f;
    }

    /** Color::fromHSB (the java.awt.Color.HSBtoRGB algorithm) */
    static int hsb(float hue, float sat, float bri) {
        int r = 0, g = 0, b = 0;
        if (sat == 0) { r = g = b = (int) (bri * 255f + 0.5f); }
        else {
            float h = (hue - (float) Math.floor(hue)) * 6f, f = h - (float) Math.floor(h);
            float p = bri * (1f - sat), q = bri * (1f - sat * f), t = bri * (1f - sat * (1f - f));
            switch ((int) h) {
                case 0: r = (int) (bri * 255f + 0.5f); g = (int) (t * 255f + 0.5f); b = (int) (p * 255f + 0.5f); break;
                case 1: r = (int) (q * 255f + 0.5f); g = (int) (bri * 255f + 0.5f); b = (int) (p * 255f + 0.5f); break;
                case 2: r = (int) (p * 255f + 0.5f); g = (int) (bri * 255f + 0.5f); b = (int) (t * 255f + 0.5f); break;
                case 3: r = (int) (p * 255f + 0.5f); g = (int) (q * 255f + 0.5f); b = (int) (bri * 255f + 0.5f); break;
                case 4: r = (int) (t * 255f + 0.5f); g = (int) (p * 255f + 0.5f); b = (int) (bri * 255f + 0.5f); break;
                default: r = (int) (bri * 255f + 0.5f); g = (int) (p * 255f + 0.5f); b = (int) (q * 255f + 0.5f); break;
            }
        }
        return r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------ light texture
    static final float[] RAMP = new float[16];
    static {
        for (int i = 0; i <= 15; i++) {
            float f = i / 15f, g = 1f - f;
            RAMP[i] = clamp01(f / (g * 3f + 1f));
        }
    }

    /** LightTexture::refresh -> 256 RGBA8 (index sky * 16 + block) */
    public static void lightTexture(float skyDarken, float[] sr, byte[] out) {
        float sa = sr[3] * 0.3f;
        float s24 = 0.05f + skyDarken * 0.95f, k = 0.35f + skyDarken * 0.65f;
        for (int i = 0; i < 256; i++) {
            float bl = RAMP[i & 15] * 1.6f, rs = RAMP[i >> 4];
            float base = (1f - sa) * s24 * rs;
            float sr0 = base + sa * sr[0] * rs, sg = base + sa * sr[1] * rs, sb = base + sa * sr[2] * rs;
            float g1 = bl * ((bl * 0.6f + 0.4f) * 0.6f + 0.4f), b1 = bl * (bl * bl * 0.6f + 0.4f);
            float r = bl + sr0 * k, g = g1 + sg * k, b = sb + b1;
            r = r * 0.96f + 0.03f; g = g * 0.96f + 0.03f; b = b * 0.96f + 0.03f;
            r = Math.min(r, 1f); g = Math.min(g, 1f); b = Math.min(b, 1f);      // gamma 0: unchanged
            r = clamp01(r * 0.96f + 0.03f); g = clamp01(g * 0.96f + 0.03f); b = clamp01(b * 0.96f + 0.03f);
            out[i * 4] = (byte) (int) (r * 255f); out[i * 4 + 1] = (byte) (int) (g * 255f);
            out[i * 4 + 2] = (byte) (int) (b * 255f); out[i * 4 + 3] = (byte) 255;
        }
    }

    // ------------------------------------------------------------------ fog distances
    /**
     * LevelRenderer::onViewRadiusChanged + updateFarChunksDistance for a view radius in blocks:
     * far = max(R - delta, 40) (delta 16 above 80, 8 above 64, else R/16); m = clamp(far/2, 48, 160);
     * fade = m (0.5 + ambient 0.5); end = far + fade - m; start = end - clamp((end - 40) / 2, 4, 96).
     * out: far, start, end
     */
    public static void fogDistances(float radiusBlocks, float ambient, boolean underwater, float[] out) {
        float d = radiusBlocks > 64 ? (radiusBlocks > 80 ? 16 : 8) : radiusBlocks * 0.0625f;
        float far = Math.max(radiusBlocks - d, 40f);
        float m = Math.max(48f, Math.min(160f, far * 0.5f));
        float fade = m * (underwater ? 0f : 0.5f + ambient * 0.5f);
        float end = far + fade - m;
        float start = end - Math.max(4f, Math.min(96f, (end - 40f) * 0.5f));
        out[0] = far; out[1] = start; out[2] = end;
    }

    /** LevelRenderer::getAmbientBrightness (its brightness field stays 0): below y 55 0.035, else 0.35 */
    public static float ambient(float eyeY) { return eyeY < 55f ? 0.035f : 0.35f; }

    /** Dimension::getSunIntensity(a, entity, 0.35) with the view vector */
    public static float sunIntensity(float tod, float[] look) {
        float ang = tod * TAU;
        float sx = 0.25f, sy = (float) Math.cos(ang), sz = (float) Math.sin(ang);
        float h = (look[0] * sx + look[1] * sy + look[2] * sz + 1f) * 0.5f;
        float fake = sy < -0.1f ? 0f : sy < 0 ? 1f + sy * 10f : 1f;     // _fakeSunHorizon
        h *= fake;
        if (h > 1f) return 1f;
        if (h < 0.35f) h = 0.35f;
        return (h - 0.35f) / 0.65f;
    }

    // ------------------------------------------------------------------ rendering
    static final String VS_COL = "#version 300 es\nlayout(location=0) in vec3 aPos; layout(location=1) in vec4 aCol;\n"
            + "uniform mat4 uMvp; out vec4 vCol; out vec3 vW; void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vCol = aCol; vW = aPos; }\n";
    static final String FS_COL = "#version 300 es\nprecision mediump float; in vec4 vCol; out vec4 o; void main(){ o = vCol; }\n";
    // stars.fragment: colour.rgb * CURRENT_COLOR.rgb * colour.a, alpha colour.a
    static final String FS_STAR = "#version 300 es\nprecision mediump float; uniform vec4 uCur; in vec4 vCol; out vec4 o;\n"
            + "void main(){ o = vec4(vCol.rgb * uCur.rgb * vCol.a, vCol.a); }\n";
    // cloud.vertex: colour * CURRENT_COLOR, alpha *= 1 - max(length(world) / RENDER_DISTANCE - 0.9, 0)
    static final String VS_CLOUD = "#version 300 es\nlayout(location=0) in vec3 aPos; layout(location=1) in vec4 aCol;\n"
            + "uniform mat4 uMvp; uniform mat4 uWorld; uniform vec4 uCur; uniform float uRd; out vec4 vCol;\n"
            + "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vec4 c = aCol * uCur; vec4 w = uWorld * vec4(aPos, 1.0);\n"
            + " float fog = max(length(w.xyz) / uRd - 0.9, 0.0); c.a *= 1.0 - fog; vCol = c; }\n";
    // uv.vertex + texture_ccolor.fragment
    static final String VS_TEX = "#version 300 es\nlayout(location=0) in vec3 aPos; layout(location=1) in vec2 aUv;\n"
            + "uniform mat4 uMvp; out vec2 vUv; void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv; }\n";
    static final String FS_TEX = "#version 300 es\nprecision mediump float; uniform sampler2D uTex; uniform vec4 uCur; in vec2 vUv; out vec4 o;\n"
            + "void main(){ vec4 d = texture(uTex, vUv); if (d.a < 0.5) discard; o = uCur * d; }\n";

    private int pCol, pStar, pCloud, pTex;
    private int uColMvp, uStarMvp, uStarCur, uCloudMvp, uCloudWorld, uCloudCur, uCloudRd, uTexMvp, uTexCur, uTexTex;
    private int vbo, starVbo, starCount, cloudVbo, cloudCount, sunTex, moonTex, lightTex;
    private byte[] cloudPix;
    private int cloudW, cloudH;
    private final byte[] lightPix = new byte[256 * 4];
    private final ByteBuffer lightBuf = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder());
    private final FloatBuffer fb = ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] m = new float[16], w = new float[16], t = new float[16], mvp = new float[16];

    public int lightTexture() { return lightTex; }

    /** LightTexture::getColorForUV: the light texture colour of a pair (SKY << 8 | BLOCK) */
    public void lightColor(int pair, float[] out) {
        int i = ((pair >> 8) & 15) * 16 + (pair & 15);
        out[0] = (lightPix[i * 4] & 255) / 255f; out[1] = (lightPix[i * 4 + 1] & 255) / 255f; out[2] = (lightPix[i * 4 + 2] & 255) / 255f;
    }

    public void init(AssetManager am) {
        pCol = Gl.program(VS_COL, FS_COL);
        uColMvp = GLES30.glGetUniformLocation(pCol, "uMvp");
        pStar = Gl.program(VS_COL, FS_STAR);
        uStarMvp = GLES30.glGetUniformLocation(pStar, "uMvp");
        uStarCur = GLES30.glGetUniformLocation(pStar, "uCur");
        pCloud = Gl.program(VS_CLOUD, FS_COL);
        uCloudMvp = GLES30.glGetUniformLocation(pCloud, "uMvp");
        uCloudWorld = GLES30.glGetUniformLocation(pCloud, "uWorld");
        uCloudCur = GLES30.glGetUniformLocation(pCloud, "uCur");
        uCloudRd = GLES30.glGetUniformLocation(pCloud, "uRd");
        pTex = Gl.program(VS_TEX, FS_TEX);
        uTexMvp = GLES30.glGetUniformLocation(pTex, "uMvp");
        uTexCur = GLES30.glGetUniformLocation(pTex, "uCur");
        uTexTex = GLES30.glGetUniformLocation(pTex, "uTex");
        vbo = Gl.buffer();
        starVbo = Gl.buffer();
        cloudVbo = Gl.buffer();
        sunTex = tex(am, "images/environment/sun.png", false);
        moonTex = tex(am, "images/environment/moon_phases.png", false);
        try {
            Bitmap b = BitmapFactory.decodeStream(am.open("images/environment/clouds.png"));
            cloudW = b.getWidth(); cloudH = b.getHeight();
            int[] px = new int[cloudW * cloudH];
            b.getPixels(px, 0, cloudW, 0, 0, cloudW, cloudH);
            cloudPix = new byte[px.length];
            for (int i = 0; i < px.length; i++) cloudPix[i] = (byte) (px[i] >>> 24);
        } catch (Exception e) { cloudPix = null; }
        int[] id = new int[1];
        GLES30.glGenTextures(1, id, 0);
        lightTex = id[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lightTex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);   // terrain sampler 1: Bilinear
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 16, 16, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        buildStars();
    }

    private static int tex(AssetManager am, String path, boolean linear) {
        try {
            Bitmap b = BitmapFactory.decodeStream(am.open(path));
            return Hud.tex(b, linear);
        } catch (Exception e) { return 0; }
    }

    /** GameRenderer::_tickLightTexture: refresh + upload */
    public void updateLight(float tod) {
        float[] sr = new float[4];
        sunrise(tod, sr);
        lightTexture(skyDarken(tod), sr, lightPix);
        lightBuf.position(0);
        lightBuf.put(lightPix).position(0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lightTex);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 16, 16, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, lightBuf);
    }

    /** LevelRenderer::_buildStarsMesh: Random(10842), 1500 tries, quads of size 0.15 + 0.1 r at radius 100 */
    private void buildStars() {
        McRandom r = new McRandom(10842);
        float[] v = new float[1500 * 4 * 7];
        int n = 0;
        for (int i = 0; i < 1500; i++) {
            float x = r.nextFloat() * 2 - 1, y = r.nextFloat() * 2 - 1, z = r.nextFloat() * 2 - 1;
            float size = 0.15f + r.nextFloat() * 0.1f;
            float d = x * x + y * y + z * z;
            if (!(d < 1f && d > 0.01f)) continue;
            float alpha = 0.7f + r.nextFloat() * 0.3f;
            float inv = 1f / (float) Math.sqrt(d);
            x *= inv; y *= inv; z *= inv;
            float px = x * 100, py = y * 100, pz = z * 100;
            double a1 = Math.atan2(x, z);
            float sA = (float) Math.sin(a1), cA = (float) Math.cos(a1);
            double a2 = Math.atan2(Math.sqrt(x * x + z * z), y);
            float sB = (float) Math.sin(a2), cB = (float) Math.cos(a2);
            double rot = r.nextFloat() * Math.PI * 2;
            float sR = (float) Math.sin(rot), cR = (float) Math.cos(rot);
            for (int j = 0; j < 4; j++) {
                float d18 = ((j & 2) - 1) * size, d19 = (((j + 1) & 2) - 1) * size;
                float d21 = d18 * cR - d19 * sR, d22 = d19 * cR + d18 * sR;
                float d23 = d21 * sB, d24 = -d21 * cB;
                float d25 = d24 * sA - d22 * cA, d26 = d22 * sA + d24 * cA;
                v[n++] = px + d25; v[n++] = py + d23; v[n++] = pz + d26;
                v[n++] = 1; v[n++] = 1; v[n++] = 1; v[n++] = alpha;
            }
        }
        // quads -> triangles
        float[] tri = new float[n / 4 * 6];
        int m2 = 0;
        for (int q = 0; q < n; q += 28) {
            int[] o = {0, 1, 2, 0, 2, 3};
            for (int k : o) { System.arraycopy(v, q + k * 7, tri, m2, 7); m2 += 7; }
        }
        starCount = m2 / 7;
        FloatBuffer b = Gl.floats(m2);
        b.put(tri, 0, m2).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m2 * 4, b, GLES30.GL_STATIC_DRAW);
    }

    private void draw(int prog, int mode, float[] data, int count, int stride, boolean uv) {
        fb.position(0);
        fb.put(data, 0, count * stride).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * stride * 4, fb, GLES30.GL_DYNAMIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride * 4, 0);
        GLES30.glVertexAttribPointer(1, uv ? 2 : 4, GLES30.GL_FLOAT, false, stride * 4, 12);
        GLES30.glDrawArrays(mode, 0, count);
    }

    /**
     * LevelRenderer::renderSky. proj: the camera projection; rot: the view rotation (no translation).
     * sky: Dimension::getSkyColor at the camera (biome temperature); fog: this frame's fog colour.
     */
    public void renderSky(float[] proj, float[] rot, long time, float a, float temperature, float ambient, float[] fog) {
        float tod = timeOfDay(time, a);
        float[] sky = new float[3];
        biomeSky(temperature, sky);
        float bri = dayBrightness(tod);
        float sr = sky[0] * bri * ambient, sg = sky[1] * bri * ambient, sb = sky[2] * bri * ambient;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(false);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glDisableVertexAttribArray(3);
        Matrix.multiplyMM(mvp, 0, proj, 0, rot, 0);
        // sky plane: fan centre (0,128,0) BLACK -> CURRENT_COLOR, ring WHITE -> FOG_COLOR
        float[] v = new float[12 * 7];
        int n = 0;
        v[n++] = 0; v[n++] = 128; v[n++] = 0; v[n++] = sr; v[n++] = sg; v[n++] = sb; v[n++] = 1;
        for (int i = 0; i <= 10; i++) {
            double ang = -i * TAU * 0.1;
            v[n++] = (float) Math.cos(ang) * 2000f; v[n++] = 128; v[n++] = (float) -Math.sin(ang) * 2000f;
            v[n++] = fog[0]; v[n++] = fog[1]; v[n++] = fog[2]; v[n++] = 1;
        }
        GLES30.glUseProgram(pCol);
        GLES30.glUniformMatrix4fv(uColMvp, 1, false, mvp, 0);
        draw(pCol, GLES30.GL_TRIANGLE_FAN, v, 12, 7, false);
        // sun and moon: sky.material sun_moon (SourceAlpha, One)
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
        float deg = tod * 360f;
        GLES30.glUseProgram(pTex);
        GLES30.glUniform1i(uTexTex, 0);
        GLES30.glUniform4f(uTexCur, 1, 1, 1, 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        for (int pass = 0; pass < 2; pass++) {
            boolean sun = pass == 0;
            float dg = (deg + (sun ? 0 : 180f)) % 360f;
            if (dg > 105f && dg < 255f) continue;                  // below the horizon
            Matrix.setIdentityM(w, 0);
            Matrix.rotateM(w, 0, dg, 1, 0, 0);
            Matrix.translateM(w, 0, 0, 120, 0);
            Matrix.multiplyMM(t, 0, mvp, 0, w, 0);
            GLES30.glUniformMatrix4fv(uTexMvp, 1, false, t, 0);
            float s, u0, u1, v0, v1;
            if (sun) { s = 30; u0 = 0; u1 = 1; v0 = 0; v1 = 1; GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sunTex); }
            else {
                int ph = (int) ((time / 24000L) % 8);
                s = 20; u0 = (ph % 4) / 4f; u1 = (ph % 4 + 1) / 4f; v0 = (ph / 4 % 2) / 2f; v1 = (ph / 4 % 2 + 1) / 2f;
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, moonTex);
            }
            // _buildSunAndMoonQuad: (-s,0,-s u1 v0) (s,0,-s u0 v0) (s,0,s u0 v1) (-s,0,s u1 v1)
            float[] q = {-s, 0, -s, u1, v0, s, 0, -s, u0, v0, s, 0, s, u0, v1, -s, 0, -s, u1, v0, s, 0, s, u0, v1, -s, 0, s, u1, v1};
            draw(pTex, GLES30.GL_TRIANGLES, q, 6, 5, true);
        }
        // stars: (OneMinusDstColor, One), rotated sunAngle
        float star = starBrightness(tod);
        if (star > 0) {
            GLES30.glBlendFunc(GLES30.GL_ONE_MINUS_DST_COLOR, GLES30.GL_ONE);
            Matrix.setIdentityM(w, 0);
            Matrix.rotateM(w, 0, deg, 1, 0, 0);
            Matrix.multiplyMM(t, 0, mvp, 0, w, 0);
            GLES30.glUseProgram(pStar);
            GLES30.glUniformMatrix4fv(uStarMvp, 1, false, t, 0);
            GLES30.glUniform4f(uStarCur, star, star, star, 1);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starVbo);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0);
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 28, 12);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, starCount);
        }
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
    }

    // ------------------------------------------------------------------ clouds
    private double cloudBuiltX = Double.NaN, cloudBuiltZ;
    private boolean cloudAbove;
    private int cloudChunkX, cloudChunkZ;

    private int alphaAt(int tx, int tz) {
        tx = ((tx % cloudW) + cloudW) % cloudW; tz = ((tz % cloudH) + cloudH) % cloudH;
        return cloudPix[tz * cloudW + tx] & 255;
    }

    /** TextureTessellator::tessellate of clouds.png around chunk (cx, cz): 64 x 64 cells, local 0..64 */
    private void buildClouds(int cx, int cz, boolean above) {
        float[] v = new float[1 << 16];
        int n = 0;
        int x0 = cx - 32, z0 = cz - 32;
        for (int j = 0; j < 64; j++) for (int i = 0; i < 64; i++) {
            int al = alphaAt(x0 + i, z0 + j);
            // top / bottom: alpha > 1; _addLighting: clamp(0.275 + 0.275 ny - 0.1 nx^2 + 0.1 nz^2 + 0.75, 0, 1)
            if (al > 1) {
                float y = above ? 1 : 0, sh = above ? 1f : 0.75f, a = al / 255f;
                float[][] c = above ? new float[][]{{i, y, j}, {i, y, j + 1}, {i + 1, y, j + 1}, {i + 1, y, j}}
                        : new float[][]{{i, y, j}, {i + 1, y, j}, {i + 1, y, j + 1}, {i, y, j + 1}};
                n = quad(v, n, c, sh, a);
            }
            // sides: alpha > 9 is solid; a face on every change along x / z
            boolean me = al > 9;
            if (me) {
                if (i == 0 || alphaAt(x0 + i - 1, z0 + j) <= 9)
                    n = quad(v, n, new float[][]{{i, 0, j}, {i, 0, j + 1}, {i, 1, j + 1}, {i, 1, j}}, 0.925f, al / 255f);
                if (i == 63 || alphaAt(x0 + i + 1, z0 + j) <= 9)
                    n = quad(v, n, new float[][]{{i + 1, 0, j + 1}, {i + 1, 0, j}, {i + 1, 1, j}, {i + 1, 1, j + 1}}, 0.925f, al / 255f);
                if (j == 0 || alphaAt(x0 + i, z0 + j - 1) <= 9)
                    n = quad(v, n, new float[][]{{i + 1, 0, j}, {i, 0, j}, {i, 1, j}, {i + 1, 1, j}}, 1f, al / 255f);
                if (j == 63 || alphaAt(x0 + i, z0 + j + 1) <= 9)
                    n = quad(v, n, new float[][]{{i, 0, j + 1}, {i + 1, 0, j + 1}, {i + 1, 1, j + 1}, {i, 1, j + 1}}, 1f, al / 255f);
            }
            if (n + 512 > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
        }
        cloudCount = n / 7;
        FloatBuffer b = Gl.floats(n);
        b.put(v, 0, n).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 4, b, GLES30.GL_STATIC_DRAW);
    }

    private static int quad(float[] v, int n, float[][] c, float sh, float a) {
        int[] o = {0, 1, 2, 0, 2, 3};
        for (int k : o) {
            v[n++] = c[k][0]; v[n++] = c[k][1]; v[n++] = c[k][2];
            v[n++] = sh; v[n++] = sh; v[n++] = sh; v[n++] = a;
        }
        return n;
    }

    /**
     * LevelRenderer::renderClouds. view: camera rotation only; cam: camera position; ticks: level ticks;
     * proj: a projection that reaches the clouds; far: RENDER_DISTANCE
     */
    public void renderClouds(float[] proj, float[] rot, double camX, double camY, double camZ, long ticks, float a, long time, float far) {
        if (cloudPix == null) return;
        double sx = camX + (ticks + a) * 0.02, sz = camZ;
        boolean above = camY >= 129.0;
        double dx = sx - cloudBuiltX, dz = sz - cloudBuiltZ;
        if (Double.isNaN(cloudBuiltX) || dx * dx + dz * dz > 225 || above != cloudAbove) {
            cloudBuiltX = sx; cloudBuiltZ = sz; cloudAbove = above;
            cloudChunkX = (int) Math.floor(sx / 16); cloudChunkZ = (int) Math.floor(sz / 16);
            buildClouds(cloudChunkX, cloudChunkZ, above);
        }
        float tod = timeOfDay(time, a);
        float b = dayBrightness(tod);
        float cr = 0.1f + b * 0.9f, cg = cr, cb = 0.15f + b * 0.85f;
        float[] sr = new float[4];
        sunrise(tod, sr);
        float k = sr[3] * 0.35f;
        cr = sr[0] * k + cr * (1 - k); cg = sr[1] * k + cg * (1 - k); cb = sr[2] * k + cb * (1 - k);
        Matrix.setIdentityM(w, 0);
        Matrix.translateM(w, 0, (float) (cloudChunkX * 16 - sx), (float) (128.33 - camY), (float) (cloudChunkZ * 16 - sz));
        Matrix.scaleM(w, 0, 16, 4, 16);
        Matrix.translateM(w, 0, -32, 0, -32);
        Matrix.multiplyMM(t, 0, rot, 0, w, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, t, 0);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glDisableVertexAttribArray(3);
        GLES30.glUseProgram(pCloud);
        GLES30.glUniformMatrix4fv(uCloudMvp, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(uCloudWorld, 1, false, w, 0);
        GLES30.glUniform4f(uCloudCur, Math.max(cr, 0), Math.max(cg, 0), Math.max(cb, 0), 0.7f);
        GLES30.glUniform1f(uCloudRd, far);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudVbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0);
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 28, 12);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, cloudCount);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
    }
}
