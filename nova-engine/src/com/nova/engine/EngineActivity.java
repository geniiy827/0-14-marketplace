package com.nova.engine;

import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.nova.engine.game.Game;

public final class EngineActivity extends Activity {
    private GLSurfaceView view;
    private android.content.SharedPreferences prefs;
    private boolean dialogOpen;

    /** world menu: current seed, type a seed (number or text, empty = random), recent seeds, copy */
    private void worldDialog() {
        if (dialogOpen) return;
        dialogOpen = true;
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        l.setPadding(pad, pad / 2, pad, 0);
        android.widget.TextView cur = new android.widget.TextView(this);
        cur.setText("Текущий сид: " + game.seed());
        cur.setTextSize(16);
        l.addView(cur);
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("сид: число или текст (пусто = случайный)");
        in.setSingleLine(true);
        l.addView(in);
        String[] recent = prefs.getString("recent", "").split("\n");
        boolean any = false;
        for (final String t : recent) {
            if (t.isEmpty()) continue;
            if (!any) {
                android.widget.TextView h = new android.widget.TextView(this);
                h.setText("Недавние:");
                h.setPadding(0, pad / 2, 0, 0);
                l.addView(h);
                any = true;
            }
            android.widget.Button b = new android.widget.Button(this);
            b.setAllCaps(false);
            b.setText(t + "   \u2192   " + com.nova.engine.world.Seeds.parse(t, 0));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { in.setText(t); in.setSelection(t.length()); }
            });
            l.addView(b);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(l);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("Мир")
                .setView(sv)
                .setPositiveButton("Создать мир", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        String t = in.getText().toString();
                        int seed = com.nova.engine.world.Seeds.parse(t, new java.util.Random().nextInt());
                        remember(t.trim().isEmpty() ? String.valueOf(seed) : t);
                        prefs.edit().putInt("seed", seed).apply();
                        game.newWorld(seed);
                    }
                })
                .setNeutralButton("Копировать сид", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("seed", String.valueOf(game.seed())));
                        android.widget.Toast.makeText(EngineActivity.this, "Сид скопирован", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Постройки рядом", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) { structuresDialog(); }
                })
                .create();
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface di) { dialogOpen = false; immersive(); }
        });
        d.show();
    }

    /** villages / pyramids / witch huts around the player, tap = teleport there */
    private void structuresDialog() {
        final android.widget.Toast wait = android.widget.Toast.makeText(this, "Ищу постройки...", android.widget.Toast.LENGTH_SHORT);
        wait.show();
        new Thread(new Runnable() {
            public void run() {
                final java.util.List<int[]> found = game.structures(100);
                runOnUiThread(new Runnable() {
                    public void run() {
                        int n = Math.min(found.size(), 40);
                        final String[] items = new String[n];
                        for (int i = 0; i < n; i++) {
                            int[] f = found.get(i);
                            items[i] = com.nova.engine.world.Finder.TYPE[f[0]] + "   X " + f[1] + "  Z " + f[2] + "   (" + f[3] + " м)";
                        }
                        android.app.AlertDialog d = new android.app.AlertDialog.Builder(EngineActivity.this)
                                .setTitle(n == 0 ? "Рядом ничего нет (1600 блоков)" : "Постройки рядом — тап: телепорт")
                                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface di, int which) {
                                        int[] f = found.get(which);
                                        game.teleport(f[1], f[2]);
                                    }
                                })
                                .setNegativeButton("Закрыть", null)
                                .create();
                        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                            public void onDismiss(android.content.DialogInterface di) { immersive(); }
                        });
                        d.show();
                    }
                });
            }
        }).start();
    }

    private void remember(String t) {
        StringBuilder sb = new StringBuilder(t);
        int n = 1;
        for (String o : prefs.getString("recent", "").split("\n")) {
            if (o.isEmpty() || o.equals(t) || n >= 10) continue;
            sb.append('\n').append(o); n++;
        }
        prefs.edit().putString("recent", sb.toString()).apply();
    }
    private Game game;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {                     // draw under the camera cutout (API 28 field, SDK here is 23)
            try {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode").setInt(lp, 1);
                getWindow().setAttributes(lp);
            } catch (Throwable ignored) { }
        }
        game = new Game(this);
        prefs = getSharedPreferences("nova", 0);
        if (prefs.contains("seed")) game.startSeed = prefs.getInt("seed", 0);
        else prefs.edit().putInt("seed", game.startSeed).apply();
        view = new GLSurfaceView(this) {
            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (game.inMenu) { game.menuTouch(e); return true; }
                if (game.controls.menuHit(e)) { worldDialog(); return true; }
                return game.controls.onTouch(e);
            }
        };
        view.setEGLContextClientVersion(3);
        view.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        view.setPreserveEGLContextOnPause(true);
        view.setRenderer(game);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(view);
        immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @Override
    protected void onPause() { super.onPause(); view.onPause(); }

    @Override
    protected void onResume() { super.onResume(); view.onResume(); }
}
