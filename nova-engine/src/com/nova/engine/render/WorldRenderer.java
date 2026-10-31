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
    static final String VS =
            "#version 300 es\n" +
            "layout(location=0) in vec3 aPos; layout(location=1) in vec2 aUv; layout(location=2) in vec4 aCol;\n" +
            "uniform mat4 uMvp; uniform vec3 uOrigin; uniform vec3 uCam;\n" +
            "out vec2 vUv; out vec4 vCol; out float vDist;\n" +
            "void main(){ vec3 p = aPos + uOrigin; gl_Position = uMvp * vec4(p, 1.0);\n" +
            " vUv = aUv; vCol = aCol; vDist = length(p.xz - uCam.xz); }\n";
    static final String FS =
            "#version 300 es\nprecision mediump float;\n" +
            "uniform sampler2D uTex; uniform vec3 uFog; uniform vec2 uFogRange; uniform float uAlphaCut;\n" +
            "in vec2 vUv; in vec4 vCol; in float vDist; out vec4 o;\n" +
            "void main(){ vec4 t = texture(uTex, vUv); if (t.a < uAlphaCut) discard;\n" +
            " vec4 c = t * vCol; float f = clamp((vDist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);\n" +
            " o = vec4(mix(c.rgb, uFog, f), c.a); }\n";

    public static final class Section {
        int solidVbo, waterVbo, solidVerts, waterVerts;
        public volatile int version = -1;
        public volatile boolean building;
    }

    private int prog, uMvp, uOrigin, uCam, uTex, uFog, uFogRange, uAlphaCut;
    private int indexBuf, maxQuads;
    public final Map<Long, Section> sections = new HashMap<Long, Section>();
    public int drawnSections;

    public void init() {
        prog = Gl.program(VS, FS);
        uMvp = GLES30.glGetUniformLocation(prog, "uMvp");
        uOrigin = GLES30.glGetUniformLocation(prog, "uOrigin");
        uCam = GLES30.glGetUniformLocation(prog, "uCam");
        uTex = GLES30.glGetUniformLocation(prog, "uTex");
        uFog = GLES30.glGetUniformLocation(prog, "uFog");
        uFogRange = GLES30.glGetUniformLocation(prog, "uFogRange");
        uAlphaCut = GLES30.glGetUniformLocation(prog, "uAlphaCut");
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

    private static int put(int vbo, int[] data) {
        if (data.length == 0) { Gl.delete(vbo); return 0; }
        if (vbo == 0) vbo = Gl.buffer();
        IntBuffer b = Gl.ints(data.length);
        b.put(data).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4, b, GLES30.GL_STATIC_DRAW);
        return vbo;
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
        GLES30.glUseProgram(prog);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniform3f(uCam, camX, camY, camZ);
        GLES30.glUniform1i(uTex, 0);
        GLES30.glUniform3f(uFog, fog[0], fog[1], fog[2]);
        GLES30.glUniform2f(uFogRange, fogEnd * 0.6f, fogEnd);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTex);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glEnableVertexAttribArray(2);
        drawnSections = 0;
        // solid + cutout
        GLES30.glUniform1f(uAlphaCut, 0.5f);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDepthMask(true);
        for (Map.Entry<Long, Section> e : sections.entrySet()) {
            Section s = e.getValue();
            if (s.solidVerts == 0 || !visible(e.getKey(), camX, camY, camZ, look, fogEnd)) continue;
            bindAndDraw(e.getKey(), s.solidVbo, s.solidVerts);
            drawnSections++;
        }
        // water
        GLES30.glUniform1f(uAlphaCut, 0.01f);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        for (Map.Entry<Long, Section> e : sections.entrySet()) {
            Section s = e.getValue();
            if (s.waterVerts == 0 || !visible(e.getKey(), camX, camY, camZ, look, fogEnd)) continue;
            bindAndDraw(e.getKey(), s.waterVbo, s.waterVerts);
        }
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void bindAndDraw(long k, int vbo, int verts) {
        int cx = (int) (k >> 36) << 8 >> 8, cz = (int) (k >> 8) << 8 >> 8, sy = (int) (k & 0xFF);
        GLES30.glUniform3f(uOrigin, cx * 16, sy * 16, cz * 16);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 24, 12);
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, 24, 20);
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
