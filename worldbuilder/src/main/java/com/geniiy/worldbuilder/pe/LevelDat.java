package com.geniiy.worldbuilder.pe;

import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.nbt.Tag.CompoundTag;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** level.dat PocketMine/Genisys: GZIP + NBT big-endian, корень → Data. Все теги и типы сохраняются. */
public class LevelDat {
    public String rootName = "";
    public CompoundTag root;

    public static LevelDat read(Path file) throws IOException {
        LevelDat l = new LevelDat();
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            Tag.Named n = Tag.readRoot(in);
            l.rootName = n.name();
            l.root = n.tag();
        }
        if (l.data() == null) throw new IOException("В level.dat нет Compound Data");
        return l;
    }

    public void write(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".wbtmp");
        try (OutputStream out = new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
            Tag.writeRoot(out, rootName, root);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public CompoundTag data() { return root.getCompound("Data"); }

    public String levelName() { return data().getString("LevelName", "world"); }
    public void setLevelName(String s) { data().putString("LevelName", s); }
    public String generatorName() { return data().getString("generatorName", "normal"); }
    public void setGeneratorName(String s) { data().putString("generatorName", s); }
    public String generatorOptions() { return data().getString("generatorOptions", ""); }
    public void setGeneratorOptions(String s) { data().putString("generatorOptions", s); }
    public int spawnX() { return data().getInt("SpawnX", 128); }
    public int spawnY() { return data().getInt("SpawnY", 70); }
    public int spawnZ() { return data().getInt("SpawnZ", 128); }
    public void setSpawn(int x, int y, int z) {
        data().setNumberKeepType("SpawnX", x, Tag.INT);
        data().setNumberKeepType("SpawnY", y, Tag.INT);
        data().setNumberKeepType("SpawnZ", z, Tag.INT);
    }
    public int gameType() { return data().getInt("GameType", 0); }
    public void setGameType(int g) { data().setNumberKeepType("GameType", g, Tag.INT); }
    public long time() { return data().getNumber("Time", 0); }
    public void setTime(long t) { data().setNumberKeepType("Time", t, Tag.LONG); }
    public long seed() { return data().getNumber("RandomSeed", 0); }
    public void setSeed(long s) { data().setNumberKeepType("RandomSeed", s, Tag.LONG); }
    public boolean hardcore() { return data().getNumber("hardcore", 0) != 0; }
    public void setHardcore(boolean b) { data().setNumberKeepType("hardcore", b ? 1 : 0, Tag.BYTE); }
    public int version() { return data().getInt("version", 0); }

    public void touchLastPlayed() { data().putLong("LastPlayed", System.currentTimeMillis()); }

    /** Новый level.dat так, как его создаёт Genisys (McRegion, version 19133). */
    public static LevelDat create(String levelName, String generator, String options, long seed, int sx, int sy, int sz, int gameType, long time) {
        LevelDat l = new LevelDat();
        l.root = new CompoundTag();
        CompoundTag d = new CompoundTag();
        d.putByte("hardcore", 0);
        d.putByte("initialized", 1);
        d.putInt("GameType", gameType);
        d.putInt("generatorVersion", 1);
        d.putInt("SpawnX", sx);
        d.putInt("SpawnY", sy);
        d.putInt("SpawnZ", sz);
        d.putInt("version", 19133);
        d.putInt("DayTime", (int) time);
        d.putLong("LastPlayed", System.currentTimeMillis());
        d.putLong("RandomSeed", seed);
        d.putLong("SizeOnDisk", 0);
        d.putLong("Time", time);
        d.putString("generatorName", generator);
        d.putString("generatorOptions", options);
        d.putString("LevelName", levelName);
        d.put("GameRules", new CompoundTag());
        l.root.put("Data", d);
        return l;
    }
}
