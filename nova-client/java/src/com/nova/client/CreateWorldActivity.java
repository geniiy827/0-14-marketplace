package com.nova.client;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.File;
import java.util.Random;

/**
 * NOVA "Создание мира": world creation with fine settings that the stock 0.14 screen
 * does not expose (world type, start time, frozen day cycle, mobs, weather, spawn point).
 * The world is written to disk (level.dat), the game generates terrain on first load.
 */
public class CreateWorldActivity extends Activity {
    private static final int REQ_STORAGE = 7301;

    private static final String[] MODES = {"Выживание", "Творческий"};
    private static final String[] TYPES = {"Бесконечный", "Плоский", "Старый (256x256)"};
    private static final int[] TYPE_GEN = {WorldWriter.GEN_INFINITE, WorldWriter.GEN_FLAT, WorldWriter.GEN_OLD};
    private static final String[] TIMES = {"Рассвет", "День", "Закат", "Ночь"};
    private static final int[] TIME_TICKS = {0, 6000, 12000, 14000};
    private static final String[] WEATHER = {"Ясно", "Дождь", "Гроза"};

    private int s, mode, type, time = 1, weather;
    private EditText name, seed, spawnX, spawnZ;
    private McKit.Toggle cycle, mobs;
    private LinearLayout spawnRow;
    private final Random rnd = new Random();

    @Override
    protected void onCreate(Bundle b) {
        NovaUi.fullscreen(this);
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        s = McKit.scale(this);
        build();
    }

    private McKit.Text label(String t) {
        McKit.Text l = new McKit.Text(this, t, McKit.GRAY);
        l.setPadding(0, 3 * s, 0, 0);
        return l;
    }

    private LinearLayout.LayoutParams full() { return NovaUi.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams l = full();
        l.topMargin = top * s;
        return l;
    }

    /** Minecraft-style cycle button: "Label: value", tap to go to the next value. */
    private McKit.Button cycleButton(final String label, final String[] values, final int[] idx, final Runnable onChange) {
        final McKit.Button[] holder = new McKit.Button[1];
        holder[0] = new McKit.Button(this, label + ": " + values[idx[0]], McKit.Button.NORMAL, new View.OnClickListener() {
            public void onClick(View v) {
                idx[0] = (idx[0] + 1) % values.length;
                holder[0].setLabel(label + ": " + values[idx[0]]);
                if (onChange != null) onChange.run();
            }
        });
        return holder[0];
    }

    private LinearLayout toggleRow(String text, McKit.Toggle t) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundDrawable(new McKit.FieldDrawable(this));
        r.setPadding(6 * s, 2 * s, 3 * s, 2 * s);
        r.addView(new McKit.Text(this, text, McKit.WHITE), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(t);
        return r;
    }

    private final int[] modeIdx = {0}, typeIdx = {0}, timeIdx = {1}, weatherIdx = {0};

    private void build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(14 * s, 8 * s, 14 * s, 6 * s);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(new McKit.Text(this, "Создание мира", McKit.WHITE));
        header.addView(new McKit.Text(this, "   расширенные настройки NOVA", McKit.ACCENT), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(header);
        LinearLayout.LayoutParams ll = NovaUi.lp(-1, s);
        ll.topMargin = 4 * s; ll.bottomMargin = 5 * s;
        col.addView(NovaUi.accentLine(this), ll);

        // two columns inside a scroll view
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = panel();
        left.addView(new McKit.Text(this, "Основное", McKit.ACCENT));
        left.addView(label("Название мира"));
        name = McKit.field(this, "Новый мир", "Название", 32, false);
        left.addView(name, full());
        left.addView(label("Сид (пусто = случайный)"));
        LinearLayout seedRow = new LinearLayout(this);
        seedRow.setGravity(Gravity.CENTER_VERTICAL);
        seed = McKit.field(this, "", "например 12345 или слово", 32, false);
        seedRow.addView(seed, new LinearLayout.LayoutParams(0, -2, 1));
        McKit.Button dice = new McKit.Button(this, "Случайный", McKit.Button.NORMAL, new View.OnClickListener() {
            public void onClick(View v) { seed.setText(String.valueOf(rnd.nextInt() & 0x7fffffff)); }
        }).minWidth(56);
        LinearLayout.LayoutParams dl = NovaUi.lp(-2, -2);
        dl.leftMargin = 4 * s;
        seedRow.addView(dice, dl);
        left.addView(seedRow, full());
        left.addView(cycleButton("Режим игры", MODES, modeIdx, null), gap(6));
        left.addView(cycleButton("Тип мира", TYPES, typeIdx, new Runnable() {
            public void run() { spawnRow.setVisibility(TYPE_GEN[typeIdx[0]] == WorldWriter.GEN_OLD ? View.GONE : View.VISIBLE); }
        }), gap(4));

        LinearLayout right = panel();
        right.addView(new McKit.Text(this, "Тонкая настройка", McKit.ACCENT));
        right.addView(cycleButton("Время на старте", TIMES, timeIdx, null), gap(3));
        cycle = new McKit.Toggle(this, true);
        right.addView(toggleRow("Смена дня и ночи", cycle), gap(4));
        mobs = new McKit.Toggle(this, true);
        right.addView(toggleRow("Появление мобов", mobs), gap(3));
        right.addView(cycleButton("Погода", WEATHER, weatherIdx, null), gap(4));
        spawnRow = new LinearLayout(this);
        spawnRow.setOrientation(LinearLayout.VERTICAL);
        spawnRow.addView(label("Точка появления (X, Z)"));
        LinearLayout xz = new LinearLayout(this);
        spawnX = McKit.field(this, "0", "X", 7, true);
        spawnZ = McKit.field(this, "0", "Z", 7, true);
        int num = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
        spawnX.setInputType(num);
        spawnZ.setInputType(num);
        xz.addView(spawnX, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams zl = new LinearLayout.LayoutParams(0, -2, 1);
        zl.leftMargin = 4 * s;
        xz.addView(spawnZ, zl);
        spawnRow.addView(xz, full());
        right.addView(spawnRow, full());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        cols.addView(left, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
        rp.leftMargin = 6 * s;
        cols.addView(right, rp);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(cols);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // footer
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.addView(new McKit.Text(this, "Мир появится в списке миров", McKit.DIM), new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(new McKit.Button(this, "Отмена", McKit.Button.NORMAL, new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        }).minWidth(70));
        McKit.Button create = new McKit.Button(this, "Создать мир", McKit.Button.PRIMARY, new View.OnClickListener() {
            public void onClick(View v) { tryCreate(); }
        }).minWidth(100);
        LinearLayout.LayoutParams cl = NovaUi.lp(-2, -2);
        cl.leftMargin = 5 * s;
        footer.addView(create, cl);
        col.addView(footer, gap(5));

        FrameLayout root = new FrameLayout(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundDrawable(new McKit.PanelDrawable(this, McKit.PANEL, 235));
        p.setPadding(8 * s, 6 * s, 8 * s, 8 * s);
        return p;
    }

    private boolean hasStorage() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void tryCreate() {
        if (!hasStorage()) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        create();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != REQ_STORAGE) return;
        if (hasStorage()) create();
        else message("Нет доступа к файлам", "Разрешите игре доступ к файлам (Настройки -> Приложения -> "
                + "Minecraft PE -> Разрешения), иначе мир не сохранится.", true);
    }

    private static int parseInt(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return 0; }
    }

    private void create() {
        String n = name.getText().toString().trim();
        if (n.isEmpty()) n = "Новый мир";
        WorldWriter.Settings st = new WorldWriter.Settings();
        st.name = n;
        st.seed = WorldWriter.seedFrom(seed.getText().toString(), rnd);
        st.gameType = modeIdx[0];
        st.generator = TYPE_GEN[typeIdx[0]];
        st.startTime = TIME_TICKS[timeIdx[0]];
        st.dayCycle = cycle.isOn();
        st.spawnMobs = mobs.isOn();
        st.weather = weatherIdx[0];
        st.spawnX = Math.max(-1000000, Math.min(1000000, parseInt(spawnX)));
        st.spawnZ = Math.max(-1000000, Math.min(1000000, parseInt(spawnZ)));
        try {
            File worlds = new File(Environment.getExternalStorageDirectory(), "games/com.mojang/minecraftWorlds");
            WorldWriter.create(worlds, st);
            message("Мир создан!", "«" + n + "» добавлен в список миров. Сид: " + st.seed
                    + ". Если мира нет в списке, выйдите из «Играть» и зайдите снова.", false);
        } catch (Exception e) {
            message("Ошибка", "Не удалось создать мир: " + e.getMessage(), true);
        }
    }

    private void message(String title, String text, final boolean error) {
        final NovaUi.Card card = new NovaUi.Card(this, title);
        if (error) card.title.setColor(McKit.RED);
        card.addText(text, McKit.WHITE);
        card.addButton("OK", McKit.Button.PRIMARY, new View.OnClickListener() {
            public void onClick(View v) {
                card.dialog.dismiss();
                if (!error) finish();
            }
        });
        card.dialog.show();
    }
}
