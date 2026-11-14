package com.geniiy.worldbuilder.pe;

import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.nbt.Tag.*;

import java.io.*;
import java.util.*;

/**
 * Чанк PocketMine McRegion 16×128×16. Хранит исходное дерево NBT целиком — массивы правятся на месте,
 * поэтому все неизвестные теги, их порядок и типы сохраняются.
 */
public final class PeChunk {
    public static final int HEIGHT = 128;

    public final int cx, cz;
    public String rootName = "";
    public CompoundTag root;
    public CompoundTag level;

    public byte[] blocks;     // 32768
    public byte[] data;       // 16384
    public byte[] skyLight;   // 16384
    public byte[] blockLight; // 16384
    public int[] biomeColors; // 256
    public int[] heightMap;   // 256
    public ListTag tileList;
    private final HashMap<Integer, CompoundTag> tiles = new HashMap<>();
    public final LinkedHashMap<Integer, Short> extraData = new LinkedHashMap<>();
    private boolean extraDirty;

    /** Чанк не удалось прочитать: его байты будут сохранены как есть, правки запрещены. */
    public boolean corrupt;
    public String corruptReason;
    /** Исходные сжатые данные (для побайтного копирования неизменённых чанков). */
    public RegionFile.RawChunk raw;
    /** Был изменён (нужно перепаковать при сохранении). */
    public volatile boolean dirty;
    /** Счётчик правок — для перестройки мешей. */
    public volatile int version;

    private PeChunk(int cx, int cz) { this.cx = cx; this.cz = cz; }

    // ---------- индексы ----------
    public static int bIndex(int x, int y, int z) { return (x << 11) | (z << 7) | y; }
    public static int nIndex(int x, int y, int z) { return (x << 10) | (z << 6) | (y >> 1); }
    public static int cIndex(int x, int z) { return (z << 4) | x; }

    public static int getNibble(byte[] arr, int x, int y, int z) {
        int b = arr[nIndex(x, y, z)];
        return (y & 1) == 0 ? b & 0x0F : (b >> 4) & 0x0F;
    }
    public static void setNibble(byte[] arr, int x, int y, int z, int v) {
        int i = nIndex(x, y, z);
        int b = arr[i];
        if ((y & 1) == 0) arr[i] = (byte) ((b & 0xF0) | (v & 0x0F));
        else arr[i] = (byte) ((b & 0x0F) | ((v & 0x0F) << 4));
    }

    // ---------- блоки ----------
    public int getId(int x, int y, int z) { return blocks[bIndex(x, y, z)] & 0xFF; }
    public int getMeta(int x, int y, int z) { return getNibble(data, x, y, z); }
    /** (id << 4) | meta */
    public int getState(int x, int y, int z) { return ((blocks[bIndex(x, y, z)] & 0xFF) << 4) | getNibble(data, x, y, z); }
    public void setState(int x, int y, int z, int state) {
        blocks[bIndex(x, y, z)] = (byte) (state >> 4);
        setNibble(data, x, y, z, state & 15);
    }
    public int getSky(int x, int y, int z) { return getNibble(skyLight, x, y, z); }
    public int getBlockLight(int x, int y, int z) { return getNibble(blockLight, x, y, z); }
    public int getBiomeColor(int x, int z) { return biomeColors[cIndex(x, z)]; }
    public int getBiome(int x, int z) { return (biomeColors[cIndex(x, z)] >>> 24) & 0xFF; }
    public void setBiomeColor(int x, int z, int v) { biomeColors[cIndex(x, z)] = v; }

    public boolean isEmptySection(int sy) {
        int y0 = sy * 16;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int base = (x << 11) | (z << 7) | y0;
            for (int y = 0; y < 16; y++) if (blocks[base + y] != 0) return false;
        }
        return true;
    }

    // ---------- тайлы ----------
    public static int tKey(int x, int y, int z) { return bIndex(x, y, z); }
    public CompoundTag getTile(int x, int y, int z) { return tiles.get(tKey(x, y, z)); }
    public Collection<CompoundTag> allTiles() { return tiles.values(); }
    public void setTile(int x, int y, int z, CompoundTag t) {
        CompoundTag old = tiles.remove(tKey(x, y, z));
        if (old != null) tileList.list.remove(old);
        if (t != null) {
            tiles.put(tKey(x, y, z), t);
            if (tileList.list.isEmpty()) tileList.elemType = Tag.COMPOUND;
            tileList.list.add(t);
        }
    }

    // ---------- ExtraData ----------
    public Short getExtra(int x, int y, int z) { return extraData.isEmpty() ? null : extraData.get(bIndex(x, y, z)); }
    public void setExtra(int x, int y, int z, Short v) {
        int k = bIndex(x, y, z);
        if (v == null) { if (extraData.remove(k) != null) extraDirty = true; }
        else { Short o = extraData.put(k, v); if (!v.equals(o)) extraDirty = true; }
    }

    // ---------- флаги ----------
    public void setFlag(String name, int v) { level.setNumberKeepType(name, v, Tag.BYTE); }
    public int getFlag(String name) { return (int) level.getNumber(name, 0); }

    // ---------- загрузка ----------
    public static PeChunk fromRaw(int cx, int cz, RegionFile.RawChunk raw) {
        PeChunk c = new PeChunk(cx, cz);
        c.raw = raw;
        try {
            byte[] bytes = raw.decompress();
            Tag.Named n = Tag.readRoot(new ByteArrayInputStream(bytes));
            c.rootName = n.name();
            c.root = n.tag();
            c.level = c.root.getCompound("Level");
            if (c.level == null) throw new IOException("нет Compound Level");
            c.bind();
        } catch (Exception e) {
            c.corrupt = true;
            c.corruptReason = e.toString();
            c.makeEmptyArrays();
        }
        return c;
    }

    private void makeEmptyArrays() {
        blocks = new byte[32768]; data = new byte[16384]; skyLight = new byte[16384]; blockLight = new byte[16384];
        biomeColors = new int[256]; heightMap = new int[256]; tileList = new ListTag(Tag.COMPOUND);
    }

    /** Связать поля с тегами дерева (создать недостающие массивы). */
    private void bind() throws IOException {
        blocks = arr(level, "Blocks", 32768);
        data = arr(level, "Data", 16384);
        skyLight = arr(level, "SkyLight", 16384);
        blockLight = arr(level, "BlockLight", 16384);
        biomeColors = iarr(level, "BiomeColors", 256);
        heightMap = iarr(level, "HeightMap", 256);
        tileList = level.getList("TileEntities");
        if (tileList == null) { tileList = new ListTag(Tag.COMPOUND); level.put("TileEntities", tileList); }
        for (Tag t : tileList) {
            if (t instanceof CompoundTag ct) {
                int x = ct.getInt("x", 0), y = ct.getInt("y", -1), z = ct.getInt("z", 0);
                if (y >= 0 && y < HEIGHT && (x >> 4) == cx && (z >> 4) == cz) tiles.put(tKey(x & 15, y, z & 15), ct);
            }
        }
        byte[] ex = level.getByteArray("ExtraData");
        if (ex != null && ex.length >= 4) {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(ex));
            int n = in.readInt();
            for (int i = 0; i < n && in.available() >= 6; i++) extraData.put(in.readInt(), in.readShort());
        }
    }

    private static byte[] arr(CompoundTag l, String k, int n) {
        byte[] a = l.getByteArray(k);
        if (a == null || a.length != n) { byte[] b = new byte[n]; if (a != null) System.arraycopy(a, 0, b, 0, Math.min(a.length, n)); l.put(k, new ByteArrayTag(b)); return b; }
        return a;
    }
    private static int[] iarr(CompoundTag l, String k, int n) {
        int[] a = l.getIntArray(k);
        if (a == null || a.length != n) { int[] b = new int[n]; if (a != null) System.arraycopy(a, 0, b, 0, Math.min(a.length, n)); l.put(k, new IntArrayTag(b)); return b; }
        return a;
    }

    /** Новый пустой чанк (раздел 3.4 ТЗ). */
    public static PeChunk createEmpty(int cx, int cz, int biomeColor) {
        PeChunk c = new PeChunk(cx, cz);
        c.root = new CompoundTag();
        CompoundTag l = new CompoundTag();
        l.putInt("xPos", cx);
        l.putInt("zPos", cz);
        l.putByte("TerrainGenerated", 1);
        l.putByte("TerrainPopulated", 1);
        l.putByte("LightPopulated", 1);
        l.putByte("V", 1);
        l.putLong("InhabitedTime", 0);
        l.put("Blocks", new ByteArrayTag(new byte[32768]));
        l.put("Data", new ByteArrayTag(new byte[16384]));
        byte[] sky = new byte[16384]; Arrays.fill(sky, (byte) 0xFF);
        l.put("SkyLight", new ByteArrayTag(sky));
        l.put("BlockLight", new ByteArrayTag(new byte[16384]));
        int[] bc = new int[256]; Arrays.fill(bc, biomeColor);
        l.put("BiomeColors", new IntArrayTag(bc));
        l.put("HeightMap", new IntArrayTag(new int[256]));
        l.put("Entities", new ListTag(Tag.COMPOUND));
        l.put("TileEntities", new ListTag(Tag.COMPOUND));
        l.put("TileTicks", new ListTag(Tag.COMPOUND));
        l.put("ExtraData", new ByteArrayTag(new byte[4]));
        c.root.put("Level", l);
        c.level = l;
        try { c.bind(); } catch (IOException e) { throw new UncheckedIOException(e); }
        c.dirty = true;
        return c;
    }

    /** Сериализовать в несжатый NBT (ExtraData перезаписывается только если менялся). */
    public byte[] toNbtBytes() {
        if (extraDirty) {
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                DataOutputStream o = new DataOutputStream(bo);
                o.writeInt(extraData.size());
                for (var e : extraData.entrySet()) { o.writeInt(e.getKey()); o.writeShort(e.getValue()); }
                level.put("ExtraData", new ByteArrayTag(bo.toByteArray()));
            } catch (IOException e) { throw new UncheckedIOException(e); }
            extraDirty = false;
        }
        return Tag.toBytes(rootName, root);
    }

    public ListTag entities() { return level == null ? null : level.getList("Entities"); }

    /** Пересчитать HeightMap: y самого верхнего не-воздушного блока, 0 если пусто. */
    public void recalcHeightMap() {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int h = 0;
            int base = (x << 11) | (z << 7);
            for (int y = HEIGHT - 1; y >= 0; y--) if (blocks[base | y] != 0) { h = y; break; }
            heightMap[cIndex(x, z)] = h;
        }
    }

    public void markDirty() { dirty = true; version++; }
}
