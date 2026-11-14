package com.geniiy.worldbuilder.pe;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Файл региона McRegion r.X.Z.mcr: 32×32 чанка, таблица позиций (4096 байт), таблица меток времени (4096 байт),
 * сектора по 4096 байт. Данные чанка: int длина (включая байт сжатия), byte сжатие (2 = zlib, 1 = gzip), данные.
 */
public final class RegionFile {
    public static final int SECTOR = 4096;
    public static final int MAX_CHUNK = 1 << 20;

    /** Сырые данные чанка в файле: тип сжатия и сжатые байты (без заголовка длины). */
    public static final class RawChunk {
        public final byte compression;
        public final byte[] data;
        public final int timestamp;
        public RawChunk(byte compression, byte[] data, int timestamp) { this.compression = compression; this.data = data; this.timestamp = timestamp; }

        public byte[] decompress() throws IOException {
            InputStream in = compression == 1
                    ? new GZIPInputStream(new ByteArrayInputStream(data))
                    : new InflaterInputStream(new ByteArrayInputStream(data));
            try (in) { return in.readAllBytes(); }
        }

        public static RawChunk compress(byte[] nbt, int timestamp) {
            Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(nbt.length / 4 + 64);
                try (DeflaterOutputStream out = new DeflaterOutputStream(bo, def)) { out.write(nbt); }
                return new RawChunk((byte) 2, bo.toByteArray(), timestamp);
            } catch (IOException e) { throw new UncheckedIOException(e); }
            finally { def.end(); }
        }
    }

    public final int rx, rz;
    /** Индекс (lx + lz*32) → сырые данные. Пустые места отсутствуют. */
    public final RawChunk[] chunks = new RawChunk[1024];
    /** Индексы, которые не удалось прочитать (повреждённые записи). */
    public final List<String> warnings = new ArrayList<>();

    public RegionFile(int rx, int rz) { this.rx = rx; this.rz = rz; }

    public static int index(int cx, int cz) { return (cx & 31) + (cz & 31) * 32; }

    public static String fileName(int rx, int rz) { return "r." + rx + "." + rz + ".mcr"; }

    /** Разобрать имя r.X.Z.mcr → {X, Z} или null. */
    public static int[] parseName(String name) {
        if (!name.startsWith("r.") || !name.endsWith(".mcr")) return null;
        String[] p = name.substring(2, name.length() - 4).split("\\.");
        if (p.length != 2) return null;
        try { return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])}; } catch (NumberFormatException e) { return null; }
    }

    public static RegionFile read(Path file, int rx, int rz) throws IOException {
        RegionFile r = new RegionFile(rx, rz);
        byte[] f = Files.readAllBytes(file);
        if (f.length < 2 * SECTOR) {
            if (f.length > 0) r.warnings.add("Файл короче заголовка (" + f.length + " байт)");
            return r;
        }
        for (int i = 0; i < 1024; i++) {
            int loc = readInt(f, i * 4);
            if (loc == 0) continue;
            int sector = loc >>> 8, count = loc & 0xFF;
            int ts = readInt(f, SECTOR + i * 4);
            long start = (long) sector * SECTOR;
            if (sector < 2 || start + 5 > f.length) { r.warnings.add("Чанк " + i + ": позиция за концом файла"); continue; }
            int len = readInt(f, (int) start);
            if (len <= 1 || len > MAX_CHUNK || start + 4 + len > f.length) { r.warnings.add("Чанк " + i + ": неверная длина " + len); continue; }
            byte comp = f[(int) start + 4];
            byte[] data = Arrays.copyOfRange(f, (int) start + 5, (int) start + 4 + len);
            r.chunks[i] = new RawChunk(comp, data, ts);
        }
        return r;
    }

    public int count() { int n = 0; for (RawChunk c : chunks) if (c != null) n++; return n; }

    /** Записать регион целиком, компактно, через временный файл и атомарное переименование. */
    public void write(Path file) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = new byte[2 * SECTOR];
        int sector = 2;
        for (int i = 0; i < 1024; i++) {
            RawChunk c = chunks[i];
            if (c == null) continue;
            int len = c.data.length + 1;
            int total = len + 4;
            int sectors = (total + SECTOR - 1) / SECTOR;
            if (sectors > 255) throw new IOException("Чанк " + i + " больше 1 МиБ после сжатия");
            writeInt(header, i * 4, (sector << 8) | sectors);
            writeInt(header, SECTOR + i * 4, c.timestamp);
            byte[] buf = new byte[sectors * SECTOR];
            writeInt(buf, 0, len);
            buf[4] = c.compression;
            System.arraycopy(c.data, 0, buf, 5, c.data.length);
            body.write(buf);
            sector += sectors;
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".wbtmp");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
            out.write(header);
            body.writeTo(out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static int readInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
    static void writeInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }
}
