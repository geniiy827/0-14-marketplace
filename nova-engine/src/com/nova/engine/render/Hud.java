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

    private AssetManager assets;

    public void init(AssetManager game) {
        assets = game;
        java.util.Arrays.fill(uniTex, 0); java.util.Arrays.fill(uniTried, false);
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

    // ------------------------------------------------------------------ transform (rotated / scaled text)
    private boolean xf;
    private float xa, xb, xc, xd, xtx, xty;

    /** following quads: p' = (tx, ty) + R(deg) * s * p */
    public void transform(float tx, float ty, float deg, float scale) {
        double r = Math.toRadians(deg);
        xa = (float) Math.cos(r) * scale; xb = (float) -Math.sin(r) * scale; xc = (float) Math.sin(r) * scale; xd = (float) Math.cos(r) * scale;
        xtx = tx; xty = ty; xf = true;
    }

    public void noTransform() { xf = false; }

    /** vertical gradient (ScreenRenderer::fillGradient) */
    public void gradient(float x, float y, float rw, float rh, int top, int bottom) {
        use(white);
        float r0 = ((top >> 16) & 255) / 255f, g0 = ((top >> 8) & 255) / 255f, b0 = (top & 255) / 255f, a0 = ((top >>> 24) & 255) / 255f;
        float r1 = ((bottom >> 16) & 255) / 255f, g1 = ((bottom >> 8) & 255) / 255f, b1 = (bottom & 255) / 255f, a1 = ((bottom >>> 24) & 255) / 255f;
        put(x, y, 0, 0, r0, g0, b0, a0); put(x + rw, y, 1, 0, r0, g0, b0, a0); put(x + rw, y + rh, 1, 1, r1, g1, b1, a1);
        put(x, y, 0, 0, r0, g0, b0, a0); put(x + rw, y + rh, 1, 1, r1, g1, b1, a1); put(x, y + rh, 0, 1, r1, g1, b1, a1);
        quads++;
    }

    // ------------------------------------------------------------------ Font of 0.14.3 (default8 + unicode glyph pages)
    private final int[] uniTex = new int[256];
    private final boolean[] uniTried = new boolean[256];
    private final byte[][] uniStart = new byte[256][], uniEnd = new byte[256][];

    /** font/glyph_XX.png: 16 x 16 cells of 16 px; per char the first and last used column (Font::_scanUnicodeCharacterWidth) */
    private boolean uniPage(int page) {
        if (uniTried[page]) return uniTex[page] != 0;
        uniTried[page] = true;
        if (assets == null) return false;
        try {
            Bitmap b = BitmapFactory.decodeStream(assets.open(String.format(java.util.Locale.ROOT, "images/font/glyph_%02X.png", page)));
            if (b == null) return false;
            int cell = b.getWidth() / 16;
            byte[] st = new byte[256], en = new byte[256];
            for (int c = 0; c < 256; c++) {
                int gx = (c % 16) * cell, gy = (c / 16) * cell, lo = cell, hi = -1;
                for (int x = 0; x < cell; x++) for (int y = 0; y < cell; y++)
                    if ((b.getPixel(gx + x, gy + y) >>> 24) > 0) { if (x < lo) lo = x; if (x > hi) hi = x; }
                if (hi < 0) { lo = 0; hi = cell / 2 - 1; }
                st[c] = (byte) lo; en[c] = (byte) hi;
            }
            uniStart[page] = st; uniEnd[page] = en;
            uniTex[page] = tex(b, false);
            return true;
        } catch (Exception e) { return false; }
    }

    /** advance of one char in font units (8 per line) */
    private float charW(int cp) {
        if (cp == 0xA0) cp = 32;
        if (cp < 128) return glyphW[cp];
        int page = cp >> 8;
        if (page > 255 || !uniPage(page)) return 4;
        int st = uniStart[page][cp & 255], en = uniEnd[page][cp & 255];
        return (en + 1 - st) / 2f + 1f;
    }

    /** width of a text in font units */
    public float fontWidth(String s) {
        float x = 0;
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); i += Character.charCount(cp); x += charW(cp); }
        return x;
    }

    /** text at scale sc (pixels per font unit), optional shadow (1 unit, colour / 4) */
    public float fontText(String s, float x, float y, float sc, int argb, boolean shadow) {
        if (shadow) fontText0(s, x + sc, y + sc, sc, (argb & 0xFF000000) | ((argb >> 2) & 0x3F3F3F));
        return fontText0(s, x, y, sc, argb);
    }

    private float fontText0(String s, float x, float y, float sc, int argb) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i); i += Character.charCount(cp);
            if (cp == 0xA0) cp = 32;
            if (cp < 128) {
                use(font);
                float u = (cp % 16) / 16f, v = (cp / 16) / 16f;
                quad(x, y, 8 * sc, 8 * sc, u, v, u + 1 / 16f, v + 1 / 16f, argb);
            } else if ((cp >> 8) <= 255 && uniPage(cp >> 8)) {
                int page = cp >> 8, c = cp & 255;
                int st = uniStart[page][c], en = uniEnd[page][c];
                use(uniTex[page]);
                float u0 = ((c % 16) * 16 + st) / 256f, u1 = ((c % 16) * 16 + en + 1) / 256f, v0 = (c / 16) / 16f;
                quad(x, y, (en + 1 - st) / 2f * sc, 8 * sc, u0, v0, u1, v0 + 1 / 16f, argb);
            }
            x += charW(cp) * sc;
        }
        return x;
    }

    private void put(float x, float y, float u, float v, float r, float g, float b, float a) {
        if (xf) { float nx = xtx + xa * x + xb * y, ny = xty + xc * x + xd * y; x = nx; y = ny; }
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

    /** white ring (inner > 0) or disc drawn with ui_invert_overlay blending (1 - dst): inverts what is under it */
    public void invertCircle(float cx, float cy, float outer, float inner, int n) { invertCircle(cx, cy, outer, inner, n, 1f); }

    /** ui_invert_overlay with ShaderColor alpha a: dst -> mix(dst, 1 - dst, a) */
    public void invertCircle(float cx, float cy, float outer, float inner, int n, float a) {
        flush();
        curTex = white;
        GLES30.glBlendFunc(GLES30.GL_ONE_MINUS_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_COLOR);
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            float ox0 = cx + (float) Math.cos(a0) * outer, oy0 = cy + (float) Math.sin(a0) * outer;
            float ox1 = cx + (float) Math.cos(a1) * outer, oy1 = cy + (float) Math.sin(a1) * outer;
            float ix0 = cx + (float) Math.cos(a0) * inner, iy0 = cy + (float) Math.sin(a0) * inner;
            float ix1 = cx + (float) Math.cos(a1) * inner, iy1 = cy + (float) Math.sin(a1) * inner;
            put(ix0, iy0, 0, 0, a, a, a, a); put(ox0, oy0, 0, 0, a, a, a, a); put(ox1, oy1, 0, 0, a, a, a, a);
            put(ix0, iy0, 0, 0, a, a, a, a); put(ox1, oy1, 0, 0, a, a, a, a); put(ix1, iy1, 0, 0, a, a, a, a);
            quads++;
        }
        flush();
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
    }

    public void end() {
        flush();
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }
}
