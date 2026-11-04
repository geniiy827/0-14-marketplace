package com.nova.engine.render;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import com.nova.engine.game.Player;

import java.nio.FloatBuffer;

/**
 * The empty hand in first person, as 0.14.3 draws it:
 *  - InGamePlayScreen::_renderItemInHand: own projection (LevelRenderer::getFov(a, false) = 70, Z_NEAR 0.05)
 *  - ItemInHandRenderer::setPlayerBob: (rot - lerp(bobOld, bob)) * 0.1, above 1 -> sign * 4th root, x 2, clamped to
 *    +-10 degrees about X (pitch) and Y (yaw); LocalPlayer::aiStep moves bob 0.4 of the way each tick
 *  - ItemInHandRenderer::render, empty hand (f = Mob::getAttackAnim, h = equipped height 1):
 *    translate(-sin(sqrt f pi) * 0.3, sin(sqrt f pi 2) * 0.4, -sin(f pi) * 0.4); translate(0.64, 0.6h - 1.2, -0.72);
 *    rotate 45 Y; rotate sin(sqrt f pi) * 70 Y; rotate -sin(f f pi) * 20 Z; translate(-1, 3.6, 3.5); rotate 120 Z;
 *    rotate 200 X; rotate -135 Y; translate(5.6, 0, 0); PlayerRenderer::renderRightHand
 *  - PlayerRenderer::renderRightHand: setupAnim(0, 0, 0, 0, 0, 1/16) -> right arm zRot = cos(0) * 0.05 + 0.05;
 *    ModelPart rightArm + rightSleeve of geometry.humanoid.custom (skins/Base/Vanilla.json): pivot (-5, 22, 0),
 *    cube origin (-8, 12, -2) size 4 12 4 uv (40, 16); sleeve uv (40, 32) inflate 0.25, alpha tested
 *  - entity.vertex lighting (FANCY): L = (1 + Ny) / 2 * 0.55 + Nx^2 * -0.1 + Nz^2 * 0.1 + 0.45 (world normals)
 * Skin: images/mob/skins/Base/steve.png (64 x 64).
 */
public final class HandRenderer {
    static final String VS = "#version 300 es\nlayout(location=0) in vec3 aPos; layout(location=1) in vec2 aUv; layout(location=2) in vec3 aN;\n"
            + "uniform mat4 uMvp; uniform mat4 uWorld; out vec2 vUv; out float vL;\n"
            + "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv;\n"
            + " vec3 N = normalize((uWorld * vec4(aN, 0.0)).xyz);\n"
            + " vL = (1.0 + N.y) * 0.5 * (1.0 - 0.45) + N.x * N.x * -0.1 + N.z * N.z * 0.1 + 0.45; }\n";
    static final String FS = "#version 300 es\nprecision mediump float; uniform sampler2D uTex; in vec2 vUv; in float vL; out vec4 o;\n"
            + "void main(){ vec4 c = texture(uTex, vUv); if (c.a < 0.5) discard; o = vec4(c.rgb * vL, 1.0); }\n";

    private int prog, uMvp, uWorld, uTex, vbo, tex, verts;
    private final float[] proj = new float[16], m = new float[16], mvp = new float[16];

    public void init(AssetManager am) {
        prog = Gl.program(VS, FS);
        uMvp = GLES30.glGetUniformLocation(prog, "uMvp");
        uWorld = GLES30.glGetUniformLocation(prog, "uWorld");
        uTex = GLES30.glGetUniformLocation(prog, "uTex");
        vbo = Gl.buffer();
        tex = 0;
        try {
            Bitmap b = BitmapFactory.decodeStream(am.open("images/mob/skins/Base/steve.png"));
            int[] t = new int[1];
            GLES30.glGenTextures(1, t, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            tex = t[0];
        } catch (Exception ignored) { }
        // the arm part in ModelPart space (y down): pos (-5, 2, 0), cube from (-3, -2, -2) size 4 12 4
        FloatBuffer fb = Gl.floats(2 * 36 * 8);
        cube(fb, -3, -2, -2, 4, 12, 4, 40, 16, 0f);
        cube(fb, -3, -2, -2, 4, 12, 4, 40, 32, 0.25f);
        verts = fb.position() / 8;
        fb.position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts * 32, fb, GLES30.GL_STATIC_DRAW);
    }

    /**
     * A model cube with the standard skin layout: x- side at (u, v+d) (d x h), front z- (u+d, v+d) (w x h),
     * x+ side (u+d+w, v+d), back z+ (u+2d+w, v+d), top y- (u+d, v) (w x d), bottom y+ (u+d+w, v). Units 1/16.
     */
    private static void cube(FloatBuffer fb, float x0, float y0, float z0, float w, float h, float d, float u, float v, float g) {
        float ax = (x0 - g) / 16f, ay = (y0 - g) / 16f, az = (z0 - g) / 16f;
        float bx = (x0 + w + g) / 16f, by = (y0 + h + g) / 16f, bz = (z0 + d + g) / 16f;
        // each face: 4 corners as seen from outside, top-left, top-right, bottom-right, bottom-left (y down = image down)
        face(fb, new float[]{ax, ay, bz, ax, ay, az, ax, by, az, ax, by, bz}, u, v + d, d, h, -1, 0, 0);          // x- (outer)
        face(fb, new float[]{ax, ay, az, bx, ay, az, bx, by, az, ax, by, az}, u + d, v + d, w, h, 0, 0, -1);      // z- front
        face(fb, new float[]{bx, ay, az, bx, ay, bz, bx, by, bz, bx, by, az}, u + d + w, v + d, d, h, 1, 0, 0);   // x+ (inner)
        face(fb, new float[]{bx, ay, bz, ax, ay, bz, ax, by, bz, bx, by, bz}, u + 2 * d + w, v + d, w, h, 0, 0, 1); // z+ back
        face(fb, new float[]{ax, ay, bz, bx, ay, bz, bx, ay, az, ax, ay, az}, u + d, v, w, d, 0, -1, 0);          // y- top
        face(fb, new float[]{ax, by, az, bx, by, az, bx, by, bz, ax, by, bz}, u + d + w, v, w, d, 0, 1, 0);       // y+ bottom
    }

    private static void face(FloatBuffer fb, float[] c, float u, float v, float uw, float vh, float nx, float ny, float nz) {
        float[][] uv = {{u, v}, {u + uw, v}, {u + uw, v + vh}, {u, v + vh}};
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int k : order) {
            fb.put(c[k * 3]); fb.put(c[k * 3 + 1]); fb.put(c[k * 3 + 2]);
            fb.put(uv[k][0] / 64f); fb.put(uv[k][1] / 64f);
            fb.put(nx); fb.put(ny); fb.put(nz);
        }
    }

    /** LevelRenderer::bobView: walking sway of the camera (and the hand) */
    public static void bobView(float[] m, Player p, float a) {
        float f = p.walkDist - p.oWalkDist;
        float ang = (f * a - p.oWalkDist) * (float) Math.PI;
        float b = p.oBob + (p.bob - p.oBob) * a;
        float t = p.oTilt + (p.tilt - p.oTilt) * a;
        Matrix.translateM(m, 0, (float) Math.sin(ang) * b * 0.5f, -Math.abs((float) Math.cos(ang) * b), 0f);
        Matrix.rotateM(m, 0, (float) Math.sin(ang) * b * 3f, 0, 0, 1);
        Matrix.rotateM(m, 0, Math.abs((float) Math.cos(ang - 0.2f) * b) * 5f, 1, 0, 0);
        Matrix.rotateM(m, 0, t, 1, 0, 0);
    }

    static float bobTerm(float v) {
        v *= 0.1f;
        if (Math.abs(v) > 1f) v = Math.signum(v) * (float) Math.sqrt(Math.sqrt(Math.abs(v)));
        v *= 2f;
        return Math.max(-10f, Math.min(10f, v));
    }

    public void render(Player p, float a, int width, int height) {
        if (tex == 0) return;
        Matrix.perspectiveM(proj, 0, 70f, (float) width / height, 0.05f, 256f);
        Matrix.setIdentityM(m, 0);
        bobView(m, p, a);
        // setPlayerBob
        float bx = p.oBobX + (p.bobX - p.oBobX) * a, by = p.oBobY + (p.bobY - p.oBobY) * a;
        Matrix.rotateM(m, 0, bobTerm(p.pitch - bx), 1, 0, 0);
        Matrix.rotateM(m, 0, bobTerm(p.yaw - by), 0, 1, 0);
        // ItemInHandRenderer::render, empty hand
        float f = p.attackAnim(a), h = 1f, PI = (float) Math.PI;
        float sq = (float) Math.sqrt(f);
        float s18 = (float) Math.sin(sq * PI), s12 = (float) Math.sin(sq * PI * 2), s14 = (float) Math.sin(f * PI), s19 = (float) Math.sin(f * f * PI);
        Matrix.translateM(m, 0, -s18 * 0.3f, s12 * 0.4f, -s14 * 0.4f);
        Matrix.translateM(m, 0, 0.64f, 0.6f * h - 1.2f, -0.72f);
        Matrix.rotateM(m, 0, 45f, 0, 1, 0);
        Matrix.rotateM(m, 0, s18 * 70f, 0, 1, 0);
        Matrix.rotateM(m, 0, -s19 * 20f, 0, 0, 1);
        Matrix.translateM(m, 0, -1f, 3.6f, 3.5f);
        Matrix.rotateM(m, 0, 120f, 0, 0, 1);
        Matrix.rotateM(m, 0, 200f, 1, 0, 0);
        Matrix.rotateM(m, 0, -135f, 0, 1, 0);
        Matrix.translateM(m, 0, 5.6f, 0, 0);
        // ModelPart rightArm: translate(pos / 16), rotate zRot (0.1 rad)
        Matrix.translateM(m, 0, -5f / 16f, 2f / 16f, 0);
        Matrix.rotateM(m, 0, (float) Math.toDegrees(0.1), 0, 0, 1);
        Matrix.multiplyMM(mvp, 0, proj, 0, m, 0);

        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(prog);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(uWorld, 1, false, m, 0);
        GLES30.glUniform1i(uTex, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 32, 12);
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 32, 20);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, verts);
        GLES30.glDisableVertexAttribArray(2);
    }
}
