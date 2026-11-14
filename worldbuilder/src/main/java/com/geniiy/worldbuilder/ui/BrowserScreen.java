package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.config.ServerProfile;
import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.pe.LevelDat;
import com.geniiy.worldbuilder.remote.*;

import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Экран «Выбор мира»: файловый браузер сервера с автоопределением миров (раздел 9 ТЗ). */
public class BrowserScreen extends WbScreen {
    private final ConnectScreen parent;
    private final ServerProfile prof;
    final RemoteFs fs;
    private String path;
    private List<RemoteFs.Entry> entries = new ArrayList<>();
    private final Map<String, WorldInfo> worlds = new ConcurrentHashMap<>();
    private String selected;
    private double off;
    private boolean loading;
    private String error;
    private int sortCol = 0; private boolean sortAsc = true;
    private String goPath = "";
    private long lastClick; private String lastClickName;
    private long lastNoop = System.currentTimeMillis();

    static class WorldInfo {
        int regions; long size; long mtime;
        String levelName, generator, spawn;
        Boolean cacheOk;
        boolean details;
        Map<String, long[]> files;
    }

    public BrowserScreen(ConnectScreen parent, ServerProfile prof, RemoteFs fs) {
        super("Выбор мира");
        this.parent = parent; this.prof = prof; this.fs = fs;
        String start = prof.startDir == null || prof.startDir.isBlank() ? "/" : prof.startDir;
        path = start;
        load(start, true);
    }

    private void load(String p, boolean autoWorlds) {
        loading = true; error = null;
        Task.start("Список", t -> {
            List<RemoteFs.Entry> l = fs.list(p);
            String target = p;
            if (autoWorlds) for (RemoteFs.Entry e : l) if (e.dir() && e.name().equals("worlds")) { target = RemoteFs.join(p, "worlds"); l = fs.list(target); break; }
            String fp = target;
            List<RemoteFs.Entry> fl = l;
            client.execute(() -> { path = fp; entries = new ArrayList<>(fl); worlds.clear(); selected = null; off = 0; goPath = fp; });
            // автоопределение миров
            int scanned = 0;
            for (RemoteFs.Entry e : fl) {
                if (!e.dir() || scanned++ > 60) continue;
                try {
                    String wp = RemoteFs.join(fp, e.name());
                    Map<String, long[]> files = WorldSource.listRemoteWorld(fs, wp);
                    if (files.containsKey("level.dat") && files.size() > 1) {
                        WorldInfo wi = new WorldInfo();
                        wi.files = files;
                        for (var f : files.entrySet()) { if (f.getKey().startsWith("region/")) wi.regions++; wi.size += Math.max(0, f.getValue()[0]); wi.mtime = Math.max(wi.mtime, f.getValue()[1]); }
                        wi.cacheOk = WorldSource.cacheMatches(prof, wp, files);
                        worlds.put(e.name(), wi);
                    }
                } catch (Exception ignored) {}
            }
            // если сама папка — мир
        }, t -> { loading = false; if (t.error != null) error = RemoteFs.humanError(t.error); }, Task.IO);
    }

    private void loadDetails(String name, WorldInfo wi) {
        if (wi.details) return;
        wi.details = true;
        Task.start("level.dat", t -> {
            Path tmp = Files.createTempFile("wb_level", ".dat");
            fs.download(RemoteFs.join(RemoteFs.join(path, name), "level.dat"), tmp, null);
            LevelDat l = LevelDat.read(tmp);
            wi.levelName = l.levelName();
            wi.generator = l.generatorName() + (l.generatorOptions().isEmpty() ? "" : " (" + l.generatorOptions() + ")");
            wi.spawn = l.spawnX() + ", " + l.spawnY() + ", " + l.spawnZ();
            Files.deleteIfExists(tmp);
        }, t -> {}, Task.IO);
    }

    @Override
    protected void draw(float delta) {
        if (System.currentTimeMillis() - lastNoop > 30000) { lastNoop = System.currentTimeMillis(); Task.IO.submit(fs::keepAlive); }
        gui.rect(0, 0, W, H, 0xFF17181B);
        gui.text("Выбор мира — " + prof.title() + " (" + fs.describe() + ")", 12, 10, Gui.TEXT);
        int top = 26;
        // хлебные крошки
        int x = 12;
        if (gui.button("up", x, top, 40, 18, "Вверх", "На уровень выше")) load(RemoteFs.parent(path), false);
        x += 44;
        if (gui.button("refresh", x, top, 70, 18, "Обновить", null)) load(path, false);
        x += 74;
        if (gui.button("home", x, top, 50, 18, "Домой", "Начальная папка профиля")) load(prof.startDir.isBlank() ? "/" : prof.startDir, false);
        x += 56;
        String[] parts = path.split("/");
        String acc = "";
        if (gui.button("crumb_root", x, top, 16, 18, "/", null)) load("/", false);
        x += 18;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            acc += "/" + part;
            int bw = gui.width(part) + 10;
            if (x + bw > W - 220) break;
            String target = acc;
            if (gui.button("crumb" + acc, x, top, bw, 18, part, null)) load(target, false);
            x += bw + 2;
        }
        goPath = gui.field("goto", W - 212, top, 170, 18, goPath, "путь…", false, false);
        if (gui.button("go", W - 38, top, 30, 18, "→", "Перейти по пути")) load(goPath, false);
        if ("goto".equals(gui.focus) && gui.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER)) { gui.focus = null; load(goPath, false); }

        int ly = top + 26;
        int cardW = Math.min(300, W / 3);
        int lw = W - 24 - cardW - 8;
        gui.panel(12, ly, lw, H - ly - 44);
        // заголовки
        String[] cols = {"Имя", "Размер", "Изменён"};
        int[] cx = {18, 12 + lw - 190, 12 + lw - 120};
        for (int i = 0; i < 3; i++) {
            String lbl = cols[i] + (sortCol == i ? (sortAsc ? " ▲" : " ▼") : "");
            if (gui.hover(cx[i], ly + 4, 70, 12)) { gui.text(lbl, cx[i], ly + 6, Gui.ACCENT); if (gui.clicked(cx[i], ly + 4, 70, 12, 0)) { if (sortCol == i) sortAsc = !sortAsc; else { sortCol = i; sortAsc = true; } } }
            else gui.text(lbl, cx[i], ly + 6, Gui.TEXT2);
        }
        gui.hsep(14, ly + 18, lw - 4);
        List<RemoteFs.Entry> sorted = new ArrayList<>(entries);
        Comparator<RemoteFs.Entry> cmp = switch (sortCol) {
            case 1 -> Comparator.comparingLong(RemoteFs.Entry::size);
            case 2 -> Comparator.comparingLong(RemoteFs.Entry::mtime);
            default -> Comparator.comparing(e -> e.name().toLowerCase());
        };
        if (!sortAsc) cmp = cmp.reversed();
        sorted.sort(Comparator.comparing((RemoteFs.Entry e) -> !worlds.containsKey(e.name())).thenComparing((RemoteFs.Entry e) -> !e.dir()).thenComparing(cmp));
        int rowH = 16, listY = ly + 20, listH = H - ly - 44 - 22;
        off = gui.scrollArea("blist", 12, listY, lw, listH, sorted.size() * rowH, off);
        gui.scissor(12, listY, lw, listH);
        SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        for (int i = 0; i < sorted.size(); i++) {
            RemoteFs.Entry e = sorted.get(i);
            int y = listY + i * rowH - (int) off;
            if (y + rowH < listY || y > listY + listH) continue;
            WorldInfo wi = worlds.get(e.name());
            boolean sel = e.name().equals(selected);
            boolean hv = gui.hover(14, y, lw - 8, rowH) && gui.my >= listY && gui.my < listY + listH;
            gui.rect(14, y, lw - 8, rowH, sel ? Gui.BTN_SEL : hv ? Gui.BTN_H : (wi != null ? 0x2041DC97 : 0));
            String icon = wi != null ? "🌍" : e.dir() ? "📁" : "  ";
            gui.text(wi != null ? "◆" : e.dir() ? "▸" : "·", 18, y + 4, wi != null ? Gui.ACCENT : Gui.TEXT2);
            String nm = e.name() + (wi != null ? "   мир: " + wi.regions + " рег., " + Task.bytes(wi.size) : "");
            gui.text(gui.ellipsize(nm, lw - 220), 30, y + 4, wi != null ? Gui.ACCENT : Gui.TEXT);
            if (!e.dir()) gui.text(Task.bytes(e.size()), cx[1], y + 4, Gui.TEXT2);
            if (e.mtime() > 0) gui.text(df.format(new Date(e.mtime())), cx[2], y + 4, Gui.TEXT2);
            if (hv && gui.clicked(14, y, lw - 8, rowH, 0)) {
                long now = System.currentTimeMillis();
                if (e.name().equals(lastClickName) && now - lastClick < 400) {
                    if (wi != null) openWorld(e.name());
                    else if (e.dir()) load(RemoteFs.join(path, e.name()), false);
                }
                lastClick = now; lastClickName = e.name();
                selected = e.name();
                if (wi != null) loadDetails(e.name(), wi);
            }
        }
        gui.noScissor();
        if (loading) gui.textC("Загрузка…", 12 + lw / 2, listY + listH / 2, Gui.TEXT2);
        else if (error != null) gui.textC(error, 12 + lw / 2, listY + listH / 2, Gui.ERR);
        else if (worlds.isEmpty() && !entries.isEmpty()) gui.text("Миры не найдены здесь. Обычно они лежат в папке worlds рядом с server.properties.", 18, H - 56, 0xFF6A6D72);

        // карточка
        int kx = 12 + lw + 8;
        gui.panel(kx, ly, cardW, H - ly - 44);
        WorldInfo wi = selected == null ? null : worlds.get(selected);
        if (wi == null) {
            for (String l : wrap(gui, "Выбери мир в списке (папки с level.dat и region/*.mcr отмечены значком ◆). Двойной клик — открыть.", cardW - 16)) { gui.text(l, kx + 8, ly + 10 + 0, Gui.TEXT2); ly += 10; }
        } else {
            int y = ly + 8;
            gui.text(selected, kx + 8, y, Gui.ACCENT); y += 14;
            String[][] rows = {
                    {"Путь", RemoteFs.join(path, selected)},
                    {"Имя (LevelName)", wi.levelName == null ? "…" : wi.levelName},
                    {"Генератор", wi.generator == null ? "…" : wi.generator},
                    {"Спавн", wi.spawn == null ? "…" : wi.spawn},
                    {"Регионов", String.valueOf(wi.regions)},
                    {"Размер", Task.bytes(wi.size)},
                    {"Изменён", wi.mtime > 0 ? df.format(new Date(wi.mtime)) : "—"},
                    {"Копия в кэше", Boolean.TRUE.equals(wi.cacheOk) ? "есть, совпадает с сервером" : "нет или устарела"},
            };
            for (String[] r : rows) {
                gui.text(r[0], kx + 8, y, Gui.TEXT2);
                for (String l : wrap(gui, r[1], cardW - 16)) { y += 10; gui.text(l, kx + 8, y, Gui.TEXT); }
                y += 14;
            }
        }
        int by = H - 34;
        if (gui.button("disc", 12, by, 110, 22, "Отключиться", null)) close();
        if (gui.button("new", W - 12 - 150 - 8 - 150, by, 150, 22, "Создать новый мир", null))
            client.setScreen(new NewWorldScreen(this, prof, fs, path));
        if (gui.primary("open", W - 12 - 150, by, 150, 22, "Открыть", null, wi != null)) openWorld(selected);
    }

    private void openWorld(String name) {
        String wp = RemoteFs.join(path, name);
        Task task = Task.start("Открытие мира " + name, t -> {
            WorldSource s = WorldSource.download(prof, fs, wp, t);
            client.execute(() -> EditorLauncher.open(this, s, null));
        }, t -> { if (t.error != null && !t.cancelled) toastError("Не удалось открыть мир: " + RemoteFs.humanError(t.error), t.error); }, Task.IO);
        dialogs.push(new Task.ProgressDialog(task));
    }

    public String currentPath() { return path; }

    @Override public void close() { Task.IO.submit(fs::close); client.setScreen(parent); }
    @Override protected void onEscape() { close(); }
}
