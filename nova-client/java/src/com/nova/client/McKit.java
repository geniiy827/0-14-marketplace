package com.nova.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft-style widgets for the NOVA native screens. Everything is drawn from the
 * game's own textures (gui atlases, font pages) at an integer pixel scale, tinted with
 * the same palette as the JSON UI theme. Enabled resource packs are respected.
 */
public final class McKit {
    private McKit() { }

    // ---------------------------------------------------------------- palette (ARGB tints)
    public static final int BTN = 0xFF7887AD;        // slate button      [0.47,0.53,0.68]
    public static final int BTN_HOVER = 0xFF57CCD1;  // teal              [0.34,0.80,0.82]
    public static final int BTN_PRESS = 0xFF3D9EA8;  // deep teal
    public static final int BTN_PRIMARY = 0xFF4FB9BD;
    public static final int BTN_DANGER = 0xFFC4505F;
    public static final int PANEL = 0xFF707D9E;      // window body       [0.44,0.49,0.62]
    public static final int FIELD = 0xFF9EB8FF;      // inset field tint  [0.62,0.72,1.00]
    public static final int ACCENT = 0xFF66F2E0;
    public static final int VIOLET = 0xFFAD85FF;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY = 0xFFC9D0E2;
    public static final int DIM = 0xFF9AA4BE;
    public static final int RED = 0xFFFF7A86;
    public static final int GOLD = 0xFFFFE27A;

    // ---------------------------------------------------------------- scale
    /** Device pixels per GUI unit. Always even, so the half-size unicode font stays crisp. */
    public static int scale(Context c) {
        int h = Math.min(c.getResources().getDisplayMetrics().heightPixels, c.getResources().getDisplayMetrics().widthPixels);
        int s = 2 * Math.round(h / 480f);
        return Math.max(2, s);
    }

    // ---------------------------------------------------------------- assets
    private static final Map<String, Bitmap> cache = new HashMap<String, Bitmap>();

    /** Texture by game path relative to assets/images (e.g. "gui/gui.png"). */
    public static synchronized Bitmap tex(Context c, String path) {
        Bitmap b = cache.get(path);
        if (b != null) return b;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPremultiplied = true;
        try {
            File f = NovaActivity.overrideFile("images/" + path);
            if (f != null) b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) {
                InputStream in = c.getAssets().open("images/" + path);
                try { b = BitmapFactory.decodeStream(in, null, o); } finally { in.close(); }
            }
        } catch (Exception ignored) { }
        if (b != null) cache.put(path, b);
        return b;
    }

    private static Paint pixelPaint(int tint, int alpha) {
        Paint p = new Paint();
        p.setFilterBitmap(false);
        p.setAntiAlias(false);
        p.setDither(false);
        if (tint != WHITE) p.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY));
        p.setAlpha(alpha);
        return p;
    }

    /** Nine-slice draw: src rect of the texture with a border (texels), scaled by s px per texel. */
    public static void nine(Canvas cv, Bitmap b, Rect src, int border, Rect dst, int s, int tint, int alpha) {
        if (b == null) return;
        Paint p = pixelPaint(tint, alpha);
        int bs = border * s;
        int[] sx = {src.left, src.left + border, src.right - border, src.right};
        int[] sy = {src.top, src.top + border, src.bottom - border, src.bottom};
        int[] dx = {dst.left, dst.left + bs, dst.right - bs, dst.right};
        int[] dy = {dst.top, dst.top + bs, dst.bottom - bs, dst.bottom};
        Rect a = new Rect(), d = new Rect();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            if (sx[i + 1] <= sx[i] || sy[j + 1] <= sy[j] || dx[i + 1] <= dx[i] || dy[j + 1] <= dy[j]) continue;
            a.set(sx[i], sy[j], sx[i + 1], sy[j + 1]);
            d.set(dx[i], dy[j], dx[i + 1], dy[j + 1]);
            cv.drawBitmap(b, a, d, p);
        }
    }

    public static void image(Canvas cv, Bitmap b, Rect src, Rect dst, int tint, int alpha) {
        if (b == null) return;
        cv.drawBitmap(b, src, dst, pixelPaint(tint, alpha));
    }

    // ---------------------------------------------------------------- font

    /** The game's bitmap font: default8.png for ASCII, glyph_XX.png pages (half size) for the rest. */
    public static final class Font {
        private static Bitmap ascii;
        private static final int[] asciiW = new int[128];
        private static final Map<Integer, Bitmap> pages = new HashMap<Integer, Bitmap>();
        private static final Map<Integer, int[]> pageW = new HashMap<Integer, int[]>();
        private static Context ctx;

        static synchronized void init(Context c) {
            if (ascii != null) return;
            ctx = c.getApplicationContext();
            ascii = tex(c, "font/default8.png");
            if (ascii == null) return;
            for (int ch = 33; ch < 127; ch++) {
                int x0 = (ch % 16) * 8, y0 = (ch / 16) * 8, last = -1;
                for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++)
                    if ((ascii.getPixel(x0 + x, y0 + y) >>> 24) != 0) last = Math.max(last, x);
                asciiW[ch] = last + 1;
            }
        }

        private static synchronized int[] page(int p) {
            if (pageW.containsKey(p)) return pageW.get(p);
            Bitmap b = tex(ctx, String.format("font/glyph_%02X.png", p));
            int[] w = null;
            if (b != null && b.getWidth() >= 256) {
                w = new int[256 * 2];  // [first col, last col] per glyph
                for (int g = 0; g < 256; g++) {
                    int x0 = (g & 15) * 16, y0 = (g >> 4) * 16, f = -1, l = -1;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
                        if ((b.getPixel(x0 + x, y0 + y) >>> 24) != 0) { if (f < 0 || x < f) f = x; if (x > l) l = x; }
                    w[g * 2] = f; w[g * 2 + 1] = l;
                }
                pages.put(p, b);
            }
            pageW.put(p, w);
            return w;
        }

        /** Advance of a character in half-units (1 GUI unit = 2 half-units). */
        static int advance(char ch) {
            if (ch == ' ') return 8;
            if (ch < 127 && ch > 32 && ascii != null) return (asciiW[ch] + 1) * 2;
            int[] w = page(ch >> 8);
            if (w == null) return 8;
            int g = ch & 0xff;
            if (w[g * 2] < 0) return 8;
            return (w[g * 2 + 1] - w[g * 2] + 1) + 2;
        }

        public static int width(String s) {   // in half-units
            int n = 0;
            for (int i = 0; i < s.length(); i++) n += advance(s.charAt(i));
            return n;
        }

        /** Draws text with its top-left at (x,y) px; h = px per half-unit. */
        public static void draw(Canvas cv, String s, float x, float y, int h, int color, boolean shadow) {
            if (shadow) drawRun(cv, s, x + 2 * h, y + 2 * h, h, shade(color));
            drawRun(cv, s, x, y, h, color);
        }

        private static void drawRun(Canvas cv, String s, float x, float y, int h, int color) {
            Paint p = pixelPaint(color, 255);
            Rect src = new Rect(), dst = new Rect();
            float cx = x;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                int adv = advance(ch);
                if (ch > 32 && ch < 127 && ascii != null) {
                    int w = asciiW[ch];
                    src.set((ch % 16) * 8, (ch / 16) * 8, (ch % 16) * 8 + w, (ch / 16) * 8 + 8);
                    dst.set((int) cx, (int) y, (int) cx + w * 2 * h, (int) y + 16 * h);
                    cv.drawBitmap(ascii, src, dst, p);
                } else if (ch > 32) {
                    int[] w = page(ch >> 8);
                    Bitmap b = pages.get(ch >> 8);
                    int g = ch & 0xff;
                    if (w != null && b != null && w[g * 2] >= 0) {
                        int x0 = (g & 15) * 16 + w[g * 2], y0 = (g >> 4) * 16, gw = w[g * 2 + 1] - w[g * 2] + 1;
                        src.set(x0, y0, x0 + gw, y0 + 16);
                        dst.set((int) cx, (int) y - 2 * h, (int) cx + gw * h, (int) y + 14 * h);
                        cv.drawBitmap(b, src, dst, p);
                    }
                }
                cx += adv * h;
            }
        }

        static int shade(int c) {
            return 0xFF000000 | (((c >> 16) & 0xff) / 4 << 16) | (((c >> 8) & 0xff) / 4 << 8) | ((c & 0xff) / 4);
        }

        /** Greedy word wrap to a max width in half-units. */
        public static List<String> wrap(String s, int maxW) {
            List<String> out = new ArrayList<String>();
            for (String para : s.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : para.split(" ")) {
                    String t = line.length() == 0 ? word : line + " " + word;
                    if (width(t) > maxW && line.length() > 0) {
                        out.add(line.toString());
                        line = new StringBuilder(word);
                    } else {
                        line = new StringBuilder(t);
                    }
                }
                out.add(line.toString());
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- drawables

    /** Window/panel body: dialog atlas nine-slice tinted with the panel colour. */
    public static final class PanelDrawable extends Drawable {
        private final Context c; private final int tint, alpha;
        public PanelDrawable(Context c, int tint, int alpha) { this.c = c; this.tint = tint; this.alpha = alpha; }
        @Override public void draw(Canvas cv) {
            nine(cv, tex(c, "gui/newgui/dialog-background-atlas.png"), new Rect(0, 80, 16, 96), 4, getBounds(), scale(c), tint, alpha);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Inset field (text boxes, lists). */
    public static final class FieldDrawable extends Drawable {
        private final Context c;
        public FieldDrawable(Context c) { this.c = c; }
        @Override public void draw(Canvas cv) {
            nine(cv, tex(c, "gui/newgui/play_screen/Indent2.png"), new Rect(0, 0, 8, 8), 3, getBounds(), scale(c), FIELD, 255);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Screen background: the menu panorama (resource-pack aware) with a soft dim on top. */
    public static final class BackgroundDrawable extends Drawable {
        private final Context c;
        public BackgroundDrawable(Context c) { this.c = c; }
        @Override public void draw(Canvas cv) {
            Rect r = getBounds();
            Bitmap pano = tex(c, "gui/background/panorama_0.png");
            Paint p = new Paint();
            p.setFilterBitmap(true);
            if (pano != null) {
                float k = Math.max(r.width() / (float) pano.getWidth(), r.height() / (float) pano.getHeight());
                int w = (int) (pano.getWidth() * k), h = (int) (pano.getHeight() * k);
                Rect dst = new Rect(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2);
                cv.drawBitmap(pano, null, dst, p);
            } else {
                cv.drawColor(0xFF1A2236);
            }
            p.setColor(0x8C141A2C);
            cv.drawRect(r, p);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    // ---------------------------------------------------------------- views

    /** Pixel-font text. Wraps to the view width when wrap=true. */
    public static class Text extends View {
        private String text;
        private int color;
        private boolean wrap, center, shadow = true;
        private final int s;

        public Text(Context c, String text, int color) {
            super(c);
            Font.init(c);
            s = scale(c);
            this.text = text == null ? "" : text;
            this.color = color;
        }

        public Text wrap() { wrap = true; return this; }
        public Text center() { center = true; return this; }
        public Text noShadow() { shadow = false; return this; }
        public void setText(String t) { text = t == null ? "" : t; requestLayout(); invalidate(); }
        public void setColor(int c) { color = c; invalidate(); }
        public String getText() { return text; }

        private int half() { return s / 2; }

        @Override protected void onMeasure(int ws, int hs) {
            int h = half();
            int lineH = 20 * h;
            int avail = MeasureSpec.getSize(ws);
            if (wrap && MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED) {
                int lines = Font.wrap(text, Math.max(16, avail / h)).size();
                setMeasuredDimension(avail, lines * lineH + 2 * h);
            } else {
                int w = Font.width(text) * h + 4 * h;
                if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) w = avail;
                else if (MeasureSpec.getMode(ws) == MeasureSpec.AT_MOST) w = Math.min(w, avail);
                setMeasuredDimension(w, lineH);
            }
        }

        @Override protected void onDraw(Canvas cv) {
            int h = half();
            List<String> lines;
            if (wrap) lines = Font.wrap(text, Math.max(16, getWidth() / h));
            else { lines = new ArrayList<String>(); lines.add(ellipsize(text, getWidth() / h - 4)); }
            float y = 2 * h;
            for (String l : lines) {
                float x = center ? (getWidth() - Font.width(l) * h) / 2f : 0;
                Font.draw(cv, l, x, y, h, color, shadow);
                y += 20 * h;
            }
        }

        static String ellipsize(String t, int maxW) {
            if (Font.width(t) <= maxW) return t;
            while (t.length() > 1 && Font.width(t + "...") > maxW) t = t.substring(0, t.length() - 1);
            return t + "...";
        }
    }

    /** Minecraft-style button (NormalButtonStroke nine-slice, same tints as the JSON theme). */
    public static class Button extends View {
        public static final int NORMAL = 0, PRIMARY = 1, DANGER = 2;
        private String label;
        private final int kind, s;
        private boolean pressed;
        private int minW = 60;

        public Button(Context c, String label, int kind, View.OnClickListener l) {
            super(c);
            Font.init(c);
            this.label = label; this.kind = kind; s = scale(c);
            setClickable(true);
            setOnClickListener(l);
        }

        public Button minWidth(int units) { minW = units; return this; }
        public void setLabel(String l) { label = l; requestLayout(); invalidate(); }

        @Override protected void onMeasure(int ws, int hs) {
            int w = Math.max(minW * s, Font.width(label) * s / 2 + 24 * s);
            if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) w = MeasureSpec.getSize(ws);
            else if (MeasureSpec.getMode(ws) == MeasureSpec.AT_MOST) w = Math.min(w, MeasureSpec.getSize(ws));
            setMeasuredDimension(w, 22 * s);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN) { pressed = true; invalidate(); }
            if (e.getAction() == MotionEvent.ACTION_CANCEL || e.getAction() == MotionEvent.ACTION_UP) { pressed = false; invalidate(); }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas cv) {
            int tint;
            if (!isEnabled()) tint = 0xFF5A6278;
            else if (pressed) tint = kind == DANGER ? 0xFF8E3442 : BTN_PRESS;
            else tint = kind == PRIMARY ? BTN_PRIMARY : (kind == DANGER ? BTN_DANGER : BTN);
            Rect r = new Rect(0, 0, getWidth(), getHeight());
            nine(cv, tex(getContext(), "gui/newgui/NormalButtonStroke.png"), new Rect(0, 0, 12, 12), 4, r, s, tint, 255);
            int h = s / 2;
            String t = Text.ellipsize(label, getWidth() / h - 12);
            int color = !isEnabled() ? 0xFFA0A8BC : (pressed ? 0xFFE0FFFA : WHITE);
            float x = (getWidth() - Font.width(t) * h) / 2f;
            float y = (getHeight() - 16 * h) / 2f + (pressed ? s : 0);
            Font.draw(cv, t, x, y, h, color, true);
        }
    }

    /** Classic touch toggle from gui/touchgui.png (same as the JSON options toggles). */
    public static class Toggle extends View {
        public interface Listener { void onToggle(Toggle t, boolean on); }
        private boolean on;
        private Listener listener;
        private final int s;

        public Toggle(Context c, boolean on) { super(c); this.on = on; s = scale(c); setClickable(true); }
        public void setListener(Listener l) { listener = l; }
        public boolean isOn() { return on; }
        public void setOn(boolean v) { on = v; invalidate(); }

        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(38 * s, 20 * s); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                on = !on; invalidate();
                if (listener != null) listener.onToggle(this, on);
                return true;
            }
            return e.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas cv) {
            Rect src = on ? new Rect(199, 206, 237, 226) : new Rect(160, 206, 198, 226);
            image(cv, tex(getContext(), "gui/touchgui.png"), src, new Rect(0, 0, getWidth(), getHeight()),
                    on ? 0xFF8CFFEB : 0xFFB0B8D0, 255);
        }
    }

    /** Square icon drawn with nearest-neighbour scaling inside an inset frame. */
    public static class Icon extends View {
        private final Bitmap bm;
        private final int units, s;
        public Icon(Context c, Bitmap bm, int units) { super(c); this.bm = bm; this.units = units; s = scale(c); }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(units * s, units * s); }
        @Override protected void onDraw(Canvas cv) {
            Rect r = new Rect(0, 0, getWidth(), getHeight());
            nine(cv, tex(getContext(), "gui/newgui/play_screen/Indent2.png"), new Rect(0, 0, 8, 8), 3, r, s, FIELD, 255);
            if (bm != null) {
                int p = 2 * s;
                cv.drawBitmap(bm, null, new Rect(p, p, getWidth() - p, getHeight() - p), pixelPaint(WHITE, 255));
            } else {
                Bitmap i = tex(getContext(), "gui/nova/packs.png");
                if (i != null) cv.drawBitmap(i, null, new Rect(3 * s, 3 * s, getWidth() - 3 * s, getHeight() - 3 * s), pixelPaint(WHITE, 255));
            }
        }
    }

    /** Text input styled as an inset game field. */
    public static EditText field(Context c, String value, String hint, int maxLen, boolean numericFriendly) {
        EditText e = new EditText(c);
        int s = scale(c);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(WHITE);
        e.setHintTextColor(DIM);
        e.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, 8.5f * s);
        e.setBackgroundDrawable(new FieldDrawable(c));
        e.setPadding(6 * s, 4 * s, 6 * s, 4 * s);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI | android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        return e;
    }
}
