package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.WorldBuilderMod;

import java.util.concurrent.*;
import java.util.function.Consumer;

/** Фоновая задача с прогрессом, отменой и оценкой оставшегося времени. */
public class Task {
    public static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "WorldBuilder-IO"); t.setDaemon(true); return t; });

    public interface Body { void run(Task t) throws Exception; }

    public final String title;
    public volatile String status = "";
    public volatile String sub = "";
    public volatile double progress; // 0..1, <0 — неопределённо
    public volatile boolean cancelled, done;
    public volatile Throwable error;
    public volatile long bytesDone, bytesTotal;
    private final long start = System.currentTimeMillis();
    private long speedT0 = System.currentTimeMillis(), speedB0;
    private double speed;
    public Future<?> future;

    public Task(String title) { this.title = title; }

    public static Task start(String title, Body body, Consumer<Task> onDone, ExecutorService ex) {
        Task t = new Task(title);
        t.future = ex.submit(() -> {
            try { body.run(t); }
            catch (Throwable e) { t.error = e; WorldBuilderMod.LOG.error("[WorldBuilder] Задача «{}» завершилась с ошибкой", title, e); }
            finally {
                t.done = true;
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> onDone.accept(t));
            }
        });
        return t;
    }

    public void check() { if (cancelled) throw new CancellationException("Отменено"); }

    public void set(String status, double p) { this.status = status; this.progress = p; }

    public double speed() {
        long now = System.currentTimeMillis();
        if (now - speedT0 > 700) { speed = (bytesDone - speedB0) * 1000.0 / (now - speedT0); speedT0 = now; speedB0 = bytesDone; }
        return speed;
    }

    public String eta() {
        if (progress <= 0.01) return "";
        long el = System.currentTimeMillis() - start;
        long rem = (long) (el / progress * (1 - progress)) / 1000;
        if (el < 1500) return "";
        return rem < 60 ? "осталось ~" + rem + " с" : "осталось ~" + (rem / 60) + " мин " + (rem % 60) + " с";
    }

    public static String bytes(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024 * 1024) return String.format("%.1f КБ", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f МБ", b / 1048576.0);
        return String.format("%.2f ГБ", b / 1073741824.0);
    }

    /** Диалог прогресса задачи. */
    public static class ProgressDialog extends WbScreen.Dialog {
        final Task task;
        public ProgressDialog(Task t) { super(t.title); this.task = t; w = 380; h = 110; }
        public boolean render(Gui g, int x, int y) {
            if (task.done) return true;
            g.text(g.ellipsize(task.status, w - 20), x + 10, y + 10, Gui.TEXT);
            g.text(g.ellipsize(task.sub, w - 20), x + 10, y + 22, Gui.TEXT2);
            int bw = w - 20;
            g.rrect(x + 10, y + 38, bw, 8, Gui.FIELD);
            if (task.progress >= 0) g.rect(x + 10, y + 38, (int) (bw * Math.min(1, task.progress)), 8, Gui.ACCENT);
            else { int p = (int) ((System.currentTimeMillis() / 8) % (bw + 60)) - 60; g.rect(x + 10 + Math.max(0, p), y + 38, Math.min(60, bw - Math.max(0, p)), 8, Gui.ACCENT); }
            String info = (task.progress >= 0 ? Math.round(task.progress * 100) + "%  " : "") + task.eta();
            if (task.bytesTotal > 0) info += "  " + bytes((long) task.speed()) + "/с";
            g.text(info, x + 10, y + 52, Gui.TEXT2);
            if (g.button("pcancel", x + w - 90, y + h - 18 - 24, 80, 18, task.cancelled ? "Отмена…" : "Отмена", null)) task.cancelled = true;
            return false;
        }
        public boolean onEsc() { task.cancelled = true; return false; }
    }
}
