package com.nova.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Random;

/**
 * Creates a new MCPE 0.14 world on disk (games/com.mojang/minecraftWorlds/<dir>):
 *   level.dat      = int32 StorageVersion (4) + int32 length + little-endian NBT
 *   levelname.txt  = world name
 *   db/            = empty LevelDB folder, the game generates terrain on first load
 * Keys and types mirror LevelData::setTagData of 0.14.3.
 */
public final class WorldWriter {
    private WorldWriter() { }

    public static final int STORAGE_VERSION = 4;     // SharedConstants::CurrentStorageVersion
    public static final int NETWORK_VERSION = 70;    // SharedConstants::NetworkProtocolVersion
    public static final int GEN_OLD = 0, GEN_INFINITE = 1, GEN_FLAT = 2;

    public static final class Settings {
        public String name = "Новый мир";
        public long seed;                 // stored as unsigned 32-bit
        public int gameType = 0;          // 0 survival, 1 creative
        public int generator = GEN_INFINITE;
        public int startTime = 0;         // 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight
        public boolean dayCycle = true;   // false -> DayCycleStopTime = startTime
        public boolean spawnMobs = true;
        public int weather = 0;           // 0 clear, 1 rain, 2 thunder
        public int spawnX = 0, spawnZ = 0;
    }

    /** Seed like the game's text box: a number is used as is, text is hashed. */
    public static long seedFrom(String text, Random rnd) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return rnd.nextInt() & 0xffffffffL;
        try {
            return Long.parseLong(t) & 0xffffffffL;
        } catch (NumberFormatException e) {
            return t.hashCode() & 0xffffffffL;
        }
    }

    // ------------------------------------------------------------- NBT (little endian)
    static final class Nbt {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        private void u8(int v) { o.write(v & 0xff); }
        private void i16(int v) { u8(v); u8(v >> 8); }
        private void i32(int v) { u8(v); u8(v >> 8); u8(v >> 16); u8(v >> 24); }
        private void i64(long v) { i32((int) v); i32((int) (v >>> 32)); }
        private void name(int type, String n) {
            u8(type);
            byte[] b = n.getBytes(Charset.forName("UTF-8"));
            i16(b.length);
            o.write(b, 0, b.length);
        }
        Nbt begin() { name(10, ""); return this; }
        Nbt b(String n, boolean v) { name(1, n); u8(v ? 1 : 0); return this; }
        Nbt i(String n, int v) { name(3, n); i32(v); return this; }
        Nbt l(String n, long v) { name(4, n); i64(v); return this; }
        Nbt f(String n, float v) { name(5, n); i32(Float.floatToIntBits(v)); return this; }
        Nbt s(String n, String v) {
            name(8, n);
            byte[] b = v.getBytes(Charset.forName("UTF-8"));
            i16(b.length);
            o.write(b, 0, b.length);
            return this;
        }
        byte[] end() { u8(0); return o.toByteArray(); }
    }

    static byte[] levelDat(Settings st, long nowSeconds, Random rnd) {
        int spawnX = st.spawnX, spawnZ = st.spawnZ, spawnY = 64;
        if (st.generator == GEN_OLD) { spawnX = 128; spawnZ = 128; }
        if (st.generator == GEN_FLAT) spawnY = 4;
        float rain = 0f, lightning = 0f;
        int rainTime = 12000 + rnd.nextInt(156000), lightningTime = 12000 + rnd.nextInt(156000);
        if (st.weather >= 1) { rain = 1f; rainTime = 6000 + rnd.nextInt(6000); }
        if (st.weather == 2) { lightning = 1f; lightningTime = 6000 + rnd.nextInt(6000); }

        byte[] nbt = new Nbt().begin()
                .l("RandomSeed", st.seed & 0xffffffffL)
                .i("GameType", st.gameType)
                .i("SpawnX", spawnX)
                .i("SpawnY", spawnY)
                .i("SpawnZ", spawnZ)
                .l("Time", st.startTime)
                .l("LastPlayed", nowSeconds)
                .s("LevelName", st.name)
                .i("StorageVersion", STORAGE_VERSION)
                .i("NetworkVersion", NETWORK_VERSION)
                .i("Platform", 2)
                .b("spawnMobs", st.spawnMobs)
                .i("Generator", st.generator)
                .i("LimitedWorldOriginX", 128)
                .i("LimitedWorldOriginY", 64)
                .i("LimitedWorldOriginZ", 128)
                .i("DayCycleStopTime", st.dayCycle ? -1 : st.startTime)
                .l("worldStartCount", 0)
                .l("currentTick", 0)
                .f("rainLevel", rain)
                .i("rainTime", rainTime)
                .f("lightningLevel", lightning)
                .i("lightningTime", lightningTime)
                .b("hasBeenLoadedInCreative", st.gameType == 1)
                .b("eduLevel", false)
                .i("globalPermissions", 2)
                .end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] head = {STORAGE_VERSION, nbt.length};
        for (int v : head) { out.write(v & 0xff); out.write(v >> 8 & 0xff); out.write(v >> 16 & 0xff); out.write(v >> 24 & 0xff); }
        out.write(nbt, 0, nbt.length);
        return out.toByteArray();
    }

    /** Folder name: readable ASCII from the world name, made unique. */
    static String folderName(File worldsDir, String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_') sb.append(ch);
            else if (ch == ' ' && sb.length() > 0) sb.append('_');
        }
        String base = sb.length() >= 3 ? sb.toString() : "nova_world";
        if (base.length() > 32) base = base.substring(0, 32);
        String n = base;
        for (int i = 2; new File(worldsDir, n).exists(); i++) n = base + "_" + i;
        return n;
    }

    /** Creates the world and returns its folder. */
    public static File create(File worldsDir, Settings st) throws IOException {
        if (!worldsDir.exists() && !worldsDir.mkdirs())
            throw new IOException("не удалось создать папку " + worldsDir);
        File dir = new File(worldsDir, folderName(worldsDir, st.name));
        if (!dir.mkdirs()) throw new IOException("не удалось создать папку мира");
        new File(dir, "db").mkdirs();
        Random rnd = new Random();
        write(new File(dir, "level.dat"), levelDat(st, System.currentTimeMillis() / 1000L, rnd));
        write(new File(dir, "levelname.txt"), st.name.getBytes(Charset.forName("UTF-8")));
        return dir;
    }

    private static void write(File f, byte[] data) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
    }
}
