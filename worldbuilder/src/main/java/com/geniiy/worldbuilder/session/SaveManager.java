package com.geniiy.worldbuilder.session;

import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.pe.PeChunk;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.pe.RegionFile;
import com.geniiy.worldbuilder.remote.RemoteFs;
import com.geniiy.worldbuilder.remote.WorldSource;
import com.geniiy.worldbuilder.ui.Task;
import com.geniiy.worldbuilder.util.Pos;
import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Сохранение: ZIP, новый мир на сервер, перезапись на сервере (с резервной копией), папка; автосохранение сессии. */
public final class SaveManager {
    private SaveManager() {}

    public static Path sessionsRoot() { return WbConfig.gameDir().resolve("sessions"); }
    public static Path backupsRoot() { return WbConfig.gameDir().resolve("backups"); }

    public static String stamp() { return new SimpleDateFormat("yyyy-MM-dd_HH-mm").format(new Date()); }

    static PeWorld.SaveOptions opts(boolean prevent, boolean light) { PeWorld.SaveOptions o = new PeWorld.SaveOptions(); o.preventPopulation = prevent; o.computeLight = light; return o; }

    static PeWorld.Progress prog(Task t, double from, double to) {
        return new PeWorld.Progress() {
            public void update(String what, double f) { t.status = what; t.progress = from + (to - from) * f; }
            public boolean cancelled() { return t.cancelled; }
        };
    }

    static Path freshStaging(Editor ed) throws IOException {
        Path st = sessionsRoot().resolve(ed.sessionId).resolve("staging");
        WorldSource.deleteTree(st);
        Files.createDirectories(st);
        return st;
    }

    /** 1. Скачать ZIP (внутри — папка мира). */
    public static void saveZip(Editor ed, Path zip, boolean prevent, boolean light, Task t) throws IOException {
        Path st = freshStaging(ed);
        List<String> files = ed.world.writeChanged(st, opts(prevent, light), prog(t, 0, 0.6));
        t.set("Упаковываю ZIP…", 0.7);
        String name = ed.world.dir.getFileName().toString();
        WorldSource.writeZip(zip, name, ed.world.dir, st, files, ed.world.allFiles());
        ed.world.commitSaved(st, files);
        t.set("Готово", 1);
    }

    /** Сохранить мир целиком в папку на диске. */
    public static void saveFolder(Editor ed, Path dir, boolean prevent, boolean light, Task t) throws IOException {
        Path st = freshStaging(ed);
        List<String> files = ed.world.writeChanged(st, opts(prevent, light), prog(t, 0, 0.6));
        Files.createDirectories(dir.resolve("region"));
        Set<String> all = new LinkedHashSet<>(ed.world.allFiles());
        all.addAll(files);
        int i = 0;
        for (String f : all) {
            Path src = files.contains(f) ? st.resolve(f) : ed.world.dir.resolve(f);
            Path dst = dir.resolve(f);
            Path tmp = dst.resolveSibling(dst.getFileName() + ".wbtmp");
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            t.set("Копирую " + f, 0.6 + 0.4 * (++i) / all.size());
        }
        ed.world.commitSaved(st, files);
    }

    /** Проверка конфликта: изменились ли файлы на сервере после открытия. Возвращает список изменённых. */
    public static List<String> conflicts(Editor ed) throws IOException {
        WorldSource s = ed.source;
        Map<String, long[]> now = WorldSource.listRemoteWorld(s.fs, s.remotePath);
        List<String> changed = new ArrayList<>();
        for (var e : now.entrySet()) {
            long[] o = s.snapshot.get(e.getKey());
            if (o == null || o[0] != e.getValue()[0] || o[1] != e.getValue()[1]) changed.add(e.getKey());
        }
        for (String k : s.snapshot.keySet()) if (!now.containsKey(k)) changed.add(k);
        return changed;
    }

    /** Загрузить файл на сервер безопасно: временное имя → проверка размера → замена. */
    static void uploadSafe(RemoteFs fs, Path local, String remote, Task t, long[] doneBytes) throws IOException {
        String tmp = remote + ".wbtmp";
        long base = doneBytes[0];
        fs.upload(local, tmp, new RemoteFs.Progress() {
            public void bytes(long d, long total) { t.bytesDone = base + d; if (t.bytesTotal > 0) t.progress = 0.3 + 0.7 * t.bytesDone / t.bytesTotal; }
            public boolean cancelled() { return t.cancelled; }
        });
        long rs = fs.size(tmp);
        if (rs != Files.size(local)) throw new IOException("Размер временного файла на сервере не совпал: " + tmp);
        if (fs.size(remote) >= 0) {
            String old = remote + ".wbold";
            try { fs.delete(old); } catch (IOException ignored) {}
            try { fs.rename(remote, old); } catch (IOException e) { fs.delete(remote); }
            fs.rename(tmp, remote);
            try { fs.delete(old); } catch (IOException ignored) {}
        } else fs.rename(tmp, remote);
        doneBytes[0] += Files.size(local);
    }

    /** 2. Загрузить на сервер как новый мир. */
    public static void uploadNew(Editor ed, String parentDir, String name, boolean prevent, boolean light, Task t) throws IOException {
        RemoteFs fs = ed.source.fs;
        String target = RemoteFs.join(parentDir, name);
        if (fs.exists(target)) throw new IOException("Папка " + target + " уже существует на сервере");
        Path st = freshStaging(ed);
        List<String> files = ed.world.writeChanged(st, opts(prevent, light), prog(t, 0, 0.3));
        Set<String> all = new LinkedHashSet<>(ed.world.allFiles());
        all.addAll(files);
        fs.mkdirs(RemoteFs.join(target, "region"));
        long total = 0;
        for (String f : all) { Path p = files.contains(f) ? st.resolve(f) : ed.world.dir.resolve(f); if (Files.exists(p)) total += Files.size(p); }
        t.bytesTotal = total;
        long[] done = {0};
        for (String f : all) {
            t.check();
            Path p = files.contains(f) ? st.resolve(f) : ed.world.dir.resolve(f);
            if (!Files.exists(p)) continue;
            t.status = "Загружаю " + f;
            uploadSafe(fs, p, RemoteFs.join(target, f), t, done);
        }
        ed.world.commitSaved(st, files);
    }

    /** 3. Перезаписать открытый мир на сервере (только изменённые регионы и level.dat). */
    public static void overwrite(Editor ed, boolean backup, boolean serverBackup, boolean prevent, boolean light, Task t) throws IOException {
        WorldSource s = ed.source;
        RemoteFs fs = s.fs;
        if (backup) {
            t.set("Резервная копия: скачиваю текущие файлы мира…", 0.02);
            Map<String, long[]> now = WorldSource.listRemoteWorld(fs, s.remotePath);
            Path bdir = backupsRoot().resolve(s.profile.cacheKey()).resolve(Paths.get(s.remotePath).getFileName().toString()).resolve(stamp());
            int i = 0;
            for (String f : now.keySet()) {
                t.check();
                t.sub = f;
                fs.download(RemoteFs.join(s.remotePath, f), bdir.resolve(f), null);
                t.progress = 0.02 + 0.13 * (++i) / now.size();
            }
            pruneBackups(bdir.getParent(), WbConfig.get().keepBackups);
            if (serverBackup) {
                String bname = s.remotePath + "_backup_" + stamp();
                t.set("Копия на сервере: " + bname, 0.16);
                fs.mkdirs(RemoteFs.join(bname, "region"));
                for (String f : now.keySet()) { t.check(); fs.upload(bdir.resolve(f), RemoteFs.join(bname, f), null); }
            }
        }
        Path st = freshStaging(ed);
        List<String> files = ed.world.writeChanged(st, opts(prevent, light), prog(t, 0.2, 0.3));
        long total = 0;
        for (String f : files) total += Files.size(st.resolve(f));
        t.bytesTotal = total;
        long[] done = {0};
        fs.mkdirs(RemoteFs.join(s.remotePath, "region"));
        for (String f : files) {
            t.check();
            t.status = "Загружаю " + f;
            uploadSafe(fs, st.resolve(f), RemoteFs.join(s.remotePath, f), t, done);
        }
        ed.world.commitSaved(st, files);
        for (String f : files) { Path dst = s.cacheDir.resolve(f); Files.createDirectories(dst.getParent()); Files.copy(st.resolve(f), dst, StandardCopyOption.REPLACE_EXISTING); }
        s.snapshot = WorldSource.listRemoteWorld(fs, s.remotePath);
        WorldSource.writeSnapshotPublic(s.cacheDir, s.snapshot);
    }

    static void pruneBackups(Path dir, int keep) {
        try (var st = Files.list(dir)) {
            List<Path> l = new ArrayList<>(st.filter(Files::isDirectory).sorted().toList());
            while (l.size() > Math.max(1, keep)) WorldSource.deleteTree(l.remove(0));
        } catch (IOException ignored) {}
    }

    // ---------- автосохранение сессии ----------
    public static void autosave(Editor ed) throws IOException {
        Path dir = sessionsRoot().resolve(ed.sessionId);
        Path data = dir.resolve("autosave");
        Path tmp = dir.resolve("autosave.tmp");
        WorldSource.deleteTree(tmp);
        Files.createDirectories(tmp);
        List<PeChunk> dirty = ed.world.dirtyChunks();
        Set<String> regs = new LinkedHashSet<>();
        for (PeChunk c : dirty) regs.add("region/" + RegionFile.fileName(c.cx >> 5, c.cz >> 5));
        // пишем без пересчёта света и без снятия пометок «изменён»
        PeWorld.SaveOptions o = opts(true, false);
        ed.world.writeChanged(tmp, o, null);
        WorldSource.deleteTree(data);
        Files.move(tmp, data);
        writeSessionInfo(ed, true, new ArrayList<>(regs));
        ed.lastAutosave = System.currentTimeMillis();
    }

    public static void writeSessionInfo(Editor ed, boolean open, List<String> dirtyRegions) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("open", open);
        o.addProperty("time", System.currentTimeMillis());
        o.addProperty("world", ed.world.level.levelName());
        o.addProperty("cacheDir", ed.source.cacheDir.toString());
        if (ed.source.profile != null) { o.addProperty("profile", ed.source.profile.id); o.addProperty("remotePath", ed.source.remotePath); }
        if (ed.source.offlinePath != null) o.addProperty("offlinePath", ed.source.offlinePath.toString());
        JsonArray a = new JsonArray();
        if (dirtyRegions != null) for (String r : dirtyRegions) a.add(r);
        o.add("regions", a);
        Path dir = sessionsRoot().resolve(ed.sessionId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("session.json"), o.toString(), StandardCharsets.UTF_8);
    }

    public static void closeSession(Editor ed) {
        try { WorldSource.deleteTree(sessionsRoot().resolve(ed.sessionId)); } catch (IOException ignored) {}
    }

    /** Удалить старые закрытые сессии (рабочие копии), кроме текущей. */
    public static void cleanupClosed(String keep) {
        try {
            if (!Files.isDirectory(sessionsRoot())) return;
            try (var st = Files.list(sessionsRoot())) {
                for (Path d : st.toList()) {
                    if (d.getFileName().toString().equals(keep)) continue;
                    Path f = d.resolve("session.json");
                    boolean open = false;
                    try { if (Files.exists(f)) open = JsonParser.parseString(Files.readString(f)).getAsJsonObject().get("open").getAsBoolean(); } catch (Exception ignored) {}
                    if (!open || !Files.isDirectory(d.resolve("autosave"))) WorldSource.deleteTree(d);
                }
            }
        } catch (IOException ignored) {}
    }

    /** Найти незакрытую сессию (после вылета). */
    public static JsonObject findCrashed() {
        try {
            if (!Files.isDirectory(sessionsRoot())) return null;
            try (var st = Files.list(sessionsRoot())) {
                for (Path d : st.toList()) {
                    Path f = d.resolve("session.json");
                    if (!Files.exists(f) || !Files.isDirectory(d.resolve("autosave"))) continue;
                    JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                    if (o.has("open") && o.get("open").getAsBoolean()) { o.addProperty("dir", d.toString()); return o; }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Рабочая копия сеанса (копия кэша, чтобы кэш всегда совпадал с сервером). */
    public static Path workDir(String sessionId) { return sessionsRoot().resolve(sessionId).resolve("work"); }

    public static Path makeWorkCopy(Path cacheDir, String sessionId) throws IOException {
        Path work = workDir(sessionId);
        WorldSource.deleteTree(work);
        Files.createDirectories(work.resolve("region"));
        try (var w = Files.walk(cacheDir)) {
            for (Path p : w.toList()) {
                if (Files.isDirectory(p)) continue;
                String rel = cacheDir.relativize(p).toString().replace('\\', '/');
                if (!(rel.equals("level.dat") || rel.startsWith("region/"))) continue;
                Path dst = work.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return work;
    }

    /** Восстановить: рабочая копия сеанса + файлы автосохранения; регионы помечаются изменёнными. */
    public static PeWorld restore(JsonObject o) throws IOException {
        Path d = Paths.get(o.get("dir").getAsString());
        Path auto = d.resolve("autosave");
        Path work = d.resolve("work");
        if (!Files.isDirectory(work)) work = Paths.get(o.get("cacheDir").getAsString());
        try (var w = Files.walk(auto)) {
            for (Path p : w.toList()) {
                if (Files.isDirectory(p)) continue;
                Path dst = work.resolve(auto.relativize(p).toString());
                Files.createDirectories(dst.getParent());
                Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        PeWorld w = PeWorld.open(work);
        for (JsonElement e : o.getAsJsonArray("regions")) {
            int[] rc = RegionFile.parseName(e.getAsString().substring(7));
            if (rc == null) continue;
            RegionFile r = RegionFile.read(work.resolve(e.getAsString()), rc[0], rc[1]);
            for (int i = 0; i < 1024; i++) if (r.chunks[i] != null) {
                PeChunk c = w.getChunk(rc[0] * 32 + (i & 31), rc[1] * 32 + (i >> 5));
                if (c != null) c.markDirty();
            }
        }
        w.levelDirty = true;
        WorldSource.deleteTree(auto);
        return w;
    }

    public static void discard(JsonObject o) {
        try { WorldSource.deleteTree(Paths.get(o.get("dir").getAsString())); } catch (IOException ignored) {}
    }

    static long dummy() { return Pos.pack(0, 0, 0); }
}
