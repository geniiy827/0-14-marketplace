package com.nova.client;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Runs in its own process (":nova_restart"): waits for the game process to die,
 * then starts the game again so enabled packs are loaded.
 */
public class RestartActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        NovaUi.fullscreen(this);
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(new McKit.Text(this, "N O V A", McKit.ACCENT).center(), new LinearLayout.LayoutParams(-1, -2));
        col.addView(new McKit.Text(this, "Применяем наборы ресурсов...", McKit.WHITE).center(), new LinearLayout.LayoutParams(-1, -2));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(root);
        new Handler().postDelayed(new Runnable() {
            public void run() {
                Intent i = new Intent(RestartActivity.this, NovaActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
                overridePendingTransition(0, 0);
                new Handler().postDelayed(new Runnable() {
                    public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
                }, 400);
            }
        }, 900);
    }
}
