package com.geniiy.minebot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity implements BotService.Listener {
    private static final int BG = 0xFF14181D, CARD = 0xFF1E242B, TXT = 0xFFE6EDF3, DIM = 0xFF8B949E, GREEN = 0xFF3DDC84, RED = 0xFFE5534B;

    private EditText name, host, port, owner, sayText;
    private CheckBox answer;
    private TextView statusView, logView;
    private ScrollView logScroll;
    private Button startBtn;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("bot", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        col.setPadding(p, p, p, p);
        root.addView(col);

        TextView title = text("MineBot 0.14", 26, TXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(text("Бот-напарник для Minecraft PE 0.14.0 / 0.14.1 по локальной сети", 14, DIM));

        statusView = text(BotService.status, 15, TXT);
        statusView.setPadding(p, p, p, p);
        statusView.setBackground(round(CARD));
        col.addView(statusView, lp(12));

        LinearLayout card = card(col);
        name = field(card, "Имя бота (3–16 латинских букв/цифр)", prefs.getString("name", "MineBot"), false);
        host = field(card, "Адрес мира (127.0.0.1 — этот телефон)", prefs.getString("host", "127.0.0.1"), false);
        port = field(card, "Порт", prefs.getString("port", "19132"), true);
        owner = field(card, "Слушаться только ника (пусто — всех)", prefs.getString("owner", ""), false);
        answer = new CheckBox(this);
        answer.setText("Отвечать в чат");
        answer.setTextColor(TXT);
        answer.setChecked(prefs.getBoolean("answer", true));
        card.addView(answer);

        startBtn = new Button(this);
        startBtn.setTextSize(17);
        startBtn.setAllCaps(false);
        startBtn.setOnClickListener(v -> toggle());
        col.addView(startBtn, lp(12));

        LinearLayout sayRow = new LinearLayout(this);
        sayRow.setOrientation(LinearLayout.HORIZONTAL);
        sayText = new EditText(this);
        sayText.setHint("Сказать от имени бота…");
        sayText.setHintTextColor(DIM);
        sayText.setTextColor(TXT);
        sayText.setSingleLine(true);
        sayRow.addView(sayText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button sayBtn = new Button(this);
        sayBtn.setText("➤");
        sayBtn.setOnClickListener(v -> {
            String s = sayText.getText().toString().trim();
            if (!s.isEmpty() && BotService.instance != null) { BotService.instance.sayAsBot(s); sayText.setText(""); }
        });
        sayRow.addView(sayBtn);
        col.addView(sayRow, lp(8));

        TextView help = text(
                "Как запустить:\n" +
                "1. Открой Minecraft PE 0.14 и зайди в свой мир.\n" +
                "2. Сверни игру, открой MineBot и нажми «Запустить бота».\n" +
                "3. Вернись в игру — бот появится рядом с точкой спавна.\n\n" +
                "Команды в чате игры:\n" +
                "!ко мне — подойти\n" +
                "!за мной [ник] — следовать\n" +
                "!стоп — остановиться\n" +
                "!иди x y z — идти к координатам\n" +
                "!охрана — следовать и бить мобов рядом с тобой\n" +
                "!атакуй — напасть на ближайшего моба\n" +
                "!копай [x y z] — сломать блок перед собой или по координатам\n" +
                "!прыг, !сядь, !смотри, !где, !скажи текст, !уйди\n" +
                "Можно и без «!», если начать с имени бота: «MineBot за мной».", 14, DIM);
        help.setPadding(p, p, p, p);
        help.setBackground(round(CARD));
        col.addView(help, lp(12));

        col.addView(text("Журнал", 16, TXT), lp(12));
        logScroll = new ScrollView(this);
        logView = text("", 12, 0xFFB0BAC5);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logScroll.addView(logView);
        logScroll.setBackground(round(0xFF0D1014));
        col.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        setContentView(root);
        synchronized (BotService.LOG) { StringBuilder sb = new StringBuilder(); for (String s : BotService.LOG) sb.append(s).append('\n'); logView.setText(sb); }
        refreshButton();
    }

    @Override protected void onResume() { super.onResume(); BotService.listener = this; statusView.setText(BotService.status); refreshButton(); }
    @Override protected void onPause() { BotService.listener = null; super.onPause(); }

    private void toggle() {
        if (BotService.active) {
            startService(new Intent(this, BotService.class).setAction("stop"));
        } else {
            String n = name.getText().toString().trim();
            if (!n.matches("[A-Za-z0-9_]{3,16}")) { statusView.setText("Имя: 3–16 символов, только латиница, цифры и _"); return; }
            prefs.edit().putString("name", n).putString("host", host.getText().toString().trim())
                    .putString("port", port.getText().toString().trim()).putString("owner", owner.getText().toString().trim())
                    .putBoolean("answer", answer.isChecked()).apply();
            synchronized (BotService.LOG) { BotService.LOG.clear(); }
            logView.setText("");
            Intent i = new Intent(this, BotService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            BotService.active = true;
        }
        statusView.postDelayed(this::refreshButton, 300);
        refreshButton();
    }

    private void refreshButton() {
        boolean on = BotService.active;
        startBtn.setText(on ? "Отключить бота" : "Запустить бота");
        startBtn.setTextColor(Color.BLACK);
        startBtn.setBackground(round(on ? RED : GREEN));
    }

    @Override public void onStatus(String s) { statusView.setText(s); refreshButton(); }

    @Override public void onLog(String s) {
        logView.append(s + "\n");
        if (logView.getLineCount() > 400) {
            CharSequence t = logView.getText();
            logView.setText(t.subSequence(t.length() / 2, t.length()));
        }
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------- helpers ----------
    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }
    private EditText field(LinearLayout parent, String label, String value, boolean number) {
        parent.addView(text(label, 13, DIM));
        EditText e = new EditText(this);
        e.setText(value); e.setTextColor(TXT); e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(e);
        return e;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(12), dp(16), dp(12));
        c.setBackground(round(CARD));
        parent.addView(c, lp(12));
        return c;
    }
    private GradientDrawable round(int color) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(12)); return g; }
    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = dp(topDp); return l;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
