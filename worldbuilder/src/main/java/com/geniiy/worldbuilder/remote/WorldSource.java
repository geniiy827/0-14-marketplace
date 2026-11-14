package com.geniiy.worldbuilder.remote;

import com.geniiy.worldbuilder.config.ServerProfile;
import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.pe.RegionFile;
import com.geniiy.worldbuilder.ui.Task;
import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Откуда открыт мир: сервер (профиль + путь) или диск/ZIP. Отвечает за кэш, «снимок» файлов сервера
 * (для проверки конфликтов) и передачу файлов.
 */
public class WorldSource {
    public ServerProfile profile;   // null — офлайн
    public RemoteFs fs;
    public String remotePath;       // папка мира на сервере
    public Path offlinePath;        // папка или ZIP на диске
    public Path cacheDir;           // рабочая копия
    /** Снимок файлов сервера на момент открытия: относительный путь → {size, mtime}. */
    public Map<String, long[]> snapshot = new LinkedHashMap<>();

    public boolean isRemote() { return profile != null && fs != null; }
    public String title() { return isRemote() ? profile.title() + ":" + remotePath : String.valueOf(offlinePath); }

    public static Path cacheRoot() { return WbConfig.gameDir().resolve("cache"); }

    /** Список файлов мира на сервере (level.dat + region/*.mcr) с размерами. */
    public static Map<String, long[]> listRemoteWorld(RemoteFs fs, String path) throws IOException {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (RemoteFs.Entry e : fs.list(path)) if (!e.dir() && e.name().equals("level.dat")) m.put("level.dat", new long[]{e.size(), e.mtime()});
        try {
            for (RemoteFs.Entry e : fs.list(RemoteFs.join(path, "region")))
                if (!e.dir() && RegionFile.parseName(e.name()) != null) m.put("region/" + e.name(), new long[]{e.size(), e.mtime()});
        } catch (IOException ignored) {}
        return m;
    }

    /** Скачать мир с сервера в кэш (только изменившиеся файлы). */
    public static WorldSource download(ServerProfile prof, RemoteFs fs, String path, Task t) throws IOException {
        WorldSource s = new WorldSource();
        s.profile = prof; s.fs = fs; s.remotePath = path;
        s.cacheDir = cacheRoot().resolve(prof.cacheKey()).resolve(path.replaceAll("^/+", "").replaceAll("[^A-Za-z0-9._/-]", "_"));
        t.set("Получаю список файлов…", -1);
        Map<String, long[]> remote = listRemoteWorld(fs, path);
        if (!remote.containsKey("level.dat")) throw new IOException("В папке нет level.dat — это не мир");
        Map<String, long[]> local = readSnapshot(s.cacheDir);
        long total = 0;
        List<String> need = new ArrayList<>();
        for (var e : remote.entrySet()) {
            long[] l = local.get(e.getKey());
            Path f = s.cacheDir.resolve(e.getKey());
            boolean same = l != null && l[0] == e.getValue()[0] && l[1] == e.getValue()[1] && Files.exists(f) && Files.size(f) == e.getValue()[0];
            if (!same) { need.add(e.getKey()); total += Math.max(0, e.getValue()[0]); }
        }
        // удалённые на сервере регионы убираем из кэша
        Path rd = s.cacheDir.resolve("region");
        if (Files.isDirectory(rd)) try (var st = Files.list(rd)) {
            for (Path p : st.toList()) if (!remote.containsKey("region/" + p.getFileName())) Files.deleteIfExists(p);
        }
        t.bytesTotal = total;
        long[] doneBase = {0};
        int i = 0;
        for (String f : need) {
            t.check();
            i++;
            t.status = "Скачиваю " + f + " (" + i + "/" + need.size() + ")";
            long base = doneBase[0];
            fs.download(RemoteFs.join(path, f), s.cacheDir.resolve(f), new RemoteFs.Progress() {
                public void bytes(long d, long tot) { t.bytesDone = base + d; t.progress = t.bytesTotal > 0 ? (double) t.bytesDone / t.bytesTotal : -1; t.sub = Task.bytes(t.bytesDone) + " из " + Task.bytes(t.bytesTotal); }
                public boolean cancelled() { return t.cancelled; }
            });
            doneBase[0] += Math.max(0, remote.get(f)[0]);
        }
        s.snapshot = remote;
        writeSnapshot(s.cacheDir, remote);
        return s;
    }

    public static Map<String, long[]> readSnapshot(Path dir) {
        Path f = dir.resolve(".wbsnapshot.json");
        Map<String, long[]> m = new LinkedHashMap<>();
        try {
            if (Files.exists(f)) {
                JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                for (var e : o.entrySet()) { JsonArray a = e.getValue().getAsJsonArray(); m.put(e.getKey(), new long[]{a.get(0).getAsLong(), a.get(1).getAsLong()}); }
            }
        } catch (Exception ignored) {}
        return m;
    }

    static void writeSnapshot(Path dir, Map<String, long[]> m) throws IOException {
        JsonObject o = new JsonObject();
        for (var e : m.entrySet()) { JsonArray a = new JsonArray(); a.add(e.getValue()[0]); a.add(e.getValue()[1]); o.add(e.getKey(), a); }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".wbsnapshot.json"), o.toString());
    }

    /** Совпадает ли кэш с сервером (по размерам и датам). */
    public static boolean cacheMatches(ServerProfile prof, String path, Map<String, long[]> remote) {
        Path dir = cacheRoot().resolve(prof.cacheKey()).resolve(path.replaceAll("^/+", "").replaceAll("[^A-Za-z0-9._/-]", "_"));
        Map<String, long[]> l = readSnapshot(dir);
        if (l.isEmpty()) return false;
        if (l.size() != remote.size()) return false;
        for (var e : remote.entrySet()) { long[] a = l.get(e.getKey()); if (a == null || a[0] != e.getValue()[0] || a[1] != e.getValue()[1]) return false; }
        return true;
    }

    /** Открыть мир с диска: папку или ZIP (ZIP распаковывается во временную рабочую папку). */
    public static WorldSource offline(Path path) throws IOException {
        WorldSource s = new WorldSource();
        s.offlinePath = path;
        if (Files.isDirectory(path)) {
            s.cacheDir = cacheRoot().resolve("_local").resolve(Integer.toHexString(path.toAbsolutePath().toString().hashCode()) + "_" + path.getFileName());
            copyWorldFiles(path, s.cacheDir);
        } else {
            s.cacheDir = cacheRoot().resolve("_zip").resolve(path.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_") + "_" + System.currentTimeMillis());
            unzipWorld(path, s.cacheDir);
        }
        return s;
    }

    static void copyWorldFiles(Path src, Path dst) throws IOException {
        if (Files.exists(dst)) deleteTree(dst);
        Files.createDirectories(dst.resolve("region"));
        Files.copy(src.resolve("level.dat"), dst.resolve("level.dat"));
        Path rd = src.resolve("region");
        if (Files.isDirectory(rd)) try (var st = Files.list(rd)) {
            for (Path p : st.toList()) Files.copy(p, dst.resolve("region").resolve(p.getFileName()));
        }
    }

    public static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var w = Files.walk(p)) { for (Path f : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f); }
    }

    /** Найти в ZIP папку мира (где лежит level.dat) и распаковать её содержимое. */
    static void unzipWorld(Path zip, Path dst) throws IOException {
        String prefix = null;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName().replace('\\', '/');
                if (n.endsWith("level.dat") && (n.equals("level.dat") || n.endsWith("/level.dat"))) {
                    String p = n.substring(0, n.length() - "level.dat".length());
                    if (prefix == null || p.length() < prefix.length()) prefix = p;
                }
            }
            if (prefix == null) throw new IOException("В архиве нет мира (не найден level.dat)");
            Files.createDirectories(dst.resolve("region"));
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName().replace('\\', '/');
                if (e.isDirectory() || !n.startsWith(prefix)) continue;
                String rel = n.substring(prefix.length());
                if (!(rel.equals("level.dat") || (rel.startsWith("region/") && rel.endsWith(".mcr") && rel.indexOf('/', 7) < 0))) continue;
                Path out = dst.resolve(rel).normalize();
                if (!out.startsWith(dst)) continue;
                try (InputStream in = z.getInputStream(e)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    /** Записать ZIP: внутри — папка мира с файлами из рабочей копии (и заменами из staging). */
    public static void writeZip(Path zip, String worldName, Path baseDir, Path overrideDir, List<String> overrides, List<String> allFiles) throws IOException {
        Path tmp = zip.resolveSibling(zip.getFileName() + ".wbtmp");
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            Set<String> files = new LinkedHashSet<>(allFiles);
            files.addAll(overrides);
            out.putNextEntry(new ZipEntry(worldName + "/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(worldName + "/region/"));
            out.closeEntry();
            for (String f : files) {
                Path src = overrides.contains(f) ? overrideDir.resolve(f) : baseDir.resolve(f);
                if (!Files.exists(src)) continue;
                out.putNextEntry(new ZipEntry(worldName + "/" + f));
                Files.copy(src, out);
                out.closeEntry();
            }
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void writeSnapshotPublic(Path dir, Map<String, long[]> m) throws IOException { writeSnapshot(dir, m); }
    public static String readText(Path p) throws IOException { return Files.readString(p, StandardCharsets.UTF_8); }
}
