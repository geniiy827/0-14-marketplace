package com.nova.engine.render;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.FloatBuffer;

/**
 * The rotating title background of 0.14.3 (CubemapBackgroundScreen::renderCubemap / render):
 *  - perspective fov 120, aspect 1, near 0.05, far 10 (stretched over the screen)
 *  - rotate 180 about X, then sin((T + a) * 0.0025) * 25 + 20 about X, then -(T + a) * 0.1 about Y;
 *    T grows by the frame time x 30 every frame
 *  - six quads (-1..1 at z = 1): face 0 as is, 1 rotate 90 Y, 2 180 Y, 3 -90 Y, 4 90 X, 5 -90 X, each with
 *    gui/background/panorama_<face>.png
 *  - then fillGradient over the screen from (1, 1, 1, 0.35) at the top to (0, 0, 0, 0.35) at the bottom (done by the
 *    caller with the HUD batch)
 */
public final class Panorama {
    static final String VS = "#version 300 es\nlayout(location=0) in vec3 aPos; layout(location=1) in vec2 aUv;\n"
            + "uniform mat4 uMvp; out vec2 vUv; void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv; }\n";
    static final String FS = "#version 300 es\nprecision mediump float; uniform sampler2D uTex; in vec2 vUv; out vec4 o;\n"
            + "void main(){ o = vec4(texture(uTex, vUv).rgb, 1.0); }\n";

    private int prog, uMvp, uTex, vbo;
    private final int[] tex = new int[6];
    private float time;
    private final float[] proj = new float[16], mv = new float[16], face = new float[16], mvp = new float[16];
    private boolean ok;

    public void init(AssetManager am) {
        prog = Gl.program(VS, FS);
        uMvp = GLES30.glGetUniformLocation(prog, "uMvp");
        uTex = GLES30.glGetUniformLocation(prog, "uTex");
        vbo = Gl.buffer();
        // the quad of renderCubemap: (-1,-1,1 uv 0,0) (-1,1,1 uv 0,1) (1,1,1 uv 1,1) (1,-1,1 uv 1,0)
        float[] q = {-1, -1, 1, 0, 0, -1, 1, 1, 0, 1, 1, 1, 1, 1, 1, -1, -1, 1, 0, 0, 1, 1, 1, 1, 1, 1, -1, 1, 1, 0};
        FloatBuffer fb = Gl.floats(q.length);
        fb.put(q).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, q.length * 4, fb, GLES30.GL_STATIC_DRAW);
        ok = am != null;
        for (int i = 0; i < 6 && ok; i++) {
            try {
                Bitmap b = BitmapFactory.decodeStream(am.open("images/gui/background/panorama_" + i + ".png"));
                int[] t = new int[1];
                GLES30.glGenTextures(1, t, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
                tex[i] = t[0];
            } catch (Exception e) { ok = false; }
        }
    }

    public void render(float dt) {
        time += dt * 30f;
        GLES30.glClearColor(0, 0, 0, 1);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        if (!ok) return;
        Matrix.perspectiveM(proj, 0, 120f, 1f, 0.05f, 10f);
        Matrix.setIdentityM(mv, 0);
        Matrix.rotateM(mv, 0, 180f, 1, 0, 0);
        Matrix.rotateM(mv, 0, (float) Math.sin(time * 0.0025f) * 25f + 20f, 1, 0, 0);
        Matrix.rotateM(mv, 0, -time * 0.1f, 0, 1, 0);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(prog);
        GLES30.glUniform1i(uTex, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12);
        for (int i = 0; i < 6; i++) {
            System.arraycopy(mv, 0, face, 0, 16);
            switch (i) {
                case 1: Matrix.rotateM(face, 0, 90f, 0, 1, 0); break;
                case 2: Matrix.rotateM(face, 0, 180f, 0, 1, 0); break;
                case 3: Matrix.rotateM(face, 0, -90f, 0, 1, 0); break;
                case 4: Matrix.rotateM(face, 0, 90f, 1, 0, 0); break;
                case 5: Matrix.rotateM(face, 0, -90f, 1, 0, 0); break;
                default: break;
            }
            Matrix.multiplyMM(mvp, 0, proj, 0, face, 0);
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i]);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);
        }
    }
}
