package com.nova.client;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewGroup;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * NOVA loading screen, drawn over the game while it loads:
 *  1. studio logo "RC" (two letters slide together, light sweep, fade out),
 *  2. the NOVA title as 3D letters (model from tools/gen_intro_mesh.py) flying in and
 *     landing in the pose of images/gui/title.png,
 *  3. once the game shows its menu: the title glides to its place in the menu and the
 *     black background fades away; the view then removes itself.
 * Own GL surface on top of the game's, so it keeps animating while the game thread loads.
 */
final class IntroView extends GLSurfaceView implements GLSurfaceView.Renderer {
    static final String TAG = "NOVA.intro";

    // ---- timeline (seconds) --------------------------------------------------
    static final float RC_IN = 0.30f, RC_IN_D = 0.95f, RC_SWEEP = 1.05f, RC_OUT = 1.80f, RC_OUT_D = 0.45f;
    static final float TITLE = 2.25f, STAGGER = 0.13f, FLY = 1.05f;
    static final float CAM_D = 1.65f;                       // camera settles over TITLE..TITLE+CAM_D
    static final float HAND = 4.45f;                        // earliest hand-over to the menu
    static final float XFADE = 0.18f, MOVE = 0.75f, BG_DELAY = 0.25f, BG_D = 0.60f;
    static final float MAX_WAIT = 40f;
    static final float HIT = 1f - 1.35f / 2.35f;           // flight progress where a letter first reaches its place                      // give up waiting for the menu

    // final pose: same camera as the render the menu title was made from (fit in tools)
    static final float ELEV = 30f, DIST = 400f, FOV = 30f, TITLE_ASPECT = 1024f / 373f;
    static final float TGT_Y = 22f;

    // menu title rect (ui_new/start_screen.json: title_panel_pocket)
    static final float MENU_TOP = 0.09f, MENU_W = 0.40f;

    static IntroView current;

    static void attach(Activity a) {
        try {
            IntroView v = new IntroView(a);
            a.addContentView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            current = v;
        } catch (Throwable t) {
            Log.e(TAG, "intro failed to start", t);
            introDone();
        }
    }

    /** activity paused/left: drop the intro (nothing to resume into) */
    static void skip() {
        IntroView v = current;
        if (v != null) v.finish();
    }

    private final Activity act;
    private volatile boolean done;
    private boolean titleShown;
    private float shownAt;

    // ---- sound -------------------------------------------------------------------
    static final int S_RC_IN = 0, S_RC_SHINE = 1, S_WHOOSH = 2, S_HIT = 3, S_SHINE = 4, S_RISE = 5;
    static final String[] SFX = {"rc_in", "rc_shine", "whoosh", "hit", "shine", "rise"};
    static final float[] HIT_RATE = {1.0f, 1.07f, 0.94f, 1.13f};
    private android.media.SoundPool pool;
    private final int[] sid = new int[SFX.length];
    private final boolean[] fired = new boolean[32];

    private void loadSounds(Context c) {
        try {
            android.media.AudioAttributes at = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            pool = new android.media.SoundPool.Builder().setMaxStreams(8).setAudioAttributes(at).build();
            for (int i = 0; i < SFX.length; i++)
                sid[i] = pool.load(c.getAssets().openFd("nova/intro/sfx/" + SFX[i] + ".ogg"), 1);
        } catch (Throwable t) {
            Log.w(TAG, "intro sounds", t);
            pool = null;
        }
    }

    private void sfx(int which, float vol, float rate) {
        if (pool == null || done) return;
        try { pool.play(sid[which], vol, vol, 1, 0, rate); } catch (Throwable ignored) { }
    }

    /** play a timeline sound once, when t passes 'at' (slot = unique index) */
    private void cue(int slot, float t, float at, int which, float vol, float rate) {
        if (!fired[slot] && t >= at) {
            fired[slot] = true;
            if (t - at < 0.25f) sfx(which, vol, rate);      // never late sounds after a stall
        }
    }

    IntroView(Activity a) {
        super(a);
        act = a;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        loadSounds(a);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return !done;                 // the menu below is not clickable during the intro
    }

    static void introDone() {
        try { if (NovaActivity.nativeOk) NovaActivity.nativeIntroDone(); } catch (Throwable ignored) { }
    }

    private void finish() {
        if (done) return;
        done = true;
        introDone();
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    setVisibility(GONE);
                    ViewGroup p = (ViewGroup) getParent();
                    if (p != null) p.removeView(IntroView.this);
                } catch (Throwable t) {
                    Log.w(TAG, "remove", t);
                }
                if (current == IntroView.this) current = null;
                final android.media.SoundPool p = pool;
                if (p != null) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { public void run() { p.release(); } }, 2500);
            }
        });
    }

    // ---- GL state ------------------------------------------------------------------
    private int prog, aPos, aUv, uMvp, uTex, uTint, uCut, uSweep;
    private int texMesh, texFlat, texR, texC, texGlow, texWhite;
    private float rAspect, cAspect;
    private FloatBuffer quadBuf;
    private int W = 1, H = 1;
    private long t0;
    private float handT = -1;                 // time the hand-over started
    private float readyAt = -1;

    // mesh: per letter group
    private final ArrayList<float[]> gVerts = new ArrayList<float[]>();   // x,y,z,u,v * 6 per quad
    private final ArrayList<float[]> gCenter = new ArrayList<float[]>();
    private FloatBuffer meshBuf;
    private int[] gFirst, gCount;
    private final float[] fit = new float[16];
    private float[] rectBig = new float[4], rectMenu = new float[4];   // x0,y0,x1,y1 in px

    // particles
    private final ArrayList<float[]> parts = new ArrayList<float[]>();   // x,y,vx,vy,age,life,size,r,g,b
    private final boolean[] landed = new boolean[8];
    private final Random rnd = new Random(7);

    static final String VS =
            "uniform mat4 uMvp;attribute vec3 aPos;attribute vec2 aUv;varying vec2 vUv;" +
            "void main(){vUv=aUv;gl_Position=uMvp*vec4(aPos,1.0);}";
    // textures are premultiplied (Android bitmaps); uSweep: x = band position (px), y = half width, z = strength
    static final String FS =
            "precision mediump float;uniform sampler2D uTex;uniform vec4 uTint;uniform float uCut;uniform vec3 uSweep;" +
            "varying vec2 vUv;void main(){vec4 c=texture2D(uTex,vUv);if(c.a<uCut)discard;" +
            "float s=uSweep.z*max(0.0,1.0-abs(gl_FragCoord.x+gl_FragCoord.y*0.55-uSweep.x)/uSweep.y);" +
            "gl_FragColor=vec4((c.rgb*uTint.rgb+vec3(s)*c.a)*uTint.a,c.a*uTint.a);}";

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig cfg) {
        try {
            prog = program(VS, FS);
            aPos = GLES20.glGetAttribLocation(prog, "aPos");
            aUv = GLES20.glGetAttribLocation(prog, "aUv");
            uMvp = GLES20.glGetUniformLocation(prog, "uMvp");
            uTex = GLES20.glGetUniformLocation(prog, "uTex");
            uTint = GLES20.glGetUniformLocation(prog, "uTint");
            uCut = GLES20.glGetUniformLocation(prog, "uCut");
            uSweep = GLES20.glGetUniformLocation(prog, "uSweep");
            quadBuf = ByteBuffer.allocateDirect(6 * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            loadMesh();
            if (W > 1) computeFit();
            texMesh = texture(asset("nova/intro/title_tex.png"), true);
            texFlat = texture(asset("nova/intro/title_flat.png"), false);
            Bitmap r = letter("R"), c = letter("C");
            rAspect = (float) r.getWidth() / r.getHeight();
            cAspect = (float) c.getWidth() / c.getHeight();
            texR = texture(r, false);
            texC = texture(c, false);
            texGlow = texture(glow(), false);
            Bitmap wb = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            wb.eraseColor(0xffffffff);
            texWhite = texture(wb, false);
        } catch (Throwable t) {
            Log.e(TAG, "intro init failed", t);
            prog = 0;
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        W = Math.max(1, w);
        H = Math.max(1, h);
        GLES20.glViewport(0, 0, W, H);
        // big centred title, then the menu's title rect
        float tw = Math.min(W * 0.74f, H * 0.36f * TITLE_ASPECT);
        float cx = W / 2f, cy = H * 0.47f;
        rectBig = new float[]{cx - tw / 2, cy - tw / TITLE_ASPECT / 2, cx + tw / 2, cy + tw / TITLE_ASPECT / 2};
        float mw = W * MENU_W, mt = H * MENU_TOP;
        rectMenu = new float[]{cx - mw / 2, mt, cx + mw / 2, mt + mw / TITLE_ASPECT};
        computeFit();
    }

    // ---- frame -------------------------------------------------------------------------
    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        if (t0 == 0) t0 = now;
        float t = (now - t0) / 1e9f;
        if (prog == 0 || done) {                      // broken init: just get out of the way
            GLES20.glClearColor(0, 0, 0, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            finish();
            return;
        }
        if (readyAt < 0 && gameReady(t)) readyAt = t;
        if (handT < 0 && t >= HAND && readyAt >= 0) handT = t;
        if (handT < 0 && t > MAX_WAIT) handT = t;

        float bg = 1f;
        if (handT >= 0) bg = 1f - clamp((t - handT - XFADE - BG_DELAY) / BG_D);
        GLES20.glClearColor(0, 0, 0, bg);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(prog);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(uTex, 0);

        cue(0, t, RC_IN - 0.05f, S_RC_IN, 0.8f, 1f);
        cue(1, t, RC_SWEEP, S_RC_SHINE, 0.55f, 1f);
        for (int i = 0; i < 4; i++) cue(2 + i, t, TITLE + STAGGER * i, S_WHOOSH, 0.55f, 0.9f + 0.07f * i);
        cue(8, t, TITLE + STAGGER * 3 + FLY + 0.12f, S_SHINE, 0.6f, 1f);
        if (handT >= 0) cue(9, t, handT + XFADE, S_RISE, 0.45f, 1f);
        if (t < RC_OUT + RC_OUT_D + 0.05f) drawRc(t);
        if (t >= TITLE) {
            if (handT < 0 || t < handT + XFADE) drawTitle3d(t, handT < 0 ? 1f : 1f - clamp((t - handT) / XFADE));
            if (handT >= 0) drawFlat(t);
        }
        drawParticles(t);
        // the menu title appears while the flat title still covers it, and the intro leaves a few frames later:
        // no frame with neither of them
        if (handT >= 0 && t > handT + XFADE + MOVE && !titleShown) { titleShown = true; introDone(); shownAt = t; }
        if (handT >= 0 && titleShown && t > shownAt + 0.15f && t > handT + XFADE + Math.max(MOVE, BG_DELAY + BG_D) + 0.05f) finish();
    }

    private boolean gameReady(float t) {
        try {
            if (NovaActivity.nativeOk) return NovaActivity.nativeInfo(10) == 1;
        } catch (Throwable ignored) { }
        return t > 9f;
    }

    // ---- 1. studio logo -------------------------------------------------------------------
    private void drawRc(float t) {
        float in = easeOutCubic(clamp((t - RC_IN) / RC_IN_D));
        float out = easeInCubic(clamp((t - RC_OUT) / RC_OUT_D));
        float a = clamp((t - RC_IN) / (RC_IN_D * 0.6f)) * (1f - out);
        if (a <= 0) return;
        float h = H * 0.26f * (0.9f + 0.1f * in) * (1f + 0.05f * out);
        float gap = H * 0.012f;
        float rw = h * rAspect, cw = h * cAspect;
        float total = rw + gap + cw;
        float cx = W / 2f, cy = H / 2f - H * 0.01f;
        float slide = (1f - easeOutExpo(clamp((t - RC_IN) / (RC_IN_D * 1.1f)))) * W * 0.09f;
        // soft light behind the letters
        float gw = h * 3.4f;
        draw2d(texGlow, cx - gw / 2, cy - gw / 2, cx + gw / 2, cy + gw / 2, 1, 1, 1, 0.22f * a, 0, null);
        float sweepX = -1;
        float sp = (t - RC_SWEEP) / 0.6f;
        if (sp > 0 && sp < 1) sweepX = (cx - total) + sp * total * 2.4f;
        float[] sw = sweepX < 0 ? null : new float[]{sweepX, h * 0.35f, 0.55f};
        float x0 = cx - total / 2;
        draw2d(texR, x0 - slide, cy - h / 2, x0 - slide + rw, cy + h / 2, 1, 1, 1, a, 0, sw);
        draw2d(texC, x0 + rw + gap + slide, cy - h / 2, x0 + rw + gap + slide + cw, cy + h / 2, 1, 1, 1, a, 0, sw);
    }

    // ---- 2. 3D title ----------------------------------------------------------------------
    private void drawTitle3d(float t, float fade) {
        float ct = easeOutCubic(clamp((t - TITLE) / CAM_D));
        float yaw = 24f * (1f - ct), dist = DIST + 170f * (1f - ct), elev = ELEV - 8f * (1f - ct);
        float[] P = new float[16], V = new float[16], PV = new float[16], F = new float[16], M = new float[16], MVP = new float[16];
        Matrix.perspectiveM(P, 0, FOV, TITLE_ASPECT, 4f, 3000f);
        view(V, elev, yaw, dist);
        Matrix.multiplyMM(PV, 0, P, 0, V, 0);
        System.arraycopy(fit, 0, F, 0, 16);
        // after landing: small pop, then a gentle float while waiting for the game
        float last = TITLE + STAGGER * (gCount.length - 1) + FLY;
        float pop = t > last ? (float) Math.sin(Math.min(1f, (t - last) / 0.32f) * Math.PI) * 0.035f : 0f;
        float bob = t > last + 0.4f ? (float) Math.sin((t - last - 0.4f) * 2.0f) * 0.012f : 0f;
        scaleAbout(F, 1f + pop, bob);
        float sweep = -1;
        float sp = (t - (last + 0.12f)) / 0.7f;
        if (handT < 0 && t > last + 2.5f) sp = ((t - last - 2.5f) % 3.2f) / 0.7f;      // repeat while waiting
        float[] sw = null;
        if (sp > 0 && sp < 1) {
            float bx0 = rectBig[0], bx1 = rectBig[2];
            sw = new float[]{bx0 - 200 + sp * (bx1 - bx0 + H * 0.9f), H * 0.10f, 0.45f};
        }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glFrontFace(GLES20.GL_CW);              // quads: positive area with screen y down = CW in GL (y up)
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texMesh);
        GLES20.glUniform1f(uCut, 0.5f);
        GLES20.glUniform3f(uSweep, sw == null ? -1e5f : sw[0], sw == null ? 1f : sw[1], sw == null ? 0f : sw[2]);
        meshBuf.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, meshBuf);
        GLES20.glEnableVertexAttribArray(aPos);
        meshBuf.position(3);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, meshBuf);
        GLES20.glEnableVertexAttribArray(aUv);
        int n = gCount.length;
        for (int i = 0; i < n; i++) {
            float st = TITLE + STAGGER * i;
            float p = clamp((t - st) / FLY);
            if (p <= 0) continue;
            letterModel(M, i, n, p);
            float[] FPV = new float[16];
            Matrix.multiplyMM(FPV, 0, F, 0, PV, 0);
            Matrix.multiplyMM(MVP, 0, FPV, 0, M, 0);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, MVP, 0);
            float light = Math.min(1f, p * 3.2f);                       // out of the dark
            float flash = p >= HIT ? Math.max(0f, 1f - (t - st - FLY * HIT) / 0.3f) * 0.6f : 0f;
            float k = light * (1f + flash) * fade;
            GLES20.glUniform4f(uTint, k, k, k, fade < 1f ? fade : 1f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, gFirst[i], gCount[i]);
            if (p >= HIT && i < landed.length && !landed[i]) {
                landed[i] = true;
                sfx(S_HIT, 0.9f, HIT_RATE[i % HIT_RATE.length]);
                burst(MVP, gCenter.get(i));
            }
        }
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    /** letter i: flies in from the depth, spinning, overshoots towards the viewer, settles */
    private void letterModel(float[] M, int i, int n, float p) {
        float[] c = gCenter.get(i);
        float side = (i - (n - 1) / 2f);                     // left letters negative
        float e = easeOutBack(p, 1.35f);
        float k = 1f - e;
        float ox = -side * 95f * k, oy = (i % 2 == 0 ? 120f : -95f) * k, oz = 760f * k;
        float yaw = (i % 2 == 0 ? -1 : 1) * 250f * k, pitch = (i % 2 == 0 ? 40f : -35f) * k, roll = side * 22f * k;
        Matrix.setIdentityM(M, 0);
        Matrix.translateM(M, 0, c[0] + ox, c[1] + oy, c[2] + oz);
        Matrix.rotateM(M, 0, yaw, 0, 1, 0);
        Matrix.rotateM(M, 0, pitch, 1, 0, 0);
        Matrix.rotateM(M, 0, roll, 0, 0, 1);
        Matrix.translateM(M, 0, -c[0], -c[1], -c[2]);
    }

    private void view(float[] V, float elevDeg, float yawDeg, float dist) {
        double a = Math.toRadians(elevDeg), y = Math.toRadians(yawDeg);
        float ex = (float) (-Math.cos(a) * Math.sin(y) * dist);
        float ey = TGT_Y - (float) (Math.sin(a) * dist);
        float ez = (float) (-Math.cos(a) * Math.cos(y) * dist);
        Matrix.setLookAtM(V, 0, ex, ey, ez, 0, TGT_Y, 0, 0, 1, 0);
    }

    /** clip-space fit: the final pose's bounding box -> rectBig */
    private void computeFit() {
        float[] P = new float[16], V = new float[16], PV = new float[16];
        Matrix.perspectiveM(P, 0, FOV, TITLE_ASPECT, 4f, 3000f);
        view(V, ELEV, 0, DIST);
        Matrix.multiplyMM(PV, 0, P, 0, V, 0);
        float x0 = 1e9f, x1 = -1e9f, y0 = 1e9f, y1 = -1e9f;
        float[] in = new float[4], out = new float[4];
        for (float[] g : gVerts) {
            for (int k = 0; k < g.length; k += 5) {
                in[0] = g[k]; in[1] = g[k + 1]; in[2] = g[k + 2]; in[3] = 1;
                Matrix.multiplyMV(out, 0, PV, 0, in, 0);
                float x = out[0] / out[3], y = out[1] / out[3];
                x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
            }
        }
        float rx0 = rectBig[0] / W * 2 - 1, rx1 = rectBig[2] / W * 2 - 1;
        float ry0 = 1 - rectBig[3] / H * 2, ry1 = 1 - rectBig[1] / H * 2;
        Matrix.setIdentityM(fit, 0);
        fit[0] = (rx1 - rx0) / (x1 - x0);
        fit[5] = (ry1 - ry0) / (y1 - y0);
        fit[12] = rx0 - x0 * fit[0];
        fit[13] = ry0 - y0 * fit[5];
    }

    /** scale the fitted title about rectBig's centre (clip space), plus a vertical bob */
    private void scaleAbout(float[] F, float s, float bob) {
        float cx = (rectBig[0] + rectBig[2]) / W - 1, cy = 1 - (rectBig[1] + rectBig[3]) / H;
        float[] S = new float[16], R = new float[16];
        Matrix.setIdentityM(S, 0);
        S[0] = s; S[5] = s;
        S[12] = cx * (1 - s);
        S[13] = cy * (1 - s) + bob;
        Matrix.multiplyMM(R, 0, S, 0, F, 0);
        System.arraycopy(R, 0, F, 0, 16);
    }

    // ---- 3. flat title -> menu ---------------------------------------------------------------
    private void drawFlat(float t) {
        float a = clamp((t - handT) / XFADE);
        float m = easeInOutCubic(clamp((t - handT - XFADE) / MOVE));
        float last = TITLE + STAGGER * (gCount.length - 1) + FLY;
        float bob = t > last + 0.4f ? (float) Math.sin((handT - last - 0.4f) * 2.0f) * 0.012f * H / 2 : 0f;
        bob *= (1f - m);
        float x0 = lerp(rectBig[0], rectMenu[0], m), y0 = lerp(rectBig[1] - bob, rectMenu[1], m);
        float x1 = lerp(rectBig[2], rectMenu[2], m), y1 = lerp(rectBig[3] - bob, rectMenu[3], m);
        draw2d(texFlat, x0, y0, x1, y1, 1, 1, 1, a, 0, null);
    }

    // ---- particles -----------------------------------------------------------------------------
    private void burst(float[] mvp, float[] c) {
        float[] o = new float[4];
        Matrix.multiplyMV(o, 0, mvp, 0, new float[]{c[0], c[1], c[2], 1}, 0);
        if (o[3] <= 0) return;
        float sx = (o[0] / o[3] * 0.5f + 0.5f) * W, sy = (1 - (o[1] / o[3] * 0.5f + 0.5f)) * H;
        for (int k = 0; k < 16; k++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            float sp = H * (0.25f + rnd.nextFloat() * 0.55f);
            float bright = 0.75f + rnd.nextFloat() * 0.25f;
            boolean white = rnd.nextInt(3) == 0;
            parts.add(new float[]{sx + (rnd.nextFloat() - .5f) * H * 0.12f, sy + (rnd.nextFloat() - .5f) * H * 0.10f,
                    (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp - H * 0.25f, 0, 0.55f + rnd.nextFloat() * 0.35f,
                    H * (0.008f + rnd.nextFloat() * 0.012f),
                    white ? 1 : 0.45f * bright, white ? 1 : 0.9f * bright, 1});
        }
    }

    private long lastPartNs;

    private void drawParticles(float t) {
        long now = System.nanoTime();
        float dt = lastPartNs == 0 ? 0 : Math.min(0.05f, (now - lastPartNs) / 1e9f);
        lastPartNs = now;
        for (int i = parts.size() - 1; i >= 0; i--) {
            float[] p = parts.get(i);
            p[4] += dt;
            if (p[4] >= p[5]) { parts.remove(i); continue; }
            p[0] += p[2] * dt; p[1] += p[3] * dt;
            p[2] *= 0.94f; p[3] = p[3] * 0.94f + H * 1.4f * dt;
            float a = 1f - p[4] / p[5];
            float s = p[6] * (0.6f + 0.4f * a);
            draw2d(texWhite, p[0] - s, p[1] - s, p[0] + s, p[1] + s, p[7], p[8], p[9], a, 0, null);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------
    /** textured rectangle in pixels (y down) */
    private void draw2d(int tex, float x0, float y0, float x1, float y1, float r, float g, float b, float a, float cut, float[] sw) {
        float[] M = new float[16];
        Matrix.orthoM(M, 0, 0, W, H, 0, -1, 1);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, M, 0);
        GLES20.glUniform4f(uTint, r, g, b, a);
        GLES20.glUniform1f(uCut, cut);
        GLES20.glUniform3f(uSweep, sw == null ? -1e5f : sw[0], sw == null ? 1f : sw[1], sw == null ? 0f : sw[2]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        float[] v = {x0, y0, 0, 0, 0, x1, y0, 0, 1, 0, x1, y1, 0, 1, 1, x0, y0, 0, 0, 0, x1, y1, 0, 1, 1, x0, y1, 0, 0, 1};
        quadBuf.clear();
        quadBuf.put(v);
        quadBuf.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, quadBuf);
        GLES20.glEnableVertexAttribArray(aPos);
        quadBuf.position(3);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, quadBuf);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
    }

    private void loadMesh() throws Exception {
        gVerts.clear();
        gCenter.clear();
        byte[] d = readAll(getContext().getAssets().open("nova/intro/title_mesh.bin"));
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x314d564e) throw new Exception("bad mesh");     // "NVM1"
        int ng = b.getShort() & 0xffff;
        int total = 0;
        for (int g = 0; g < ng; g++) {
            int ln = b.getShort() & 0xffff;
            b.position(b.position() + ln);
            gCenter.add(new float[]{b.getFloat(), b.getFloat(), b.getFloat()});
            int nq = b.getShort() & 0xffff;
            float[] v = new float[nq * 6 * 5];
            int o = 0;
            for (int q = 0; q < nq; q++) {
                float[][] c = new float[4][5];
                for (int k = 0; k < 4; k++) for (int j = 0; j < 5; j++) c[k][j] = b.getFloat();
                int[] idx = {0, 1, 2, 0, 2, 3};
                for (int k : idx) { System.arraycopy(c[k], 0, v, o, 5); o += 5; }
            }
            gVerts.add(v);
            total += v.length;
        }
        meshBuf = ByteBuffer.allocateDirect(total * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        gFirst = new int[ng];
        gCount = new int[ng];
        int first = 0;
        for (int g = 0; g < ng; g++) {
            float[] v = gVerts.get(g);
            meshBuf.put(v);
            gFirst[g] = first;
            gCount[g] = v.length / 5;
            first += gCount[g];
        }
        meshBuf.position(0);
    }

    private Bitmap asset(String p) throws Exception {
        InputStream in = getContext().getAssets().open(p);
        try {
            return BitmapFactory.decodeStream(in);
        } finally {
            in.close();
        }
    }

    /** one studio-logo letter: heavy sans, white -> grey from top to bottom */
    private static Bitmap letter(String s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setTextSize(360);
        Rect r = new Rect();
        p.getTextBounds(s, 0, s.length(), r);
        int pad = 6;
        Bitmap b = Bitmap.createBitmap(r.width() + pad * 2, r.height() + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        p.setShader(new LinearGradient(0, pad, 0, pad + r.height(), 0xffffffff, 0xff7a7a7a, Shader.TileMode.CLAMP));
        c.drawText(s, pad - r.left, pad - r.top, p);
        return b;
    }

    private static Bitmap glow() {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(128, 128, 128, new int[]{0x66ffffff, 0x18ffffff, 0x00ffffff},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 256, 256, p);
        return b;
    }

    private static int texture(Bitmap b, boolean pixelArt) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, pixelArt ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
        return id[0];
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }

    static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float easeOutCubic(float t) { float u = 1 - t; return 1 - u * u * u; }
    static float easeInCubic(float t) { return t * t * t; }
    static float easeInOutCubic(float t) { return t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; }
    static float easeOutExpo(float t) { return t >= 1 ? 1 : 1 - (float) Math.pow(2, -10 * t); }
    static float easeOutBack(float t, float s) { float u = t - 1; return 1 + (s + 1) * u * u * u + s * u * u; }
}
