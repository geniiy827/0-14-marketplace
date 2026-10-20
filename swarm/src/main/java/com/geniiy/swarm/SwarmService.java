package com.geniiy.swarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.geniiy.swarm.core.SwarmManager;

import java.util.ArrayList;
import java.util.List;

/** Держит рой ботов живым в фоне. */
public class SwarmService extends Service {
    public interface Listener { void onLog(String s); }

    static SwarmService instance;
    static final List<String> LOG = new ArrayList<>();
    static Listener listener;
    static boolean active;

    public SwarmManager swarm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        if (intent != null && "stop".equals(intent.getAction())) { stopAll(); return START_NOT_STICKY; }
        startForegroundNow();
        if (swarm == null) {
            swarm = new SwarmManager(this::log);
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swarm:wake");
                wake.acquire(6 * 60 * 60 * 1000L);
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "swarm:wifi"); wifi.acquire(); }
            } catch (Exception ignored) {}
        }
        active = true;
        if (intent != null) {
            swarm.host = intent.getStringExtra("host");
            swarm.port = intent.getIntExtra("port", 19132);
            String rt = intent.getStringExtra("reg");
            if (rt != null && !rt.isEmpty()) swarm.registerTemplate = rt;
            int add = intent.getIntExtra("add", 0);
            if (add > 0) swarm.add(add);
        }
        updateNote();
        return START_NOT_STICKY;
    }

    void stopAll() {
        active = false;
        if (swarm != null) { swarm.shutdown(); swarm = null; }
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        stopForeground(true);
        stopSelf();
        instance = null;
    }

    void updateNote() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (active) nm.notify(1, buildNotification());
        } catch (Exception ignored) {}
    }

    private void log(String s) {
        synchronized (LOG) { LOG.add(s); if (LOG.size() > 300) LOG.remove(0); }
        main.post(() -> { if (listener != null) listener.onLog(s); });
    }

    @Override public void onDestroy() { if (swarm != null) swarm.shutdown(); instance = null; active = false; super.onDestroy(); }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("swarm", "Swarm", NotificationManager.IMPORTANCE_LOW));
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
    }

    private Notification buildNotification() {
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), fl);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SwarmService.class).setAction("stop"), fl);
        String text = swarm != null ? ("Ботов онлайн: " + swarm.onlineCount() + " / " + swarm.total()) : "Готов";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "swarm") : new Notification.Builder(this);
        b.setContentTitle("Нагрузочный тест").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(open).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stop);
        return b.build();
    }
}
