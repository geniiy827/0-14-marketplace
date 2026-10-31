package com.nova.engine.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.nio.FloatBuffer;

/**
 * 2D layer in screen pixels: coloured / textured quads and text. One dynamic VBO, batched per texture.
 * Font: the game's ASCII font (images/font/default8.png) when the game is installed, else a monospace bitmap.
 */
public final class Hud {
    static final String VS =
            "#version 300 es\nlayout(location=0) in vec2 aPos; layout(location=1) in vec2 aUv; layout(location=2) in vec4 aCol;\n" +
            "uniform vec2 uScreen; out vec2 vUv; out vec4 vCol;\n" +
            "void main(){ gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0, 1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0); vUv = aUv; vCol = aCol; }\n";
    static final String FS =
            "#version 300 es\nprecision mediump float; uniform sampler2D uTex; in vec2 vUv; in vec4 vCol; out vec4 o;\n" +
            "void main(){ o = texture(uTex, vUv) * vCol; }\n";

    private int prog, uScreen, uTex, vbo, white, font;
    private final FloatBuffer fb = Gl.floats(8 * 6 * 4096);
    private int quads, curTex;
    public float w, h;
    private final float[] glyphW = new float[256];
    private int fontCell = 8;

    public void init(AssetManager game) {
        prog = Gl.program(VS, FS);
        uScreen = GLES30.glGetUniformLocation(prog, "uScreen");
        uTex = GLES30.glGetUniformLocation(prog, "uTex");
        vbo = Gl.buffer();
        Bitmap wb = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        wb.eraseColor(Color.WHITE);
        white = tex(wb, false);
        Bitmap fbm = null;
        if (game != null) {
            try { fbm = BitmapFactory.decodeStream(game.open("images/font/default8.png")); } catch (Exception ignored) { }
        }
        if (fbm != null) {
            fontCell = fbm.getWidth() / 16;
            for (int c = 0; c < 256; c++) {                 // glyph widths from the pixels
                int gx = (c % 16) * fontCell, gy = (c / 16) * fontCell, maxX = -1;
                for (int y = 0; y < fontCell; y++) for (int x = 0; x < fontCell; x++)
                    if ((fbm.getPixel(gx + x, gy + y) >>> 24) > 0 && x > maxX) maxX = x;
                glyphW[c] = c == 32 ? fontCell * 0.5f : maxX + 2;
            }
        } else {
            fbm = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(fbm);
            Paint p = new Paint();
            p.setColor(Color.WHITE);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(14);
            p.setAntiAlias(false);
            fontCell = 16;
            for (int c = 32; c < 127; c++) {
                cv.drawText(String.valueOf((char) c), (c % 16) * 16 + 2, (c / 16) * 16 + 13, p);
                glyphW[c] = 10;
            }
        }
        font = tex(fbm, false);
    }

    /** a texture from the assets (0 when missing) */
    public int loadTex(AssetManager am, String path) {
        if (am == null) return 0;
        try {
            Bitmap b = BitmapFactory.decodeStream(am.open(path));
            return b == null ? 0 : tex(b, false);
        } catch (Exception e) { return 0; }
    }

    static int tex(Bitmap b, boolean linear) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        int f = linear ? GLES30.GL_LINEAR : GLES30.GL_NEAREST;
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, f);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, f);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    public void begin(float sw, float sh) {
        w = sw; h = sh;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(prog);
        GLES30.glUniform2f(uScreen, sw, sh);
        GLES30.glUniform1i(uTex, 0);
        quads = 0;
        curTex = white;
        fb.clear();
    }

    private void use(int tex) {
        if (tex != curTex) { flush(); curTex = tex; }
        if (quads >= 4096) flush();
    }

    public void rect(float x, float y, float rw, float rh, int argb) {
        use(white);
        quad(x, y, rw, rh, 0, 0, 1, 1, argb);
    }

    public void image(int tex, float x, float y, float rw, float rh, float u0, float v0, float u1, float v1, int argb) {
        use(tex);
        quad(x, y, rw, rh, u0, v0, u1, v1, argb);
    }

    /** text with a 1px shadow; size = pixel height */
    public float text(String s, float x, float y, float size, int argb) {
        float sc = size / fontCell;
        text0(s, x + sc, y + sc, sc, (argb & 0xFF000000) | ((argb >> 2) & 0x3F3F3F));
        return text0(s, x, y, sc, argb);
    }

    public float textWidth(String s, float size) {
        float sc = size / fontCell, x = 0;
        for (int i = 0; i < s.length(); i++) x += glyphW[s.charAt(i) & 255] * sc;
        return x;
    }

    private float text0(String s, float x, float y, float sc, int argb) {
        use(font);
        float cs = fontCell * sc;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i) & 255;
            float u = (c % 16) / 16f, v = (c / 16) / 16f;
            quad(x, y, cs, cs, u, v, u + 1 / 16f, v + 1 / 16f, argb);
            x += glyphW[c] * sc;
            if (quads >= 4096) flush();
        }
        return x;
    }

    private void quad(float x, float y, float rw, float rh, float u0, float v0, float u1, float v1, int argb) {
        float r = ((argb >> 16) & 255) / 255f, g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f, a = ((argb >>> 24) & 255) / 255f;
        put(x, y, u0, v0, r, g, b, a);
        put(x + rw, y, u1, v0, r, g, b, a);
        put(x + rw, y + rh, u1, v1, r, g, b, a);
        put(x, y, u0, v0, r, g, b, a);
        put(x + rw, y + rh, u1, v1, r, g, b, a);
        put(x, y + rh, u0, v1, r, g, b, a);
        quads++;
    }

    private void put(float x, float y, float u, float v, float r, float g, float b, float a) {
        fb.put(x); fb.put(y); fb.put(u); fb.put(v); fb.put(r); fb.put(g); fb.put(b); fb.put(a);
    }

    public void flush() {
        if (quads == 0) return;
        int n = fb.position();
        fb.position(0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curTex);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 4, fb, GLES30.GL_STREAM_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 32, 0);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 32, 8);
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 32, 16);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, quads * 6);
        fb.clear();
        quads = 0;
    }

    public void end() {
        flush();
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }
}
