package com.geniiy.minebot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.geniiy.minebot.core.Bot;

import java.util.ArrayList;
import java.util.List;

/** Держит бота в фоне, пока ты играешь в Minecraft. */
public class BotService extends Service implements Bot.Ui {
    public interface Listener { void onStatus(String s); void onLog(String s); }

    static BotService instance;
    static final List<String> LOG = new ArrayList<>();
    static String status = "Бот не запущен";
    static Listener listener;
    static boolean active;

    private Bot bot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        if (intent != null && "stop".equals(intent.getAction())) { stopBot(); return START_NOT_STICKY; }
        startForegroundNow("Подключение…");
        SharedPreferences p = getSharedPreferences("bot", MODE_PRIVATE);
        if (bot != null) bot.disconnect();
        bot = new Bot(this);
        bot.name = p.getString("name", "MineBot");
        bot.host = p.getString("host", "127.0.0.1");
        try { bot.port = Integer.parseInt(p.getString("port", "19132")); } catch (Exception e) { bot.port = 19132; }
        bot.owner = p.getString("owner", "").trim();
        bot.answerInChat = p.getBoolean("answer", true);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minebot:bot");
            wake.acquire(6 * 60 * 60 * 1000L);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "minebot:wifi"); wifi.acquire(); }
        } catch (Exception ignored) {}
        active = true;
        try { bot.connect(); } catch (Exception e) { status("Ошибка запуска: " + e.getMessage()); }
        return START_NOT_STICKY;
    }

    void stopBot() {
        active = false;
        if (bot != null) { bot.disconnect(); bot = null; }
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        status = "Бот остановлен";
        if (listener != null) main.post(() -> { if (listener != null) listener.onStatus(status); });
        stopForeground(true);
        stopSelf();
    }

    void sayAsBot(String s) { if (bot != null) bot.chat(s); }
    String info() { return bot != null ? bot.info() : ""; }

    @Override public void onDestroy() { if (bot != null) bot.disconnect(); instance = null; active = false; super.onDestroy(); }

    private void startForegroundNow(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("bot", "MineBot", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
    }

    private Notification buildNotification(String text) {
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), fl);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BotService.class).setAction("stop"), fl);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "bot") : new Notification.Builder(this);
        b.setContentTitle("MineBot").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(open).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stop);
        return b.build();
    }

    // ---------- Bot.Ui ----------
    @Override public void status(String s) {
        status = s;
        main.post(() -> {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (active) nm.notify(1, buildNotification(s));
            } catch (Exception ignored) {}
            if (listener != null) listener.onStatus(s);
        });
    }

    @Override public void log(String s) {
        synchronized (LOG) { LOG.add(s); if (LOG.size() > 300) LOG.remove(0); }
        main.post(() -> { if (listener != null) listener.onLog(s); });
    }
}
