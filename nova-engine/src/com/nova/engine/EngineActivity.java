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

    /** AddExternalServerScreen: server name, address, port */
    /** ChatScreen: the system keyboard on a text box, send -> TextPacket (chat), "/..." goes as a command text too */
    private void chatDialog() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        final android.app.AlertDialog d = new android.app.AlertDialog.Builder(this).setView(in)
                .setPositiveButton("Отправить", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) { send(in.getText().toString()); }
                })
                .setNegativeButton("Назад", null).create();
        in.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            public boolean onEditorAction(android.widget.TextView v, int id, android.view.KeyEvent ev) { send(in.getText().toString()); d.dismiss(); return true; }
        });
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
        in.requestFocus();
    }

    private void send(final String t) {
        final String m = t.trim();
        if (m.isEmpty()) return;
        view.queueEvent(new Runnable() { public void run() { game.say(m); } });
    }

    private void serverDialog(final com.nova.engine.ui.PlayScreen s) {
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        final android.widget.EditText name = new android.widget.EditText(this), addr = new android.widget.EditText(this), port = new android.widget.EditText(this);
        final android.widget.EditText nick = new android.widget.EditText(this);
        nick.setHint("Твой ник"); nick.setText(game.playerName);
        l.addView(nick);
        name.setHint("Имя сервера"); addr.setHint("Адрес (IP)"); port.setHint("Порт"); port.setText("19132");
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        l.addView(name); l.addView(addr); l.addView(port);
        new android.app.AlertDialog.Builder(this).setTitle("Добавить сервер").setView(l)
                .setPositiveButton("Добавить", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        int p = 19132;
                        try { p = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) { }
                        String nk = nick.getText().toString().trim();
                        if (!nk.isEmpty()) { game.playerName = nk; prefs.edit().putString("name", nk).apply(); }
                        String a = addr.getText().toString().trim();
                        if (!a.isEmpty()) s.addServer(name.getText().toString().trim(), a, p);
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    /** world menu: current seed, type a seed (number or text, empty = random), recent seeds, copy */
    private void worldDialog() {
        if (dialogOpen) return;
        if (game.onServer()) {                                   // on a server: only leaving makes sense here
            dialogOpen = true;
            new android.app.AlertDialog.Builder(this).setTitle("Сервер")
                    .setPositiveButton("Выйти в меню", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { game.leaveServer(); }
                    }).setNegativeButton("Назад", null)
                    .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                        public void onDismiss(android.content.DialogInterface d) { dialogOpen = false; }
                    }).show();
            return;
        }
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
        final java.io.File crash = new java.io.File(getFilesDir(), "crash.txt");
        if (crash.exists()) {                                    // the last crash, so it can be sent
            try {
                java.io.FileInputStream fi = new java.io.FileInputStream(crash);
                byte[] bb = new byte[(int) crash.length()];
                int rd = 0; while (rd < bb.length) { int k = fi.read(bb, rd, bb.length - rd); if (k < 0) break; rd += k; }
                fi.close();
                String txt = new String(bb, 0, rd, "UTF-8");
                crash.delete();
                final android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText(txt); tv.setTextIsSelectable(true); tv.setPadding(24, 24, 24, 24);
                new android.app.AlertDialog.Builder(this).setTitle("Прошлый вылет").setView(tv).setPositiveButton("OK", null).show();
            } catch (Exception ignored) { }
        }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    java.io.FileWriter fw = new java.io.FileWriter(crash);
                    fw.write(Game.VERSION + " thread " + th.getName() + "\n" + sw);
                    fw.close();
                } catch (Exception ignored) { }
                if (prev != null) prev.uncaughtException(th, e);
            }
        });
        game = new Game(this);
        game.filesDir = getFilesDir();
        game.ui = new Game.Ui() {
            public void askServer(final com.nova.engine.ui.PlayScreen s) {
                runOnUiThread(new Runnable() { public void run() { serverDialog(s); } });
            }
        };
        prefs = getSharedPreferences("nova", 0);
        game.playerName = prefs.getString("name", "Steve");
        if (prefs.contains("seed")) game.startSeed = prefs.getInt("seed", 0);
        else prefs.edit().putInt("seed", game.startSeed).apply();
        view = new GLSurfaceView(this) {
            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (game.inMenu) { game.menuTouch(e); return true; }
                if (game.controls.menuHit(e)) { worldDialog(); return true; }
                if (game.onServer() && game.controls.chatHit(e)) { chatDialog(); return true; }
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
