package com.geniiy.bridge14.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Таблицы перевода 0.14 → 1.20.1 (сгенерированы из данных самого Minecraft). */
public final class Tables {
    public static final int[] BLOCK = new int[4096];               // (peId<<4|meta) → java state
    private static final Map<Integer, Integer> ITEM = new HashMap<>();   // (peId<<4|meta) → java item
    private static final Map<Integer, Integer> ITEM_BACK = new HashMap<>(); // java item → (peId<<4|meta)
    private static final Map<Integer, Integer> ENTITY = new HashMap<>(); // pe type → java entity type
    public static byte[] CODEC;
    public static int PLAINS = 39;
    public static int ITEM_ENTITY_TYPE = 54, PLAYER_TYPE = 122;
    private static boolean loaded;

    public static synchronized void load() {
        if (loaded) return;
        try {
            try (DataInputStream in = new DataInputStream(res("blocks.bin"))) { for (int i = 0; i < 4096; i++) BLOCK[i] = in.readInt(); }
            try (DataInputStream in = new DataInputStream(res("items.bin"))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    int pid = in.readUnsignedShort(), m = in.readUnsignedByte(), j = in.readInt();
                    ITEM.put(pid << 4 | m, j);
                    ITEM_BACK.putIfAbsent(j, pid << 4 | m);
                }
            }
            try (DataInputStream in = new DataInputStream(res("entities.bin"))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) { int k = in.readInt(), v = in.readInt(); ENTITY.put(k, v); }
            }
            CODEC = readAll(res("codec.nbt"));
            String meta = new String(readAll(res("meta.txt")));
            for (String line : meta.split("\n")) if (line.startsWith("plains=")) PLAINS = Integer.parseInt(line.substring(7).trim());
            Integer it = ENTITY.get(64); if (it != null) ITEM_ENTITY_TYPE = it;
            Integer pl = ENTITY.get(63); if (pl != null) PLAYER_TYPE = pl;
            loaded = true;
        } catch (Exception e) {
            throw new RuntimeException("Не удалось загрузить таблицы: " + e, e);
        }
    }

    private static InputStream res(String n) {
        InputStream in = Tables.class.getResourceAsStream("/bridge14/" + n);
        if (in == null) throw new RuntimeException("нет ресурса " + n);
        return in;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }

    public static int block(int peId, int meta) { return (peId < 0 || peId > 255) ? 0 : BLOCK[peId << 4 | (meta & 15)]; }

    /** Java-предмет для предмета 0.14 (или -1). */
    public static int item(int peId, int meta) {
        Integer j = ITEM.get(peId << 4 | (meta & 15));
        if (j == null) j = ITEM.get(peId << 4);
        return j == null ? -1 : j;
    }

    /** Предмет 0.14 {id, meta} для Java-предмета (или null — такого в 0.14 нет). */
    public static int[] itemBack(int javaId) {
        Integer k = ITEM_BACK.get(javaId);
        return k == null ? null : new int[]{k >> 4, k & 15};
    }

    public static int entity(int peType) { Integer v = ENTITY.get(peType & 0xff); return v == null ? -1 : v; }
}
