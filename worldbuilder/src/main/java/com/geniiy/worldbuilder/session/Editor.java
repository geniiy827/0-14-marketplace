package com.geniiy.worldbuilder.session;

import com.geniiy.worldbuilder.WorldBuilderMod;
import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.edit.*;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.remote.WorldSource;
import net.minecraft.client.MinecraftClient;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Состояние сеанса редактирования: мир, история, выделение, буфер, хотбар, поток операций. */
public class Editor {
    public final PeWorld world;
    public final WorldSource source;
    public final UndoHistory history = new UndoHistory();
    public Selection selection = new Selection();
    public final List<Clipboard> clipboards = new ArrayList<>();
    public Clipboard clip;
    public final Pattern[] hotbar = new Pattern[9];
    public int slot;
    public final Map<String, Pattern> patternLibrary = new LinkedHashMap<>();
    public final Map<String, Mask> maskLibrary = new LinkedHashMap<>();
    public final Map<String, Selection> savedSelections = new LinkedHashMap<>();
    public final String sessionId;
    public long lastAutosave = System.currentTimeMillis();

    private final ExecutorService ops = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "WorldBuilder-Ops"); t.setDaemon(true); return t; });
    public volatile Op running;
    private final ConcurrentLinkedQueue<Op> queue = new ConcurrentLinkedQueue<>();

    public Editor(PeWorld world, WorldSource source) { this(world, source, Long.toString(System.currentTimeMillis(), 36)); }

    public Editor(PeWorld world, WorldSource source, String sessionId) {
        this.sessionId = sessionId;
        this.world = world;
        this.source = source;
        WbConfig c = WbConfig.get();
        for (int i = 0; i < 9; i++) hotbar[i] = Pattern.single(c.hotbar[i]);
        history.maxSteps = c.undoSteps;
        history.maxBytes = (long) c.undoMemoryMb << 20;
    }

    public Pattern active() { return hotbar[slot]; }
    public int activeState() { return hotbar[slot].first(); }

    public void saveHotbar() {
        WbConfig c = WbConfig.get();
        for (int i = 0; i < 9; i++) c.hotbar[i] = hotbar[i].first();
        c.save();
    }

    /** Долгая операция в потоке операций: прогресс, отмена (с откатом уже сделанного). */
    public static class Op implements Ops.P {
        public final String name, icon;
        public final ChangeSet cs;
        public volatile double progress;
        public volatile boolean cancelled, done;
        public volatile Throwable error;
        public volatile String result;
        final long start = System.currentTimeMillis();
        Op(String name, String icon) { this.name = name; this.icon = icon; this.cs = new ChangeSet(name, icon); }
        public void check() { if (cancelled) throw new CancellationException(); }
        public void progress(double p) { progress = p; }
        public String eta() {
            if (progress < 0.02) return "";
            long el = System.currentTimeMillis() - start;
            long rem = (long) (el / progress * (1 - progress)) / 1000;
            return el < 800 ? "" : "осталось ~" + rem + " с";
        }
    }

    public interface Body { String run(Op op) throws Exception; }

    /**
     * Выполнить правку в фоне. По завершении шаг попадает в историю, вызывается done (на главном потоке).
     */
    public Op run(String name, String icon, Body body, Consumer<Op> done) {
        Op op = new Op(name, icon);
        queue.add(op);
        ops.submit(() -> {
            running = op;
            try { op.result = body.run(op); }
            catch (CancellationException e) { op.cancelled = true; }
            catch (Throwable e) { op.error = e; WorldBuilderMod.LOG.error("[WorldBuilder] Операция «{}»", name, e); }
            if (op.cancelled || op.error != null) {
                try { world.undo(op.cs); } catch (Throwable e2) { WorldBuilderMod.LOG.error("[WorldBuilder] Откат", e2); }
            }
            op.done = true;
            running = null;
            queue.remove(op);
            MinecraftClient.getInstance().execute(() -> {
                if (!op.cancelled && op.error == null) history.push(op.cs);
                if (done != null) done.accept(op);
            });
        });
        return op;
    }

    /** Выполнить задачу в потоке операций без записи в историю (например, мазок кисти с общим ChangeSet). */
    public void enqueue(Runnable r) { ops.submit(() -> { try { r.run(); } catch (Throwable e) { WorldBuilderMod.LOG.error("[WorldBuilder] Задача", e); } }); }

    public boolean busy() { return running != null || !queue.isEmpty(); }

    public void undo(Consumer<String> report) {
        if (busy()) { report.accept("Подожди окончания операции"); return; }
        ChangeSet cs = history.undoStep();
        if (cs == null) { report.accept("Нечего отменять"); return; }
        world.undo(cs);
        report.accept("Отменено: " + cs.name);
    }

    public void redo(Consumer<String> report) {
        if (busy()) { report.accept("Подожди окончания операции"); return; }
        ChangeSet cs = history.redoStep();
        if (cs == null) { report.accept("Нечего повторять"); return; }
        world.redo(cs);
        report.accept("Повторено: " + cs.name);
    }

    /** Откатить/вернуть историю до шага index (число применённых шагов). */
    public void jumpTo(int index) {
        if (busy()) return;
        while (history.pos > index) world.undo(history.undoStep());
        while (history.pos < index) world.redo(history.redoStep());
    }

    public void addClipboard(Clipboard c) {
        clipboards.add(0, c);
        clip = c;
        while (clipboards.size() > 10) {
            int i = clipboards.size() - 1;
            while (i >= 0 && clipboards.get(i).pinned) i--;
            if (i < 0) break;
            clipboards.remove(i);
        }
    }

    public void shutdown() { ops.shutdownNow(); }
}
