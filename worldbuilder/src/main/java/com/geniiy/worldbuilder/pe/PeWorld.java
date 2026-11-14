package com.geniiy.worldbuilder.pe;

import com.geniiy.worldbuilder.edit.ChangeSet;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.nbt.Tag.CompoundTag;
import com.geniiy.worldbuilder.util.Pos;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Открытый мир PE: level.dat + регионы McRegion. Авторитетная модель — данные PE (id:meta, тайлы, биомы).
 * Блоки, ставящиеся через {@link #set}, дают «виртуальное» состояние: ≥0 — (id<<4|meta), &lt;0 — заглушка импорта.
 */
public final class PeWorld {
    public final Path dir;
    public LevelDat level;
    private final ConcurrentHashMap<Long, RegionFile> regions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PeChunk> chunks = new ConcurrentHashMap<>();
    /** Чанки, которые есть в файлах регионов. */
    private final Set<Long> existing = ConcurrentHashMap.newKeySet();
    /** Регионы, которые есть на диске. */
    private final Set<Long> regionFiles = ConcurrentHashMap.newKeySet();
    /** Заглушки после импорта: позиция → индекс в stubPalette. */
    public final ConcurrentHashMap<Long, Integer> stubs = new ConcurrentHashMap<>();
    public final List<String> stubPalette = Collections.synchronizedList(new ArrayList<>());
    public int defaultBiomeColor = 0x0192BC59;
    public volatile boolean levelDirty;
    public final List<String> warnings = new ArrayList<>();
    /** Слушатель изменения секций (cx, cz, sy) — для перестройки мешей. */
    public volatile SectionListener listener;
    public interface SectionListener { void sectionChanged(int cx, int sy, int cz); }

    private PeWorld(Path dir) { this.dir = dir; }

    // ---------- открытие ----------
    public static String detectProblem(Path dir) {
        if (!Files.isRegularFile(dir.resolve("level.dat"))) return "В папке нет level.dat";
        if (Files.isDirectory(dir.resolve("db"))) return "Формат мира не поддерживается: нужен McRegion (.mcr). В Genisys формат задаётся в pocketmine.yml → level-settings → default-format: mcregion";
        Path rd = dir.resolve("region");
        if (Files.isDirectory(rd)) {
            try (Stream<Path> s = Files.list(rd)) {
                List<String> names = s.map(p -> p.getFileName().toString()).toList();
                boolean mcr = names.stream().anyMatch(n -> n.endsWith(".mcr"));
                boolean mca = names.stream().anyMatch(n -> n.endsWith(".mca"));
                if (mca && !mcr) return "Формат мира не поддерживается: нужен McRegion (.mcr). В Genisys формат задаётся в pocketmine.yml → level-settings → default-format: mcregion";
            } catch (IOException e) { return e.toString(); }
        }
        return null;
    }

    public static PeWorld open(Path dir) throws IOException {
        String p = detectProblem(dir);
        if (p != null) throw new IOException(p);
        PeWorld w = new PeWorld(dir);
        w.level = LevelDat.read(dir.resolve("level.dat"));
        Path rd = dir.resolve("region");
        if (Files.isDirectory(rd)) {
            try (Stream<Path> s = Files.list(rd)) {
                for (Path f : s.toList()) {
                    int[] rc = RegionFile.parseName(f.getFileName().toString());
                    if (rc == null) continue;
                    w.regionFiles.add(Pos.chunkKey(rc[0], rc[1]));
                    w.scanHeader(f, rc[0], rc[1]);
                }
            }
        }
        if ("hell".equals(w.level.generatorName()) || "nether".equals(w.level.generatorName())) w.defaultBiomeColor = 0x08000000;
        return w;
    }

    /** Новый мир в пустой папке. */
    public static PeWorld create(Path dir, LevelDat level, int biomeColor) throws IOException {
        Files.createDirectories(dir.resolve("region"));
        PeWorld w = new PeWorld(dir);
        w.level = level;
        w.defaultBiomeColor = biomeColor;
        w.levelDirty = true;
        level.write(dir.resolve("level.dat"));
        return w;
    }

    private void scanHeader(Path f, int rx, int rz) {
        try (InputStream in = Files.newInputStream(f)) {
            byte[] h = in.readNBytes(4096);
            if (h.length < 4096) return;
            for (int i = 0; i < 1024; i++) {
                int loc = RegionFile.readInt(h, i * 4);
                if (loc != 0) existing.add(Pos.chunkKey(rx * 32 + (i & 31), rz * 32 + (i >> 5)));
            }
        } catch (IOException e) { warnings.add("Регион " + f.getFileName() + ": " + e); }
    }

    // ---------- чанки ----------
    public boolean chunkExists(int cx, int cz) { long k = Pos.chunkKey(cx, cz); return existing.contains(k) || chunks.containsKey(k); }
    public boolean chunkLoaded(int cx, int cz) { return chunks.containsKey(Pos.chunkKey(cx, cz)); }
    public Set<Long> existingChunks() { Set<Long> s = new HashSet<>(existing); s.addAll(chunks.keySet()); return s; }
    public Collection<PeChunk> loadedChunks() { return chunks.values(); }
    public int loadedCount() { return chunks.size(); }

    private RegionFile region(int rx, int rz) {
        long k = Pos.chunkKey(rx, rz);
        RegionFile r = regions.get(k);
        if (r != null) return r;
        synchronized (regions) {
            r = regions.get(k);
            if (r != null) return r;
            Path f = dir.resolve("region").resolve(RegionFile.fileName(rx, rz));
            try {
                r = Files.exists(f) ? RegionFile.read(f, rx, rz) : new RegionFile(rx, rz);
                for (String wn : r.warnings) warnings.add(f.getFileName() + ": " + wn);
            } catch (IOException e) {
                warnings.add("Не удалось прочитать " + f.getFileName() + ": " + e);
                r = new RegionFile(rx, rz);
            }
            regions.put(k, r);
            return r;
        }
    }

    /** Загрузить чанк, если он есть в файлах (null — нет). */
    public PeChunk getChunk(int cx, int cz) {
        long k = Pos.chunkKey(cx, cz);
        PeChunk c = chunks.get(k);
        if (c != null) return c;
        if (!existing.contains(k)) return null;
        RegionFile r = region(cx >> 5, cz >> 5);
        RegionFile.RawChunk raw = r.chunks[RegionFile.index(cx, cz)];
        if (raw == null) { existing.remove(k); return null; }
        synchronized (this) {
            c = chunks.get(k);
            if (c != null) return c;
            c = PeChunk.fromRaw(cx, cz, raw);
            if (c.corrupt) warnings.add("Чанк " + cx + "," + cz + " повреждён — не будет изменён: " + c.corruptReason);
            chunks.put(k, c);
        }
        return c;
    }

    /** Получить или создать чанк (для установки блоков в несгенерированном месте). */
    public PeChunk getOrCreateChunk(int cx, int cz) {
        PeChunk c = getChunk(cx, cz);
        if (c != null) return c;
        synchronized (this) {
            long k = Pos.chunkKey(cx, cz);
            c = chunks.get(k);
            if (c != null) return c;
            c = PeChunk.createEmpty(cx, cz, nearestBiomeColor(cx, cz));
            chunks.put(k, c);
        }
        return c;
    }

    private int nearestBiomeColor(int cx, int cz) {
        for (int r = 1; r <= 2; r++)
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                PeChunk n = chunks.get(Pos.chunkKey(cx + dx, cz + dz));
                if (n != null && !n.corrupt) return n.biomeColors[0];
            }
        return defaultBiomeColor;
    }

    /** Выгрузить неизменённые чанки дальше радиуса (бюджет памяти). */
    public void evictFar(int ccx, int ccz, int radius, int maxLoaded) {
        if (chunks.size() <= maxLoaded) return;
        for (var e : chunks.entrySet()) {
            PeChunk c = e.getValue();
            if (c.dirty) continue;
            if (Math.abs(c.cx - ccx) > radius || Math.abs(c.cz - ccz) > radius) chunks.remove(e.getKey());
        }
    }

    // ---------- блоки ----------
    public static boolean inHeight(int y) { return y >= 0 && y < PeChunk.HEIGHT; }

    /** Состояние PE (без заглушек). Нет чанка или за высотой — 0 (воздух). */
    public int getState(int x, int y, int z) {
        if (!inHeight(y)) return 0;
        PeChunk c = getChunk(x >> 4, z >> 4);
        return c == null ? 0 : c.getState(x & 15, y, z & 15);
    }

    /** Виртуальное состояние: заглушка (&lt;0) или состояние PE. */
    public int getV(int x, int y, int z) {
        if (!stubs.isEmpty()) {
            Integer s = stubs.get(Pos.pack(x, y, z));
            if (s != null) return -(1 + s);
        }
        return getState(x, y, z);
    }

    public CompoundTag getTile(int x, int y, int z) {
        if (!inHeight(y)) return null;
        PeChunk c = getChunk(x >> 4, z >> 4);
        return c == null ? null : c.getTile(x & 15, y, z & 15);
    }

    /**
     * Поставить блок. Тайл приводится в соответствие (создать минимальный / удалить / заменить другого типа).
     * @return true, если что-то изменилось
     */
    public boolean set(int x, int y, int z, int v, ChangeSet cs) {
        if (!inHeight(y)) return false;
        PeChunk c = getOrCreateChunk(x >> 4, z >> 4);
        if (c.corrupt) return false;
        int lx = x & 15, lz = z & 15;
        long p = Pos.pack(x, y, z);
        int old = c.getState(lx, y, lz);
        Integer oldStub = stubs.isEmpty() ? null : stubs.get(p);
        int oldV = oldStub != null ? -(1 + oldStub) : old;
        if (oldV == v) return false;
        if (v < 0) {
            stubs.put(p, -v - 1);
            if (old != 0) { c.setState(lx, y, lz, 0); }
        } else {
            if (oldStub != null) stubs.remove(p);
            c.setState(lx, y, lz, v);
        }
        if (cs != null) cs.recordBlock(p, oldV, v);
        int nid = v < 0 ? 0 : v >> 4;
        if ((old >> 4) != nid || v < 0) {
            CompoundTag ot = c.getTile(lx, y, lz);
            String need = TileUtil.tileFor(nid);
            if (need == null) {
                if (ot != null) { if (cs != null) cs.recordTile(p, ot, null); c.setTile(lx, y, lz, null); }
            } else if (ot == null || !need.equals(ot.getString("id", ""))) {
                CompoundTag nt = TileUtil.create(need, x, y, z);
                if (cs != null) cs.recordTile(p, ot, nt);
                c.setTile(lx, y, lz, nt);
            }
            if (c.getExtra(lx, y, lz) != null) c.setExtra(lx, y, lz, null);
        }
        touch(c, lx, y, lz);
        return true;
    }

    /** Заменить тайл в позиции (null — удалить). */
    public void setTile(int x, int y, int z, CompoundTag t, ChangeSet cs) {
        if (!inHeight(y)) return;
        PeChunk c = getOrCreateChunk(x >> 4, z >> 4);
        if (c.corrupt) return;
        CompoundTag ot = c.getTile(x & 15, y, z & 15);
        if (cs != null) cs.recordTile(Pos.pack(x, y, z), ot, t);
        c.setTile(x & 15, y, z & 15, t);
        touch(c, x & 15, y, z & 15);
    }

    public int getBiomeColor(int x, int z) {
        PeChunk c = getChunk(x >> 4, z >> 4);
        return c == null ? defaultBiomeColor : c.getBiomeColor(x & 15, z & 15);
    }

    public void setBiomeColor(int x, int z, int v, ChangeSet cs) {
        PeChunk c = getOrCreateChunk(x >> 4, z >> 4);
        if (c.corrupt) return;
        int o = c.getBiomeColor(x & 15, z & 15);
        if (o == v) return;
        if (cs != null) cs.recordBiome(Pos.colKey(x, z), o, v);
        c.setBiomeColor(x & 15, z & 15, v);
        c.markDirty();
        SectionListener l = listener;
        if (l != null) for (int sy = 0; sy < 8; sy++) l.sectionChanged(c.cx, sy, c.cz);
    }

    private void touch(PeChunk c, int lx, int y, int lz) {
        c.markDirty();
        SectionListener l = listener;
        if (l == null) return;
        int sy = y >> 4;
        l.sectionChanged(c.cx, sy, c.cz);
        if (lx == 0) l.sectionChanged(c.cx - 1, sy, c.cz);
        if (lx == 15) l.sectionChanged(c.cx + 1, sy, c.cz);
        if (lz == 0) l.sectionChanged(c.cx, sy, c.cz - 1);
        if (lz == 15) l.sectionChanged(c.cx, sy, c.cz + 1);
        if ((y & 15) == 0 && sy > 0) l.sectionChanged(c.cx, sy - 1, c.cz);
        if ((y & 15) == 15 && sy < 7) l.sectionChanged(c.cx, sy + 1, c.cz);
    }

    // ---------- отмена ----------
    public void undo(ChangeSet cs) {
        for (int i = cs.size() - 1; i >= 0; i--) {
            long p = cs.pos(i);
            setRaw(Pos.x(p), Pos.y(p), Pos.z(p), cs.before(i));
        }
        for (Long p : cs.tileTouched) setTile(Pos.x(p), Pos.y(p), Pos.z(p), copyOrNull(cs.tilesBefore.get(p)), null);
        for (var e : cs.biomes.entrySet()) setBiomeColor(Pos.ckX(e.getKey()), Pos.ckZ(e.getKey()), e.getValue()[0], null);
        for (Runnable[] r : cs.extra) r[0].run();
    }

    public void redo(ChangeSet cs) {
        for (int i = 0; i < cs.size(); i++) {
            long p = cs.pos(i);
            setRaw(Pos.x(p), Pos.y(p), Pos.z(p), cs.after(i));
        }
        for (Long p : cs.tileTouched) setTile(Pos.x(p), Pos.y(p), Pos.z(p), copyOrNull(cs.tilesAfter.get(p)), null);
        for (var e : cs.biomes.entrySet()) setBiomeColor(Pos.ckX(e.getKey()), Pos.ckZ(e.getKey()), e.getValue()[1], null);
        for (Runnable[] r : cs.extra) r[1].run();
    }

    private static CompoundTag copyOrNull(CompoundTag t) { return t == null ? null : t.copy(); }

    /** Поставить без автоматики тайлов (для отмены). */
    private void setRaw(int x, int y, int z, int v) {
        PeChunk c = getOrCreateChunk(x >> 4, z >> 4);
        if (c.corrupt) return;
        long p = Pos.pack(x, y, z);
        if (v < 0) { stubs.put(p, -v - 1); c.setState(x & 15, y, z & 15, 0); }
        else { stubs.remove(p); c.setState(x & 15, y, z & 15, v); }
        touch(c, x & 15, y, z & 15);
    }

    // ---------- заглушки ----------
    public int stubIndex(String javaState) {
        synchronized (stubPalette) {
            int i = stubPalette.indexOf(javaState);
            if (i >= 0) return i;
            stubPalette.add(javaState);
            return stubPalette.size() - 1;
        }
    }
    public String stubName(int v) { int i = -v - 1; return i >= 0 && i < stubPalette.size() ? stubPalette.get(i) : "?"; }

    // ---------- проверки перед сохранением ----------
    public static final class CheckReport {
        public int tilesRemoved, tilesCreated;
        public final List<int[]> brokenDoubles = new ArrayList<>();
    }

    /** Согласованность тайлов и поиск половинок дверей и высоких растений. */
    public CheckReport checkConsistency() {
        CheckReport r = new CheckReport();
        for (PeChunk c : chunks.values()) {
            if (!c.dirty || c.corrupt) continue;
            for (CompoundTag t : new ArrayList<>(c.allTiles())) {
                int x = t.getInt("x", 0), y = t.getInt("y", 0), z = t.getInt("z", 0);
                String need = TileUtil.tileFor(c.getId(x & 15, y, z & 15));
                if (need == null || !need.equals(t.getString("id", ""))) {
                    c.setTile(x & 15, y, z & 15, null); r.tilesRemoved++;
                }
            }
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < PeChunk.HEIGHT; y++) {
                int id = c.getId(x, y, z);
                if (id == 0) continue;
                String need = TileUtil.tileFor(id);
                if (need != null && c.getTile(x, y, z) == null) {
                    c.setTile(x, y, z, TileUtil.create(need, c.cx * 16 + x, y, c.cz * 16 + z)); r.tilesCreated++;
                }
                boolean door = id == 64 || id == 71 || (id >= 193 && id <= 197);
                if (door || id == 175) {
                    int m = c.getMeta(x, y, z);
                    int wx = c.cx * 16 + x, wz = c.cz * 16 + z;
                    int other = (m & 8) == 0 ? getState(wx, y + 1, wz) : getState(wx, y - 1, wz);
                    boolean ok = (other >> 4) == id && (((other & 8) != 0) != ((m & 8) != 0));
                    if (!ok && r.brokenDoubles.size() < 200) r.brokenDoubles.add(new int[]{wx, y, wz});
                }
            }
        }
        return r;
    }

    public boolean hasUnsavedChanges() {
        if (levelDirty) return true;
        for (PeChunk c : chunks.values()) if (c.dirty) return true;
        return false;
    }

    public List<PeChunk> dirtyChunks() {
        List<PeChunk> l = new ArrayList<>();
        for (PeChunk c : chunks.values()) if (c.dirty && !c.corrupt) l.add(c);
        return l;
    }

    // ---------- сохранение ----------
    public static final class SaveOptions {
        public boolean preventPopulation = true;
        public boolean computeLight = true;
    }

    public interface Progress { void update(String what, double fraction); boolean cancelled(); }

    /**
     * Записать изменённые регионы и level.dat в outDir (структура папки мира). Неизменённые чанки
     * копируются байт в байт. Возвращает список относительных путей записанных файлов.
     * Состояние «изменён» снимается только вызовом {@link #commitSaved}.
     */
    public List<String> writeChanged(Path outDir, SaveOptions opt, Progress pr) throws IOException {
        List<PeChunk> dirty = dirtyChunks();
        if (pr != null) pr.update("Подготовка чанков", 0);
        if (opt.computeLight) {
            LightEngine.relight(this, dirty, pr);
        }
        for (PeChunk c : dirty) {
            c.recalcHeightMap();
            c.setFlag("TerrainGenerated", 1);
            if (opt.preventPopulation) c.setFlag("TerrainPopulated", 1);
            c.setFlag("LightPopulated", opt.computeLight ? 1 : 0);
        }
        Map<Long, List<PeChunk>> byRegion = new HashMap<>();
        for (PeChunk c : dirty) byRegion.computeIfAbsent(Pos.chunkKey(c.cx >> 5, c.cz >> 5), k -> new ArrayList<>()).add(c);
        List<String> written = new ArrayList<>();
        Files.createDirectories(outDir.resolve("region"));
        int done = 0;
        int ts = (int) (System.currentTimeMillis() / 1000);
        for (var e : byRegion.entrySet()) {
            if (pr != null && pr.cancelled()) throw new IOException("Отменено");
            int rx = Pos.ckX(e.getKey()), rz = Pos.ckZ(e.getKey());
            RegionFile src = region(rx, rz);
            RegionFile out = new RegionFile(rx, rz);
            System.arraycopy(src.chunks, 0, out.chunks, 0, 1024);
            for (PeChunk c : e.getValue()) {
                out.chunks[RegionFile.index(c.cx, c.cz)] = RegionFile.RawChunk.compress(c.toNbtBytes(), ts);
            }
            String name = RegionFile.fileName(rx, rz);
            out.write(outDir.resolve("region").resolve(name));
            written.add("region/" + name);
            done++;
            if (pr != null) pr.update("Запись регионов " + done + "/" + byRegion.size(), 0.5 + 0.45 * done / byRegion.size());
        }
        level.touchLastPlayed();
        Path ld = outDir.resolve("level.dat");
        level.write(ld);
        written.add("level.dat");
        if (pr != null) pr.update("Готово", 1);
        return written;
    }

    /** После успешного сохранения: обновить рабочую копию и снять пометки «изменён». */
    public void commitSaved(Path writtenDir, List<String> files) throws IOException {
        if (!writtenDir.equals(dir)) {
            for (String f : files) {
                Path s = writtenDir.resolve(f), d = dir.resolve(f);
                Files.createDirectories(d.getParent());
                Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String f : files) {
            if (!f.startsWith("region/")) continue;
            int[] rc = RegionFile.parseName(f.substring(7));
            if (rc == null) continue;
            long k = Pos.chunkKey(rc[0], rc[1]);
            RegionFile r = RegionFile.read(dir.resolve(f), rc[0], rc[1]);
            regions.put(k, r);
            regionFiles.add(k);
            for (int i = 0; i < 1024; i++) {
                if (r.chunks[i] == null) continue;
                long ck = Pos.chunkKey(rc[0] * 32 + (i & 31), rc[1] * 32 + (i >> 5));
                existing.add(ck);
                PeChunk c = chunks.get(ck);
                if (c != null) { c.raw = r.chunks[i]; c.dirty = false; }
            }
        }
        levelDirty = false;
    }

    /** Все файлы мира в рабочей копии (относительные пути). */
    public List<String> allFiles() throws IOException {
        List<String> l = new ArrayList<>();
        l.add("level.dat");
        Path rd = dir.resolve("region");
        if (Files.isDirectory(rd)) try (Stream<Path> s = Files.list(rd)) {
            for (Path p : s.toList()) if (p.getFileName().toString().endsWith(".mcr")) l.add("region/" + p.getFileName());
        }
        return l;
    }

    /** Самый высокий непустой блок колонки (−1 — пусто). */
    public int topY(int x, int z) {
        PeChunk c = getChunk(x >> 4, z >> 4);
        if (c == null) return -1;
        for (int y = PeChunk.HEIGHT - 1; y >= 0; y--) if (c.getId(x & 15, y, z & 15) != 0) return y;
        return -1;
    }

    public int regionCount() { return regionFiles.size(); }

    /** Применить action ко всем чанкам (для статистики). */
    public void forEachLoaded(Consumer<PeChunk> a) { chunks.values().forEach(a); }

    public CompoundTag levelData() { return level.data(); }
    public static Tag.CompoundTag none() { return null; }
}
