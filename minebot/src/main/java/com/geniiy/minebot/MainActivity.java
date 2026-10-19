package com.geniiy.minebot;

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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.geniiy.minebot.core.LanScanner;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements BotService.Listener {
    private static final int BG = 0xFF14181D, CARD = 0xFF1E242B, BTN = 0xFF2A323B, TXT = 0xFFE6EDF3, DIM = 0xFF8B949E,
            GREEN = 0xFF3DDC84, RED = 0xFFE5534B, BLUE = 0xFF4FA3FF;

    private EditText name, host, port, owner, sayText;
    private CheckBox answer, debug;
    private TextView statusView, infoView, logView;
    private ScrollView logScroll;
    private Button startBtn;
    private LinearLayout found, settings, controls;
    private SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            BotService s = BotService.instance;
            String inf = s != null ? s.info() : "";
            infoView.setText(inf);
            infoView.setVisibility(inf.isEmpty() ? View.GONE : View.VISIBLE);
            setControlsEnabled(s != null && s.online());
            refreshButton();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("bot", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        col.setPadding(p, p, p, p);
        root.addView(col);

        TextView title = text("MineBot 0.14", 24, TXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(p, dp(10), p, dp(10));
        statusCard.setBackground(round(CARD));
        statusView = text(BotService.status, 15, TXT);
        infoView = text("", 13, DIM);
        infoView.setPadding(0, dp(6), 0, 0);
        statusCard.addView(statusView);
        statusCard.addView(infoView);
        col.addView(statusCard, lp(10));

        startBtn = new Button(this);
        startBtn.setTextSize(17);
        startBtn.setAllCaps(false);
        startBtn.setOnClickListener(v -> toggle());
        col.addView(startBtn, lp(10));

        // ---- пульт управления ----
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        col.addView(controls, lp(6));
        String[][] rows = {
                {"🧍 Ко мне", "ко мне"}, {"👣 За мной", "за мной"}, {"✋ Стоп", "стоп"},
                {"🛡 Охрана", "охрана"}, {"⚔ Атака", "атакуй"}, {"✨ ТП ко мне", "тп"},
                {"⛏ Копать", "копай"}, {"🕳 Туннель", "туннель 10"}, {"⬇ Вниз 5", "вниз 5"},
                {"🎒 Собирать", "собирай"}, {"🚶 Гулять", "гуляй"}, {"💃 Танец", "танцуй"},
                {"⤴ Прыжок", "прыг"}, {"🪑 Сесть", "сядь"}, {"👀 Смотри", "смотри"},
                {"➖ Ближе", "ближе"}, {"➕ Дальше", "дальше"}, {"ℹ Инфо", "инфо"}};
        LinearLayout row = null;
        for (int i = 0; i < rows.length; i++) {
            if (i % 3 == 0) { row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); controls.addView(row, lp(4)); }
            final String cmd = rows[i][1];
            Button c = new Button(this);
            c.setText(rows[i][0]); c.setAllCaps(false); c.setTextSize(13); c.setTextColor(TXT);
            c.setBackground(round(BTN));
            c.setPadding(dp(4), 0, dp(4), 0);
            c.setOnClickListener(v -> { BotService s = BotService.instance; if (s != null) s.command(cmd); });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, dp(44), 1);
            l.leftMargin = l.rightMargin = dp(2);
            row.addView(c, l);
        }

        LinearLayout sayRow = new LinearLayout(this);
        sayRow.setOrientation(LinearLayout.HORIZONTAL);
        sayText = new EditText(this);
        sayText.setHint("Сказать от имени бота или команда (/…)");
        sayText.setHintTextColor(DIM);
        sayText.setTextColor(TXT);
        sayText.setSingleLine(true);
        sayRow.addView(sayText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button sayBtn = new Button(this);
        sayBtn.setText("➤");
        sayBtn.setOnClickListener(v -> {
            String s = sayText.getText().toString().trim();
            BotService svc = BotService.instance;
            if (s.isEmpty() || svc == null) return;
            if (s.startsWith("!")) svc.command(s.substring(1).trim()); else svc.sayAsBot(s);
            sayText.setText("");
        });
        sayRow.addView(sayBtn);
        col.addView(sayRow, lp(6));
        col.addView(text("Начни с «!», чтобы отдать боту команду прямо отсюда, например «!иди 10 64 5».", 12, DIM));

        // ---- настройки ----
        Button setBtn = new Button(this);
        setBtn.setText("⚙ Настройки и поиск миров");
        setBtn.setAllCaps(false);
        setBtn.setTextColor(TXT);
        setBtn.setBackground(round(BTN));
        col.addView(setBtn, lp(12));
        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(14), dp(10), dp(14), dp(10));
        settings.setBackground(round(CARD));
        col.addView(settings, lp(4));
        boolean firstRun = !prefs.contains("name");
        settings.setVisibility(firstRun ? View.VISIBLE : View.GONE);
        setBtn.setOnClickListener(v -> settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        name = field(settings, "Имя бота (3–16 латинских букв/цифр)", prefs.getString("name", "MineBot"), false);
        host = field(settings, "Адрес мира (127.0.0.1 — этот телефон) или сервера", prefs.getString("host", "127.0.0.1"), false);
        port = field(settings, "Порт", prefs.getString("port", "19132"), true);
        owner = field(settings, "Слушаться только ника (пусто — всех)", prefs.getString("owner", ""), false);
        answer = check(settings, "Отвечать в чат", prefs.getBoolean("answer", true));
        debug = check(settings, "Подробный журнал (для поиска ошибок)", prefs.getBoolean("debug", false));
        Button scanBtn = new Button(this);
        scanBtn.setText("🔍 Найти миры рядом");
        scanBtn.setAllCaps(false);
        scanBtn.setOnClickListener(v -> scan());
        settings.addView(scanBtn, lp(6));
        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        settings.addView(found);

        TextView help = text(
                "Как запустить: зайди в мир в Minecraft → открой MineBot → «Запустить бота» → вернись в игру.\n\n" +
                "Команды в чате игры: «!команда» или «Имя_бота команда». Работает и на серверах с префиксами в чате.\n" +
                "ко мне · за мной [ник] · стоп · иди x y z · тп · охрана · атакуй · копай [x y z] · туннель N · вниз N · " +
                "собирай · гуляй · танцуй · прыг · сядь · смотри · ближе · дальше · где · инфо · скажи текст · уйди · помощь", 13, DIM);
        help.setPadding(p, p, p, p);
        help.setBackground(round(CARD));
        col.addView(help, lp(12));

        col.addView(text("Журнал", 15, TXT), lp(12));
        logScroll = new ScrollView(this);
        logView = text("", 12, 0xFFB0BAC5);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        logScroll.setBackground(round(0xFF0D1014));
        col.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));

        setContentView(root);
        synchronized (BotService.LOG) { StringBuilder sb = new StringBuilder(); for (String s : BotService.LOG) sb.append(s).append('\n'); logView.setText(sb); }
        refreshButton();
        setControlsEnabled(false);
    }

    @Override protected void onResume() { super.onResume(); BotService.listener = this; statusView.setText(BotService.status); h.post(poll); }
    @Override protected void onPause() { BotService.listener = null; h.removeCallbacks(poll); super.onPause(); }

    private void toggle() {
        if (BotService.active) {
            startService(new Intent(this, BotService.class).setAction("stop"));
            BotService.active = false;
        } else {
            String n = name.getText().toString().trim();
            if (!n.matches("[A-Za-z0-9_]{3,16}")) { statusView.setText("Имя: 3–16 символов, только латиница, цифры и _"); settings.setVisibility(View.VISIBLE); return; }
            prefs.edit().putString("name", n).putString("host", host.getText().toString().trim())
                    .putString("port", port.getText().toString().trim()).putString("owner", owner.getText().toString().trim())
                    .putBoolean("answer", answer.isChecked()).putBoolean("debug", debug.isChecked()).apply();
            synchronized (BotService.LOG) { BotService.LOG.clear(); }
            logView.setText("");
            settings.setVisibility(View.GONE);
            Intent i = new Intent(this, BotService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            BotService.active = true;
        }
        refreshButton();
    }

    private void scan() {
        found.removeAllViews();
        found.addView(text("Ищу миры… (2–3 секунды)", 14, DIM));
        final String hh = host.getText().toString().trim();
        new Thread(() -> {
            List<String> extra = new ArrayList<>();
            if (!hh.isEmpty()) extra.add(hh);
            List<LanScanner.Found> list = LanScanner.scan(extra, 2500);
            runOnUiThread(() -> {
                found.removeAllViews();
                if (list.isEmpty()) {
                    found.addView(text("Ничего не найдено. Открой мир в Minecraft и проверь, что включена игра по локальной сети.", 14, RED));
                    return;
                }
                for (LanScanner.Found f : list) {
                    TextView t = text("▶ " + LanScanner.pretty(f.info) + "\n   " + f.host + ":" + f.port, 14, TXT);
                    t.setPadding(dp(12), dp(10), dp(12), dp(10));
                    t.setBackground(round(BTN));
                    t.setOnClickListener(v -> { host.setText(f.host); port.setText(String.valueOf(f.port)); statusView.setText("Выбран: " + f.host + ":" + f.port); });
                    found.addView(t, lp(6));
                }
            });
        }).start();
    }

    private void refreshButton() {
        boolean on = BotService.active;
        startBtn.setText(on ? "⏹ Отключить бота" : "▶ Запустить бота");
        startBtn.setTextColor(Color.BLACK);
        startBtn.setBackground(round(on ? RED : GREEN));
    }

    private void setControlsEnabled(boolean en) {
        controls.setAlpha(en ? 1f : 0.4f);
        for (int i = 0; i < controls.getChildCount(); i++) {
            ViewGroup r = (ViewGroup) controls.getChildAt(i);
            for (int j = 0; j < r.getChildCount(); j++) r.getChildAt(j).setEnabled(en);
        }
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
    private CheckBox check(LinearLayout parent, String label, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(label); c.setTextColor(TXT); c.setChecked(v);
        parent.addView(c);
        return c;
    }
    private GradientDrawable round(int color) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(12)); return g; }
    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = dp(topDp); return l;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
