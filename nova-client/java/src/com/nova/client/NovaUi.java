package com.nova.client;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

/** Shared helpers + the NOVA dialog card, drawn with the game's own textures (see McKit). */
public final class NovaUi {
    private NovaUi() { }

    public static void fullscreen(Activity a) {
        a.requestWindowFeature(Window.FEATURE_NO_TITLE);
        a.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        a.getWindow().setBackgroundDrawable(new McKit.BackgroundDrawable(a));
        View decor = a.getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f КБ", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0));
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    /** Thin breathing-free accent line (mint -> violet), like the JSON accent_line. */
    public static View accentLine(Context c) {
        View v = new View(c) {
            final Paint p = new Paint();
            @Override protected void onDraw(Canvas cv) {
                int w = getWidth();
                for (int x = 0; x < w; x += 2) {
                    float t = x / (float) Math.max(1, w);
                    p.setColor(blend(McKit.ACCENT, McKit.VIOLET, t));
                    cv.drawRect(x, 0, x + 2, getHeight(), p);
                }
            }
        };
        return v;
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /** Progress bar: inset field with a mint fill. 0..1000 */
    public static final class Progress extends View {
        private int value;
        private boolean indeterminate;
        private long t0 = System.currentTimeMillis();
        private final int s;
        public Progress(Context c) { super(c); s = McKit.scale(c); }
        public void setProgress(int v) { value = v; indeterminate = false; invalidate(); }
        public void setIndeterminate(boolean b) { indeterminate = b; invalidate(); }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), 8 * s); }
        @Override protected void onDraw(Canvas cv) {
            Rect r = new Rect(0, 0, getWidth(), getHeight());
            McKit.nine(cv, McKit.tex(getContext(), "gui/newgui/play_screen/Indent2.png"), new Rect(0, 0, 8, 8), 3, r, s, McKit.FIELD, 255);
            Paint p = new Paint();
            p.setColor(McKit.ACCENT);
            int pad = 2 * s, w = getWidth() - 2 * pad;
            if (indeterminate) {
                long t = (System.currentTimeMillis() - t0) % 1200;
                int seg = w / 4, x = (int) ((w + seg) * t / 1200f) - seg;
                cv.drawRect(pad + Math.max(0, x), pad, pad + Math.min(w, x + seg), getHeight() - pad, p);
                postInvalidateDelayed(16);
            } else {
                cv.drawRect(pad, pad, pad + w * value / 1000, getHeight() - pad, p);
            }
        }
    }

    /** NOVA dialog in Minecraft style: window panel, pixel title, accent line, game buttons. */
    public static final class Card {
        public final Dialog dialog;
        public final LinearLayout body;
        public final LinearLayout buttons;
        public final McKit.Text title;
        private final Context c;
        private final int s;

        public Card(Context c, String titleText) {
            this.c = c;
            s = McKit.scale(c);
            dialog = new Dialog(c);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundDrawable(new McKit.PanelDrawable(c, McKit.PANEL, 250));
            root.setPadding(10 * s, 9 * s, 10 * s, 9 * s);

            title = new McKit.Text(c, titleText, McKit.ACCENT);
            root.addView(title);
            View line = accentLine(c);
            LinearLayout.LayoutParams ll = lp(ViewGroup.LayoutParams.MATCH_PARENT, s);
            ll.topMargin = 2 * s; ll.bottomMargin = 6 * s;
            root.addView(line, ll);

            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            root.addView(body);

            buttons = new LinearLayout(c);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END);
            LinearLayout.LayoutParams bl = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = 8 * s;
            root.addView(buttons, bl);

            dialog.setContentView(root);
            Window w = dialog.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.55f);
            w.setLayout(Math.min(240 * s, (int) (c.getResources().getDisplayMetrics().widthPixels * 0.9f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        public McKit.Text addText(String text, int color) {
            McKit.Text t = new McKit.Text(c, text, color).wrap();
            body.addView(t, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return t;
        }

        public Progress addProgress() {
            Progress p = new Progress(c);
            LinearLayout.LayoutParams l = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l.topMargin = 6 * s;
            body.addView(p, l);
            return p;
        }

        public McKit.Button addButton(String label, int kind, View.OnClickListener l) {
            McKit.Button b = new McKit.Button(c, label, kind, l).minWidth(70);
            LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.leftMargin = 4 * s;
            buttons.addView(b, p);
            return b;
        }
    }
}
