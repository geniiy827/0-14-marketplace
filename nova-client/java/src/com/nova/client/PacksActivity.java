package com.nova.client;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.InputStream;
import java.util.List;

/** "Наборы ресурсов": library of .mcres packs — on/off, priority, delete, import. Minecraft-styled. */
public class PacksActivity extends Activity {
    private static final int REQ_PICK = 4101;
    private static final int ROW_ON = 0xFF5E98A4;

    private LinearLayout list;
    private LinearLayout banner;
    private McKit.Text subtitle;
    private List<String> startupOrder;
    private int s;

    @Override
    protected void onCreate(Bundle b) {
        NovaUi.fullscreen(this);
        super.onCreate(b);
        s = McKit.scale(this);
        startupOrder = LanSync.startupOrder != null ? LanSync.startupOrder : PackManager.enabledOrder(this);
        build();
        refresh();
    }

    private void build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(14 * s, 8 * s, 14 * s, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(new McKit.Button(this, "< Назад", McKit.Button.NORMAL, new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        }).minWidth(56));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(10 * s, 0, 0, 0);
        titles.addView(new McKit.Text(this, "Наборы ресурсов", McKit.WHITE));
        subtitle = new McKit.Text(this, "", McKit.GRAY);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(new McKit.Button(this, "+ Импорт .mcres", McKit.Button.PRIMARY, new View.OnClickListener() {
            public void onClick(View v) { pickFile(); }
        }).minWidth(90));
        col.addView(header);

        LinearLayout.LayoutParams ll = NovaUi.lp(-1, s);
        ll.topMargin = 5 * s; ll.bottomMargin = 5 * s;
        col.addView(NovaUi.accentLine(this), ll);

        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackgroundDrawable(new McKit.PanelDrawable(this, 0xFF9784CF, 245));
        banner.setPadding(8 * s, 4 * s, 5 * s, 4 * s);
        banner.addView(new McKit.Text(this, "Изменения применятся после перезапуска игры", McKit.WHITE),
                new LinearLayout.LayoutParams(0, -2, 1));
        banner.addView(new McKit.Button(this, "Перезапустить", McKit.Button.PRIMARY, new View.OnClickListener() {
            public void onClick(View v) { NovaActivity.restartGame(PacksActivity.this); }
        }).minWidth(80));
        LinearLayout.LayoutParams blp = NovaUi.lp(-1, -2);
        blp.bottomMargin = 5 * s;
        col.addView(banner, blp);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, 8 * s);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        FrameLayout root = new FrameLayout(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private void refresh() {
        list.removeAllViews();
        List<PackManager.Pack> packs = PackManager.list(this);
        int active = 0;
        for (PackManager.Pack pk : packs) if (pk.enabled) active++;
        subtitle.setText(packs.size() + " в библиотеке, активно: " + active);
        banner.setVisibility(PackManager.enabledOrder(this).equals(startupOrder) ? View.GONE : View.VISIBLE);

        if (packs.isEmpty()) {
            McKit.Text e = new McKit.Text(this, "Пока нет ни одного набора. Откройте файл .mcres в проводнике "
                    + "(«Открыть с помощью» -> Minecraft PE) или нажмите «+ Импорт .mcres».", McKit.GRAY).wrap().center();
            LinearLayout.LayoutParams l = NovaUi.lp(-1, -2);
            l.topMargin = 30 * s;
            list.addView(e, l);
            return;
        }
        section("Активные", active == 0 ? "ничего не включено, игра использует стандартные ресурсы" : "верхний набор главнее");
        int i = 0;
        for (PackManager.Pack pk : packs) if (pk.enabled) list.addView(row(pk, i++));
        if (active < packs.size()) {
            section("Доступные", null);
            for (PackManager.Pack pk : packs) if (!pk.enabled) list.addView(row(pk, -1));
        }
    }

    private void section(String name, String hint) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.BOTTOM);
        r.setPadding(2 * s, 4 * s, 0, 2 * s);
        r.addView(new McKit.Text(this, name, McKit.ACCENT));
        if (hint != null) {
            McKit.Text h = new McKit.Text(this, "  " + hint, McKit.DIM);
            r.addView(h);
        }
        list.addView(r);
    }

    private View row(final PackManager.Pack pk, int activeIndex) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackgroundDrawable(new McKit.PanelDrawable(this, pk.enabled ? ROW_ON : McKit.PANEL, 240));
        c.setPadding(5 * s, 5 * s, 6 * s, 5 * s);
        LinearLayout.LayoutParams clp = NovaUi.lp(-1, -2);
        clp.bottomMargin = 4 * s;
        c.setLayoutParams(clp);

        c.addView(new McKit.Icon(this, pk.icon().exists() ? BitmapFactory.decodeFile(pk.icon().getAbsolutePath()) : null, 30));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(6 * s, 0, 6 * s, 0);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(new McKit.Text(this, pk.name, McKit.WHITE), new LinearLayout.LayoutParams(-2, -2));
        if (pk.fromLan()) nameRow.addView(new McKit.Text(this, "  [с сервера]", McKit.VIOLET));
        info.addView(nameRow);
        if (!pk.description.isEmpty()) info.addView(new McKit.Text(this, pk.description, McKit.GRAY), NovaUi.lp(-1, -2));
        info.addView(new McKit.Text(this, "v" + pk.versionString() + (pk.author.isEmpty() ? "" : "  " + pk.author)
                + "  " + NovaUi.size(pk.size), McKit.DIM), NovaUi.lp(-1, -2));
        c.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        if (activeIndex > 0) {
            McKit.Button up = new McKit.Button(this, "^", McKit.Button.NORMAL, new View.OnClickListener() {
                public void onClick(View v) {
                    try { PackManager.moveUp(PacksActivity.this, pk.uuid); } catch (Exception ignored) { }
                    refresh();
                }
            }).minWidth(22);
            LinearLayout.LayoutParams ul = NovaUi.lp(-2, -2);
            ul.rightMargin = 5 * s;
            c.addView(up, ul);
        }

        McKit.Toggle t = new McKit.Toggle(this, pk.enabled);
        t.setListener(new McKit.Toggle.Listener() {
            public void onToggle(McKit.Toggle tg, boolean on) {
                try { PackManager.setEnabled(PacksActivity.this, pk.uuid, on); } catch (Exception ignored) { }
                list.postDelayed(new Runnable() { public void run() { refresh(); } }, 150);
            }
        });
        c.addView(t);

        McKit.Button del = new McKit.Button(this, "Удалить", McKit.Button.DANGER, new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(pk); }
        }).minWidth(52);
        LinearLayout.LayoutParams dl = NovaUi.lp(-2, -2);
        dl.leftMargin = 6 * s;
        c.addView(del, dl);
        return c;
    }

    private void confirmDelete(final PackManager.Pack pk) {
        final NovaUi.Card card = new NovaUi.Card(this, "Удалить набор?");
        card.addText("«" + pk.name + "» будет удалён с устройства. Это действие нельзя отменить.", McKit.WHITE);
        card.addButton("Отмена", McKit.Button.NORMAL, new View.OnClickListener() {
            public void onClick(View v) { card.dialog.dismiss(); }
        });
        card.addButton("Удалить", McKit.Button.DANGER, new View.OnClickListener() {
            public void onClick(View v) {
                try { PackManager.delete(PacksActivity.this, pk.uuid); } catch (Exception ignored) { }
                card.dialog.dismiss();
                refresh();
            }
        });
        card.dialog.show();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Выберите файл .mcres"), REQ_PICK);
        } catch (Exception e) {
            showResult(null, "Не найден файловый менеджер для выбора файла.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final NovaUi.Card wait = new NovaUi.Card(this, "Импорт...");
        wait.addText("Распаковываем набор ресурсов", McKit.GRAY);
        wait.addProgress().setIndeterminate(true);
        wait.dialog.setCancelable(false);
        wait.dialog.show();
        new Thread(new Runnable() {
            public void run() {
                PackManager.Pack pk = null;
                String err = null;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    pk = PackManager.importStream(PacksActivity.this, in, "user", "", null, false, null);
                } catch (PackManager.PackException e) {
                    err = e.getMessage();
                } catch (Exception e) {
                    err = "Не удалось прочитать файл: " + e.getMessage();
                }
                final PackManager.Pack fpk = pk;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        wait.dialog.dismiss();
                        showResult(fpk, ferr);
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void showResult(PackManager.Pack pk, String err) {
        final NovaUi.Card card = new NovaUi.Card(this, err == null ? "Успешный импорт" : "Ошибка импорта");
        if (err == null) {
            card.addText("«" + pk.name + "» добавлен в библиотеку. Набор выключен, включите его переключателем.", McKit.WHITE);
        } else {
            card.title.setColor(McKit.RED);
            card.addText(err, McKit.WHITE);
        }
        card.addButton("OK", McKit.Button.PRIMARY, new View.OnClickListener() {
            public void onClick(View v) { card.dialog.dismiss(); }
        });
        card.dialog.show();
    }
}
