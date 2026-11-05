package com.nova.engine.render;

import android.opengl.GLES30;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * GPU side of the world: one VBO pair (solid / water) per section, shared quad index buffer,
 * terrain shader with linear fog and alpha test.
 */
public final class WorldRenderer {
    /**
     * renderchunk.vertex / renderchunk.fragment of 0.14 (FANCY, FOG): TEXCOORD_1 = light pair / 16 into the 16x16 light
     * texture (TEXTURE_1, bilinear), diffuse * light * colour, fog by the camera distance:
     * clamp((dist / RENDER_DISTANCE - FOG_CONTROL.x) / (FOG_CONTROL.y - FOG_CONTROL.x)) -> here start / end in blocks.
     */
    static final String VS =
            "#version 300 es\n" +
            "layout(location=0) in vec3 aPos; layout(location=1) in vec2 aUv; layout(location=2) in vec4 aCol; layout(location=3) in vec2 aLight;\n" +
            "uniform mat4 uMvp; uniform vec3 uOrigin; uniform vec3 uCam; uniform float uWater; uniform float uFarChunks;\n" +
            "out highp vec2 vUv; out vec2 vLm; out vec4 vCol; out float vDist; out float vNear; out float vCamDist;\n" +
            "void main(){ vec3 p = aPos + uOrigin; gl_Position = uMvp * vec4(p, 1.0);\n" +
            " vUv = aUv; vLm = aLight / 16.0; vec4 color = aCol; vec3 relPos = uCam - p; float depth = length(relPos); vDist = depth;\n" +
            " vNear = 0.0; vCamDist = 0.0;\n" +
            " if (uWater > 0.5 && aCol.a < 0.01) {\n" +       // renderchunk.vertex NEAR_WATER + FANCY
            "  vNear = 1.0; vCamDist = depth / uFarChunks;\n" +
            "  float F = dot(normalize(relPos), vec3(0.0, 1.0, 0.0)); F = 1.0 - max(F, 0.1);\n" +
            "  F = 1.0 - mix(F * F * F * F, 1.0, min(1.0, depth / uFarChunks));\n" +
            "  color.rg -= vec2(F * 56.0 / 255.0);\n" +
            "  vec4 depthColor = vec4(color.rgb * 0.5, 1.0); vec4 traspColor = vec4(color.rgb * 0.45, 0.8); vec4 surfColor = vec4(color.rgb, 1.0);\n" +
            "  vec4 nearColor = mix(traspColor, depthColor, color.a); color = mix(surfColor, nearColor, F);\n" +
            " }\n" +
            " vCol = color; }\n";
    static final String FS =
            "#version 300 es\nprecision mediump float;\n" +
            "uniform sampler2D uTex; uniform sampler2D uLightTex; uniform vec3 uFog; uniform vec2 uFogRange; uniform float uAlphaCut; uniform float uFlat;\n" +
            "in highp vec2 vUv; in vec2 vLm; in vec4 vCol; in float vDist; in float vNear; in float vCamDist; out vec4 o;\n" +
            // util.h texture2D_AA (TEXEL_AA, ATLAS_TEXTURE): one-pixel-wide bilinear edges, mip level from the derivatives
            // + 0.5 bias, uvs clamped inside the 16x16 atlas face per level, two levels mixed
            "uniform highp vec3 uTexDim;\n" +
            "highp vec2 clampFace(highp vec2 uv, highp float lv){ highp vec2 n = uTexDim.xy / 16.0; highp vec2 f = fract(uv * n);\n" +
            " highp vec2 c = uv * n - f; highp vec2 ht = vec2(0.5) / (vec2(16.0) / pow(2.0, lv)); f = clamp(f, ht, vec2(1.0) - ht); return (c + f) / n; }\n" +
            "vec4 texAA(highp vec2 uv){ highp vec2 dx = dFdx(uv) * uTexDim.xy, dy = dFdy(uv) * uTexDim.xy;\n" +
            " highp vec2 dU = vec2(dx.x, dy.x), dV = vec2(dx.y, dy.y); highp float du = sqrt(dot(dU, dU)), dv = sqrt(dot(dV, dV));\n" +
            " highp float d = max(dot(dx, dx), dot(dy, dy)); highp float lv = 0.5 * log2(d) + 0.5; lv = clamp(lv, 0.0, uTexDim.z);\n" +
            " highp vec2 m;\n" +
            " if (lv >= 1.0) m = uv; else { highp vec2 t = fract(uv * uTexDim.xy); m = uv * uTexDim.xy - t;\n" +
            "  highp vec2 sc = max(vec2(1.0 / du, 1.0 / dv), 1.0); t = clamp(t * sc, 0.0, 0.5) + clamp(t * sc - (sc - 0.5), 0.0, 0.5); m = (m + t) / uTexDim.xy; }\n" +
            " highp float l0 = clamp(floor(lv), 0.0, uTexDim.z), l1 = clamp(floor(lv + 1.0), 0.0, uTexDim.z);\n" +
            " return mix(textureLod(uTex, clampFace(m, l0), l0), textureLod(uTex, clampFace(m, l1), l1), fract(lv)); }\n" +
            // TEXEL_AA_FEATURE is off by default (Options::setTexelAA -> RendererSettings +0x31 = 0): plain texture2D
            "void main(){ vec4 t = texture(uTex, vUv); if (t.a < uAlphaCut) discard;\n" +
            " vec4 lit = t * texture(uLightTex, vLm); lit.a = t.a * vCol.a;\n" +
            " if (vNear > 0.5) lit.a = mix(lit.a, 1.0, clamp(vCamDist, 0.0, 1.0));\n" +
            " lit.rgb *= vCol.rgb;\n" +
            " vec4 c = mix(lit, vCol, uFlat); float f = clamp((vDist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);\n" +
            " o = vec4(mix(c.rgb, uFog, f), c.a); }\n";

    public static final class Section {
        int solidVbo, waterVbo, solidVerts, waterVerts;
        public volatile int version = -1;
        public volatile boolean building;
    }

    private int prog, uMvp, uOrigin, uCam, uTex, uLightTex, uFog, uFogRange, uAlphaCut, uFlat, uWater, uFarChunks, uTexDim;
    /** TEXTURE_DIMENSIONS: atlas width, height, mip levels */
    public float texW = 512, texH = 256, texLevels = 4;
    /** FAR_CHUNKS_DISTANCE (LevelRenderer +0x1638) */
    public float farChunks = 88f;
    /** the 16x16 light texture of GameRenderer::_tickLightTexture (set by the game every frame) */
    public int lightTex;
    private int indexBuf, maxQuads;
    public final Map<Long, Section> sections = new HashMap<Long, Section>();
    public int drawnSections;

    public void init() {
        prog = Gl.program(VS, FS);
        uMvp = GLES30.glGetUniformLocation(prog, "uMvp");
        uOrigin = GLES30.glGetUniformLocation(prog, "uOrigin");
        uCam = GLES30.glGetUniformLocation(prog, "uCam");
        uTex = GLES30.glGetUniformLocation(prog, "uTex");
        uLightTex = GLES30.glGetUniformLocation(prog, "uLightTex");
        uWater = GLES30.glGetUniformLocation(prog, "uWater");
        uTexDim = GLES30.glGetUniformLocation(prog, "uTexDim");
        uFarChunks = GLES30.glGetUniformLocation(prog, "uFarChunks");
        uFog = GLES30.glGetUniformLocation(prog, "uFog");
        uFogRange = GLES30.glGetUniformLocation(prog, "uFogRange");
        uAlphaCut = GLES30.glGetUniformLocation(prog, "uAlphaCut");
        uFlat = GLES30.glGetUniformLocation(prog, "uFlat");
        maxQuads = 1 << 16;
        IntBuffer ib = Gl.ints(maxQuads * 6);
        for (int q = 0; q < maxQuads; q++) {
            int b = q * 4;
            ib.put(b); ib.put(b + 1); ib.put(b + 2); ib.put(b); ib.put(b + 2); ib.put(b + 3);
        }
        ib.position(0);
        indexBuf = Gl.buffer();
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, maxQuads * 6 * 4, ib, GLES30.GL_STATIC_DRAW);
    }

    public static long key(int cx, int sy, int cz) { return ((long) (cx & 0xFFFFFF) << 36) | ((long) (cz & 0xFFFFFF) << 8) | (sy & 0xFF); }

    public Section section(int cx, int sy, int cz) {
        long k = key(cx, sy, cz);
        Section s = sections.get(k);
        if (s == null) { s = new Section(); sections.put(k, s); }
        return s;
    }

    /** GL thread: upload a finished mesh */
    public void upload(Mesher.Mesh m) {
        Section s = section(m.cx, m.sy, m.cz);
        s.building = false;
        s.version = m.version;
        s.solidVbo = put(s.solidVbo, m.solid);
        s.solidVerts = m.solidVerts;
        s.waterVbo = put(s.waterVbo, m.water);
        s.waterVerts = m.waterVerts;
    }

    private static IntBuffer upBuf;

    private static int put(int vbo, int[] data) {
        if (data.length == 0) { Gl.delete(vbo); return 0; }
        if (vbo == 0) vbo = Gl.buffer();
        if (upBuf == null || upBuf.capacity() < data.length) upBuf = Gl.ints(Math.max(data.length, 1 << 18));
        IntBuffer b = upBuf;
        b.clear();
        b.put(data).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4, b, GLES30.GL_STATIC_DRAW);
        return vbo;
    }

    /** drop everything (new world) */
    public void clearAll() {
        for (Section s : sections.values()) { Gl.delete(s.solidVbo); Gl.delete(s.waterVbo); }
        sections.clear();
    }

    /** drop sections of chunks out of range */
    public void unloadFar(int pcx, int pcz, int radius) {
        Iterator<Map.Entry<Long, Section>> it = sections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Section> e = it.next();
            long k = e.getKey();
            int cx = (int) (k >> 36) << 8 >> 8, cz = (int) (k >> 8) << 8 >> 8;
            if (Math.abs(cx - pcx) > radius + 1 || Math.abs(cz - pcz) > radius + 1) {
                Gl.delete(e.getValue().solidVbo);
                Gl.delete(e.getValue().waterVbo);
                it.remove();
            }
        }
    }

    public void draw(float[] mvp, float camX, float camY, float camZ, float[] look, int atlasTex, float[] fog, float fogEnd) {
        draw(mvp, camX, camY, camZ, look, atlasTex, fog, fogEnd * 0.6f, fogEnd);
    }

    public void draw(float[] mvp, float camX, float camY, float camZ, float[] look, int atlasTex, float[] fog, float fogStart, float fogEnd) {
        GLES30.glUseProgram(prog);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniform3f(uCam, camX, camY, camZ);
        GLES30.glUniform1i(uTex, 0);
        GLES30.glUniform1i(uLightTex, 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lightTex);
        GLES30.glUniform3f(uFog, fog[0], fog[1], fog[2]);
        GLES30.glUniform2f(uFogRange, fogStart, Math.max(fogEnd, fogStart + 0.01f));
        GLES30.glUniform1f(uWater, 0f);
        GLES30.glUniform1f(uFarChunks, Math.max(farChunks, 1f));
        GLES30.glUniform3f(uTexDim, texW, texH, texLevels);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTex);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glEnableVertexAttribArray(3);
        drawnSections = 0;
        // LevelRenderer: frustum culled render chunks (FrustumCuller), opaque front to back, blend back to front
        frustum(mvp);
        int nv = 0;
        float lim = (fogEnd + 16) * (fogEnd + 16);
        for (Map.Entry<Long, Section> e : sections.entrySet()) {
            Section s = e.getValue();
            if (s.solidVerts == 0 && s.waterVerts == 0) continue;
            long k = e.getKey();
            int cx = (int) (k >> 36) << 8 >> 8, cz = (int) (k >> 8) << 8 >> 8, sy = (int) (k & 0xFF);
            float x0 = cx * 16, y0 = sy * 16, z0 = cz * 16;
            float mx = x0 + 8 - camX, my = y0 + 8 - camY, mz = z0 + 8 - camZ;
            float d2 = mx * mx + my * my + mz * mz;
            if (d2 > lim || !boxInFrustum(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16)) continue;
            if (nv == visKeys.length) { visKeys = java.util.Arrays.copyOf(visKeys, nv * 2); visSec = java.util.Arrays.copyOf(visSec, nv * 2); visDist = java.util.Arrays.copyOf(visDist, nv * 2); }
            visKeys[nv] = k; visSec[nv] = s; visDist[nv] = d2; nv++;
        }
        // sort by distance: (float bits of d2 << 32 | index), positive floats sort like their bits
        if (sortBuf.length < nv) sortBuf = new long[visKeys.length];
        if (order.length < nv) order = new int[visKeys.length];
        for (int i = 0; i < nv; i++) sortBuf[i] = ((long) Float.floatToRawIntBits(visDist[i]) << 32) | i;
        java.util.Arrays.sort(sortBuf, 0, nv);
        for (int i = 0; i < nv; i++) order[i] = (int) sortBuf[i];
        // solid + cutout
        GLES30.glUniform1f(uAlphaCut, 0.5f);
        GLES30.glUniform1f(uFlat, 0f);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDepthMask(true);
        for (int i = 0; i < nv; i++) {
            int o = order[i];
            Section s = visSec[o];
            if (s.solidVerts == 0) continue;
            bindAndDraw(visKeys[o], s.solidVbo, s.solidVerts);
            drawnSections++;
        }
        // water
        GLES30.glUniform1f(uAlphaCut, 0.01f);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glPolygonOffset(0.1f, 0.1f);                              // terrain_water: polygonOffsetLevel 0.1
        GLES30.glUniform1f(uWater, 1f);
        // culled like the rest: liquids carry their own back faces, ice/glass show only their front faces
        for (int i = nv - 1; i >= 0; i--) {
            int o = order[i];
            Section s = visSec[o];
            if (s.waterVerts == 0) continue;
            bindAndDraw(visKeys[o], s.waterVbo, s.waterVerts);
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glUniform1f(uWater, 0f);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisableVertexAttribArray(3);
    }

    private long[] visKeys = new long[1024];
    private Section[] visSec = new Section[1024];
    private float[] visDist = new float[1024];
    private int[] order = new int[1024];
    private long[] sortBuf = new long[1024];
    private final float[] planes = new float[24];

    /** the 6 clip planes of a column-major mvp (Gribb-Hartmann) */
    private void frustum(float[] m) {
        for (int i = 0; i < 6; i++) {
            int row = i >> 1; float sg = (i & 1) == 0 ? 1 : -1;
            float a = m[3] + sg * m[row], b = m[7] + sg * m[4 + row], c = m[11] + sg * m[8 + row], d = m[15] + sg * m[12 + row];
            planes[i * 4] = a; planes[i * 4 + 1] = b; planes[i * 4 + 2] = c; planes[i * 4 + 3] = d;
        }
    }

    private boolean boxInFrustum(float x0, float y0, float z0, float x1, float y1, float z1) {
        for (int i = 0; i < 6; i++) {
            float a = planes[i * 4], b = planes[i * 4 + 1], c = planes[i * 4 + 2], d = planes[i * 4 + 3];
            float px = a >= 0 ? x1 : x0, py = b >= 0 ? y1 : y0, pz = c >= 0 ? z1 : z0;
            if (a * px + b * py + c * pz + d < 0) return false;
        }
        return true;
    }

    private int overlayVbo;

    /**
     * LevelRenderer::renderHitSelect: the selected block drawn again with shader colour 0.65 (additive),
     * so it lights up. verts: Mesher vertex format, positions relative to (bx, by, bz).
     */
    public void drawOverlay(float[] mvp, int atlasTex, int[] verts, int count, int bx, int by, int bz) {
        if (count == 0) return;
        if (overlayVbo == 0) overlayVbo = Gl.buffer();
        GLES30.glUseProgram(prog);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniform1f(uAlphaCut, 0.5f);
        GLES30.glUniform2f(uFogRange, 1e6f, 2e6f);
        GLES30.glUniform1f(uFlat, 1f);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTex);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, overlayVbo);
        java.nio.IntBuffer b = java.nio.ByteBuffer.allocateDirect(count * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
        b.put(verts, 0, count).position(0);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 4, b, GLES30.GL_DYNAMIC_DRAW);
        GLES30.glUniform3f(uOrigin, bx, by, bz);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 28, 12);
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, 28, 20);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glDisableVertexAttribArray(3);
        GLES30.glVertexAttrib2f(3, 15f, 15f);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_SRC_COLOR);    // ui3D.material selection_overlay
        GLES30.glDepthMask(false);
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glPolygonOffset(-5f, -5f);                                // polygonOffsetLevel 5
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count / 28 * 6, GLES30.GL_UNSIGNED_INT, 0);
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glUniform1f(uFlat, 0f);
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void bindAndDraw(long k, int vbo, int verts) {
        int cx = (int) (k >> 36) << 8 >> 8, cz = (int) (k >> 8) << 8 >> 8, sy = (int) (k & 0xFF);
        GLES30.glUniform3f(uOrigin, cx * 16, sy * 16, cz * 16);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 28, 12);
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, 28, 20);
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_UNSIGNED_BYTE, false, 28, 24);
        int quads = Math.min(verts / 4, maxQuads);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, quads * 6, GLES30.GL_UNSIGNED_INT, 0);
    }

    /** distance + rough view cone */
    private static boolean visible(long k, float x, float y, float z, float[] look, float far) {
        int cx = (int) (k >> 36) << 8 >> 8, cz = (int) (k >> 8) << 8 >> 8, sy = (int) (k & 0xFF);
        float mx = cx * 16 + 8 - x, my = sy * 16 + 8 - y, mz = cz * 16 + 8 - z;
        float d2 = mx * mx + my * my + mz * mz;
        if (d2 > (far + 16) * (far + 16)) return false;
        if (d2 < 32 * 32) return true;
        float d = (float) Math.sqrt(d2);
        float dot = (mx * look[0] + my * look[1] + mz * look[2]) / d;
        return dot > -0.25f - 14f / d;                // generous: never hides what is on screen
    }
}
