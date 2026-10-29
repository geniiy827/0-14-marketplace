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
        final boolean ru = "ru".equals(java.util.Locale.getDefault().getLanguage());
        // no dialog: import in the background, bring the game up, the game shows a toast
        new Thread(new Runnable() {
            public void run() {
                PackManager.Pack pk = null;
                String err = null;
                try {
                    InputStream s = getContentResolver().openInputStream(uri);
                    if (s == null) throw new java.io.IOException(ru ? "нет доступа к файлу" : "no access to the file");
                    pk = PackManager.importStream(ImportActivity.this, s, "user", fileName, null, false, null);
                } catch (PackManager.PackException e) {
                    err = e.getMessage();
                } catch (SecurityException e) {
                    err = ru ? "нет доступа к файлу" : "no access to the file";
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                if (err == null) NovaActivity.postToast("@nova.toast.imported", pk.name, pk.icon());
                else NovaActivity.postToast("@nova.toast.importfail", err);
                runOnUiThread(new Runnable() {
                    public void run() {
                        Intent i = new Intent(ImportActivity.this, NovaActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(i);
                        finish();
                    }
                });
            }
        }).start();
    }
}
