package com.geniiy.swarm.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Управляет роем ботов: добавление, тик ИИ всех сразу, отключение. */
public final class SwarmManager {
    public interface Ui { void log(String s); }

    public String host = "127.0.0.1";
    public int port = 19132;
    public String registerTemplate = "/register {pass} {pass}";
    public int connectDelayMs = 400;   // пауза между входами ботов

    private final Ui ui;
    private final Random rnd = new Random();
    private final ConcurrentHashMap<Integer, SwarmBot> bots = new ConcurrentHashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final AtomicInteger seq = new AtomicInteger(1);
    private final ConcurrentLinkedQueue<Integer> toConnect = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private Thread tickThread, connectThread;

    public SwarmManager(Ui ui) { this.ui = ui; startThreads(); }

    private void startThreads() {
        running = true;
        tickThread = new Thread(() -> {
            long next = System.currentTimeMillis();
            while (running) {
                next += 50;
                for (SwarmBot b : bots.values()) {
                    try { b.aiTick(); } catch (Exception ignored) {}
                }
                long sl = next - System.currentTimeMillis();
                if (sl > 0) { try { Thread.sleep(sl); } catch (InterruptedException e) { return; } }
                else if (sl < -1000) next = System.currentTimeMillis();
            }
        }, "swarm-tick");
        tickThread.setDaemon(true);
        tickThread.start();

        connectThread = new Thread(() -> {
            while (running) {
                Integer id = toConnect.poll();
                if (id == null) { try { Thread.sleep(80); } catch (InterruptedException e) { return; } continue; }
                SwarmBot b = bots.get(id);
                if (b != null) {
                    try { b.start(); } catch (Exception e) { ui.log(b.name + ": не удалось запустить (" + e.getMessage() + ")"); }
                }
                try { Thread.sleep(Math.max(50, connectDelayMs)); } catch (InterruptedException e) { return; }
            }
        }, "swarm-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /** Добавить n ботов (входят с задержкой друг за другом). */
    public synchronized void add(int n) {
        for (int i = 0; i < n; i++) {
            String name;
            int guard = 0;
            do { name = SwarmBot.randomName(rnd); } while (!usedNames.add(name) && guard++ < 50);
            String pass = SwarmBot.randomPassword(rnd);
            int id = seq.getAndIncrement();
            SwarmBot b = new SwarmBot(id, name, pass, host, port, registerTemplate, rnd.nextLong(), ui::log);
            bots.put(id, b);
            toConnect.add(id);
        }
        ui.log("Добавлено " + n + " ботов, заходят по очереди…");
    }

    public void remove(int id) {
        SwarmBot b = bots.remove(id);
        if (b != null) { b.stop(); usedNames.remove(b.name); }
    }

    public void stopAll() {
        toConnect.clear();
        for (SwarmBot b : new ArrayList<>(bots.values())) b.stop();
        bots.clear();
        usedNames.clear();
        ui.log("Все боты отключены.");
    }

    public void shutdown() { running = false; stopAll(); }

    public static final class Row { public int id; public String name, status; public boolean online; }

    public List<Row> snapshot() {
        List<SwarmBot> list = new ArrayList<>(bots.values());
        list.sort((a, c) -> Integer.compare(a.id, c.id));
        List<Row> r = new ArrayList<>();
        for (SwarmBot b : list) { Row row = new Row(); row.id = b.id; row.name = b.name; row.status = b.status; row.online = b.online; r.add(row); }
        return r;
    }

    public int total() { return bots.size(); }
    public int onlineCount() { int n = 0; for (SwarmBot b : bots.values()) if (b.online) n++; return n; }
    public int queued() { return toConnect.size(); }
}
