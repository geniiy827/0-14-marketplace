package com.nova.client;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.graphics.BitmapFactory;

import java.io.InputStream;

/**
 * Opened when the user taps a .mcres file in a file manager ("Open with NOVA")
 * or shares it to the app. Imports the pack (disabled) and reports the result.
 */
public class ImportActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        Uri uri = null;
        Intent in = getIntent();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uri = (Uri) p;
        } else {
            uri = in.getData();
        }
        if (uri == null) { finish(); return; }
        start(uri);
    }

    private String displayName(Uri uri) {
        String name = uri.getLastPathSegment();
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try { if (c.moveToFirst() && c.getString(0) != null) name = c.getString(0); } finally { c.close(); }
            }
        } catch (Exception ignored) { }
        return name == null ? "файл" : name;
    }

    private void start(final Uri uri) {
        final String fileName = displayName(uri);
        final NovaUi.Card card = new NovaUi.Card(this, "Импорт набора ресурсов");
        final McKit.Text status = card.addText(fileName, McKit.GRAY);
        final NovaUi.Progress pb = card.addProgress();
        pb.setIndeterminate(true);
        card.dialog.setCancelable(false);
        card.dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) { finish(); }
        });
        card.dialog.show();

        new Thread(new Runnable() {
            public void run() {
                PackManager.Pack pk = null;
                String err = null;
                try {
                    InputStream s = getContentResolver().openInputStream(uri);
                    if (s == null) throw new java.io.IOException("нет доступа к файлу");
                    pk = PackManager.importStream(ImportActivity.this, s, "user", fileName, null, false, null);
                } catch (PackManager.PackException e) {
                    err = e.getMessage();
                } catch (SecurityException e) {
                    err = "Нет доступа к файлу. Попробуйте импортировать из меню «Наборы ресурсов».";
                } catch (Exception e) {
                    err = "Не удалось прочитать файл: " + e.getMessage();
                }
                final PackManager.Pack fpk = pk;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() { done(card, pb, status, fpk, ferr); }
                });
            }
        }).start();
    }

    private void done(final NovaUi.Card card, View pb, McKit.Text status, PackManager.Pack pk, String err) {
        int s = McKit.scale(this);
        card.body.removeView(pb);
        if (err != null) {
            card.title.setText("Ошибка импорта");
            card.title.setColor(McKit.RED);
            status.setText(err);
            status.setColor(McKit.WHITE);
            card.addButton("Закрыть", McKit.Button.NORMAL, new View.OnClickListener() {
                public void onClick(View v) { card.dialog.dismiss(); }
            });
            return;
        }
        card.title.setText("Успешный импорт!");
        card.body.removeView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new McKit.Icon(this, pk.icon().exists() ? BitmapFactory.decodeFile(pk.icon().getAbsolutePath()) : null, 34));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(7 * s, 0, 0, 0);
        info.addView(new McKit.Text(this, pk.name, McKit.WHITE), NovaUi.lp(-1, -2));
        if (!pk.description.isEmpty()) info.addView(new McKit.Text(this, pk.description, McKit.GRAY), NovaUi.lp(-1, -2));
        info.addView(new McKit.Text(this, "v" + pk.versionString() + (pk.author.isEmpty() ? "" : "  " + pk.author)
                + "  " + NovaUi.size(pk.size), McKit.DIM), NovaUi.lp(-1, -2));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.body.addView(row);
        McKit.Text hint = card.addText("Набор добавлен и пока выключен. Включите его в главном меню: «Наборы ресурсов».", McKit.GRAY);
        hint.setPadding(0, 5 * s, 0, 0);

        card.addButton("Готово", McKit.Button.NORMAL, new View.OnClickListener() {
            public void onClick(View v) { card.dialog.dismiss(); }
        });
        card.addButton("Открыть наборы", McKit.Button.PRIMARY, new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(ImportActivity.this, PacksActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                card.dialog.dismiss();
            }
        });
    }
}
