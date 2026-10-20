package com.geniiy.swarm;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.geniiy.swarm.core.SwarmManager;

import java.util.List;

public class MainActivity extends Activity implements SwarmService.Listener {
    private static final int BG = 0xFF14181D, CARD = 0xFF1E242B, BTN = 0xFF2A323B, TXT = 0xFFE6EDF3, DIM = 0xFF8B949E,
            GREEN = 0xFF3DDC84, RED = 0xFFE5534B, YELLOW = 0xFFE3B341;

    private EditText host, port, count, reg, delay;
    private TextView stat, logView;
    private ScrollView logScroll;
    private LinearLayout list;
    private SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("swarm", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        col.setPadding(p, p, p, p);
        root.addView(col);

        TextView title = text("Нагрузочный тест сервера", 22, TXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(text("Рой ботов для проверки своего сервера MCPE 0.14. Каждый заходит, пишет /register, бегает и прыгает.", 13, DIM));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(round(CARD));
        col.addView(card, lp(10));
        host = field(card, "IP сервера", prefs.getString("host", "127.0.0.1"), false);
        port = field(card, "Порт", prefs.getString("port", "19132"), true);
        reg = field(card, "Команда регистрации ({pass} — пароль)", prefs.getString("reg", "/register {pass} {pass}"), false);
        delay = field(card, "Пауза между входами, мс", prefs.getString("delay", "400"), true);
        count = field(card, "Сколько ботов добавить", prefs.getString("count", "10"), true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button add = bigButton("➕ Подключить", GREEN);
        add.setOnClickListener(v -> addBots());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(52), 2); l1.rightMargin = dp(6);
        row.addView(add, l1);
        Button stopAll = bigButton("⏹ Отключить всех", RED);
        stopAll.setOnClickListener(v -> { startService(new Intent(this, SwarmService.class).setAction("stop")); SwarmService.active = false; });
        row.addView(stopAll, new LinearLayout.LayoutParams(0, dp(52), 3));
        col.addView(row, lp(10));

        stat = text("Ботов нет", 15, YELLOW);
        stat.setPadding(p, dp(10), p, dp(10));
        stat.setBackground(round(CARD));
        col.addView(stat, lp(10));

        col.addView(text("Боты", 15, TXT), lp(12));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, lp(4));

        col.addView(text("Журнал", 15, TXT), lp(12));
        logScroll = new ScrollView(this);
        logView = text("", 12, 0xFFB0BAC5);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logScroll.addView(logView);
        logScroll.setBackground(round(0xFF0D1014));
        logScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        col.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        setContentView(root);
        synchronized (SwarmService.LOG) { StringBuilder sb = new StringBuilder(); for (String s : SwarmService.LOG) sb.append(s).append('\n'); logView.setText(sb); }
    }

    @Override protected void onResume() { super.onResume(); SwarmService.listener = this; h.post(poll); }
    @Override protected void onPause() { SwarmService.listener = null; h.removeCallbacks(poll); save(); super.onPause(); }

    private void save() {
        prefs.edit().putString("host", host.getText().toString().trim()).putString("port", port.getText().toString().trim())
                .putString("reg", reg.getText().toString().trim()).putString("delay", delay.getText().toString().trim())
                .putString("count", count.getText().toString().trim()).apply();
    }

    private void addBots() {
        save();
        String n = host.getText().toString().trim();
        if (n.isEmpty()) { stat.setText("Введи IP сервера"); return; }
        int c = parse(count.getText().toString(), 10);
        Intent i = new Intent(this, SwarmService.class);
        i.putExtra("host", n);
        i.putExtra("port", parse(port.getText().toString(), 19132));
        i.putExtra("reg", reg.getText().toString().trim());
        i.putExtra("add", c);
        SwarmService s = SwarmService.instance;
        if (s != null && s.swarm != null) s.swarm.connectDelayMs = parse(delay.getText().toString(), 400);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        SwarmService.active = true;
        if (SwarmService.instance != null && SwarmService.instance.swarm != null)
            SwarmService.instance.swarm.connectDelayMs = parse(delay.getText().toString(), 400);
    }

    private void refresh() {
        SwarmService s = SwarmService.instance;
        SwarmManager sw = s != null ? s.swarm : null;
        if (sw == null) { stat.setText("Ботов нет"); list.removeAllViews(); return; }
        stat.setText("Онлайн: " + sw.onlineCount() + " / всего " + sw.total() + (sw.queued() > 0 ? "  (заходят: " + sw.queued() + ")" : ""));
        List<SwarmManager.Row> rows = sw.snapshot();
        // перестраиваем список, только если изменилось число строк
        if (list.getChildCount() != rows.size()) {
            list.removeAllViews();
            for (SwarmManager.Row r : rows) list.addView(botRow(r));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                SwarmManager.Row r = rows.get(i);
                LinearLayout rl = (LinearLayout) list.getChildAt(i);
                ((TextView) rl.getChildAt(0)).setText((r.online ? "🟢 " : "⚪ ") + r.name + "  —  " + r.status);
                rl.setTag(r.id);
            }
        }
        s.updateNote();
    }

    private View botRow(SwarmManager.Row r) {
        LinearLayout rl = new LinearLayout(this);
        rl.setOrientation(LinearLayout.HORIZONTAL);
        rl.setGravity(Gravity.CENTER_VERTICAL);
        rl.setPadding(dp(10), dp(6), dp(6), dp(6));
        rl.setBackground(round(CARD));
        rl.setTag(r.id);
        TextView t = text((r.online ? "🟢 " : "⚪ ") + r.name + "  —  " + r.status, 14, TXT);
        rl.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button x = new Button(this);
        x.setText("✕"); x.setTextColor(TXT); x.setBackground(round(BTN));
        x.setOnClickListener(v -> { SwarmService s = SwarmService.instance; if (s != null && s.swarm != null) { s.swarm.remove((Integer) rl.getTag()); refresh(); } });
        rl.addView(x, new LinearLayout.LayoutParams(dp(48), dp(42)));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(rl, lp(4));
        return wrap;
    }

    @Override public void onLog(String s) {
        logView.append(s + "\n");
        if (logView.getLineCount() > 400) { CharSequence t = logView.getText(); logView.setText(t.subSequence(t.length() / 2, t.length())); }
        boolean atBottom = logScroll.getScrollY() + logScroll.getHeight() >= logView.getHeight() - dp(40);
        if (atBottom) logScroll.post(() -> logScroll.scrollTo(0, Math.max(0, logView.getHeight() - logScroll.getHeight())));
    }

    // helpers
    private static int parse(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private EditText field(LinearLayout parent, String label, String value, boolean number) {
        parent.addView(text(label, 13, DIM));
        EditText e = new EditText(this); e.setText(value); e.setTextColor(TXT); e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(e); return e;
    }
    private Button bigButton(String s, int color) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(Color.BLACK); b.setBackground(round(color)); return b; }
    private GradientDrawable round(int color) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(12)); return g; }
    private LinearLayout.LayoutParams lp(int topDp) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); l.topMargin = dp(topDp); return l; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
