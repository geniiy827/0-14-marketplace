package com.geniiy.worldbuilder.session;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.edit.Biomes;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.pe.*;
import com.geniiy.worldbuilder.util.Pos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Шаблоны новых миров (раздел 10) и «тестовый мир всех блоков» (раздел 30.3). */
public final class WorldTemplates {
    private WorldTemplates() {}

    public enum Kind { EMPTY, PLATFORM, FLAT }

    public static class Params {
        public String folder = "newworld", levelName = "newworld";
        public Kind kind = Kind.FLAT;
        public int platformSize = 16, platformY = 64, platformBlock = 2 << 4;
        public final List<int[]> layers = new ArrayList<>(); // снизу вверх: {state, count}
        public int biome = 1;
        public int radius = 8;
        public boolean autoSpawn = true;
        public int spawnX = 128, spawnY = 70, spawnZ = 128;
        public int gameType = 1;
        public long time = 0;
        public long seed = new Random().nextLong();
        public Params() { classic(); }
        public void classic() { layers.clear(); layers.add(new int[]{7 << 4, 1}); layers.add(new int[]{3 << 4, 2}); layers.add(new int[]{2 << 4, 1}); }
        public int height() { int h = 0; for (int[] l : layers) h += l[1]; return h; }
    }

    /** Строка пресета Genisys: «2;слои;биом;» (элемент — [Nx]id[:meta]). */
    public static String preset(Params p) {
        StringBuilder sb = new StringBuilder("2;");
        for (int i = 0; i < p.layers.size(); i++) {
            int[] l = p.layers.get(i);
            if (i > 0) sb.append(',');
            if (l[1] > 1) sb.append(l[1]).append('x');
            sb.append(l[0] >> 4);
            if ((l[0] & 15) != 0) sb.append(':').append(l[0] & 15);
        }
        sb.append(';').append(p.biome).append(';');
        return sb.toString();
    }

    public static PeWorld create(Path dir, Params p) throws IOException {
        int biomeVal = Biomes.value(p.biome);
        String gen, opts;
        int sx = p.spawnX, sy = p.spawnY, sz = p.spawnZ;
        int cx0 = 8, cz0 = 8; // спавн по умолчанию — в центре региона 0,0 (128,128)
        if (p.autoSpawn) { sx = 128; sz = 128; }
        switch (p.kind) {
            case FLAT -> { gen = "flat"; opts = preset(p); if (p.autoSpawn) sy = Math.min(127, p.height() + 1); }
            case PLATFORM -> { gen = "flat"; opts = "2;0;" + p.biome + ";"; if (p.autoSpawn) sy = Math.min(127, p.platformY + 1); }
            default -> { gen = "flat"; opts = "2;0;" + p.biome + ";"; if (p.autoSpawn) sy = 64; }
        }
        LevelDat l = LevelDat.create(p.levelName, gen, opts, p.seed, sx, sy, sz, p.gameType, p.time);
        PeWorld w = PeWorld.create(dir, l, biomeVal);
        int scx = sx >> 4, scz = sz >> 4;
        int r = p.radius;
        for (int cx = scx - r; cx <= scx + r; cx++) for (int cz = scz - r; cz <= scz + r; cz++) {
            PeChunk c = w.getOrCreateChunk(cx, cz);
            if (p.kind == Kind.FLAT) {
                int y = 0;
                for (int[] layer : p.layers) for (int k = 0; k < layer[1] && y < 128; k++, y++)
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) c.setState(x, y, z, layer[0]);
            }
            c.markDirty();
        }
        if (p.kind == Kind.PLATFORM) {
            int n = Math.max(1, p.platformSize), x0 = sx - n / 2, z0 = sz - n / 2;
            for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) w.set(x0 + x, p.platformY, z0 + z, p.platformBlock, null);
        }
        if (p.kind == Kind.EMPTY && p.radius == 0) w.getOrCreateChunk(scx, scz).markDirty();
        LightEngine.relight(w, w.dirtyChunks(), null);
        for (PeChunk c : w.dirtyChunks()) c.recalcHeightMap();
        return w;
    }

    /**
     * Тестовый мир всех блоков: каждый id:meta таблицы на каменном постаменте, рядом табличка «id:meta»,
     * ряды по id. Направленным блокам ставится опора-стенка.
     */
    public static PeWorld testWorld(Path dir) throws IOException {
        Params p = new Params();
        p.kind = Kind.FLAT;
        p.layers.clear();
        p.layers.add(new int[]{7 << 4, 1});
        p.layers.add(new int[]{1 << 4, 59});
        p.layers.add(new int[]{2 << 4, 1});
        p.levelName = "wb_test_all_blocks";
        p.radius = 0;
        p.gameType = 1;
        LevelDat l = LevelDat.create(p.levelName, "flat", preset(p), 1, 0, 64, 0, 1, 6000);
        PeWorld w = PeWorld.create(dir, l, Biomes.value(1));
        PeBlocks t = PeBlocks.get();
        int row = 0;
        int baseY = 61; // верх земли — 60
        // земля под всей сеткой
        int maxMetas = 16;
        int rows = t.all.size();
        for (int x = -2; x < 2 + maxMetas * 4; x++) for (int z = -2; z < 2 + rows * 5; z++) {
            for (int y = 0; y <= 60; y++) w.set(x, y, z, y == 0 ? 7 << 4 : y == 60 ? 2 << 4 : 1 << 4, null);
        }
        for (PeBlocks.BlockInfo b : t.all) {
            if (b.id == 0) continue;
            int z = 2 + row * 5;
            int col = 0;
            for (PeBlocks.MetaInfo m : b.metaList) {
                int x = 2 + col * 4;
                col++;
                int st = (b.id << 4) | m.meta;
                w.set(x, baseY - 1, z, 1 << 4, null); // постамент
                placeTest(w, b, st, x, baseY, z);
                // табличка с подписью
                w.set(x + 2, baseY, z, (63 << 4) | 8, null);
                Tag.CompoundTag sign = w.getTile(x + 2, baseY, z);
                if (sign != null) {
                    sign.putString("Text1", b.id + ":" + m.meta);
                    sign.putString("Text2", trim(b.nameEn));
                    sign.putString("Text3", trim(m.nameRu != null ? m.nameRu : b.nameRu));
                    sign.putString("Text4", b.status == null ? "" : b.status);
                }
            }
            row++;
        }
        w.level.setSpawn(8, 63, -4);
        LightEngine.relight(w, w.dirtyChunks(), null);
        for (PeChunk c : w.dirtyChunks()) c.recalcHeightMap();
        return w;
    }

    static String trim(String s) { if (s == null) return ""; return s.length() > 15 ? s.substring(0, 15) : s; }

    static void placeTest(PeWorld w, PeBlocks.BlockInfo b, int st, int x, int y, int z) {
        int meta = st & 15;
        switch (b.family) {
            case "door" -> {
                if ((meta & 8) == 0) { w.set(x, y, z, st, null); w.set(x, y + 1, z, (b.id << 4) | 8, null); }
                else { w.set(x, y, z, b.id << 4, null); w.set(x, y + 1, z, st, null); }
            }
            case "double_plant" -> {
                if ((meta & 8) == 0) { w.set(x, y, z, st, null); w.set(x, y + 1, z, (b.id << 4) | 8 | (meta & 7), null); }
                else { w.set(x, y, z, (b.id << 4) | (meta & 7), null); w.set(x, y + 1, z, st, null); }
            }
            case "bed" -> {
                int dir = meta & 3; // 0S 1W 2N 3E
                int dx = dir == 1 ? -1 : dir == 3 ? 1 : 0, dz = dir == 0 ? 1 : dir == 2 ? -1 : 0;
                if ((meta & 8) == 0) { w.set(x, y, z, st, null); w.set(x + dx, y, z + dz, st | 8, null); }
                else { w.set(x - dx, y, z - dz, st & ~8, null); w.set(x, y, z, st, null); }
            }
            case "torch", "button", "lever", "facing4_face", "item_frame", "vine", "cocoa", "skull", "trapdoor" -> {
                // опора со всех четырёх сторон на уровне блока (стенка для настенных вариантов)
                int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] a : around) w.set(x + a[0], y, z + a[1], b.family.equals("cocoa") ? (17 << 4 | 3) : (1 << 4), null);
                w.set(x, y + 1, z, 1 << 4, null);
                w.set(x, y, z, st, null);
            }
            case "crop", "sapling" -> { w.set(x, y - 1, z, b.id == 115 ? 88 << 4 : 60 << 4, null); w.set(x, y, z, st, null); }
            default -> {
                if (b.id == 81 || b.id == 83 || b.id == 32 || b.id == 31) w.set(x, y - 1, z, 12 << 4, null);
                if (b.id == 83) { w.set(x + 1, y - 1, z, 9 << 4, null); }
                w.set(x, y, z, st, null);
            }
        }
    }

    static long noop() { return Pos.pack(0, 0, 0); }
}
