package com.geniiy.bridge14.proxy;

import com.geniiy.bridge14.pe.Buf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongToIntFunction;

/**
 * NBT предметов 0.14 (имя с наковальни/плагина, описание, зачарования, номер карты).
 * Предмет у нас — int[]{id, count, meta, ref}; ref — номер сырых NBT (LE) в общем пуле (0 — нет).
 */
public final class ItemNbt {
    private static final List<byte[]> POOL = new ArrayList<>();
    private static final Map<ByteBuffer, Integer> INDEX = new HashMap<>();
    private static final Map<Integer, Info> INFO = new HashMap<>();
    static { POOL.add(null); }

    public static final class Info { public String name; public List<String> lore = new ArrayList<>(); public List<int[]> ench = new ArrayList<>(); public long map; public boolean hasMap; public Integer color; }

    private ItemNbt() {}

    public static synchronized int ref(byte[] le) {
        if (le == null || le.length == 0) return 0;
        ByteBuffer k = ByteBuffer.wrap(le);
        Integer r = INDEX.get(k);
        if (r != null) return r;
        if (POOL.size() > 50000) { POOL.subList(1, POOL.size()).clear(); INDEX.clear(); INFO.clear(); }
        POOL.add(le);
        INDEX.put(k, POOL.size() - 1);
        return POOL.size() - 1;
    }

    public static synchronized byte[] get(int ref) { return ref > 0 && ref < POOL.size() ? POOL.get(ref) : null; }

    public static int refOf(int[] it) { return it != null && it.length > 3 ? it[3] : 0; }

    @SuppressWarnings("unchecked")
    public static synchronized Info info(int ref) {
        if (ref <= 0) return null;
        Info i = INFO.get(ref);
        if (i != null) return i;
        i = new Info();
        try {
            Object o = Nbt.readLE(new Buf(get(ref)));
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                Object d = m.get("display");
                if (d instanceof Map) {
                    Object n = ((Map<String, Object>) d).get("Name");
                    if (n instanceof String) i.name = (String) n;
                    Object l = ((Map<String, Object>) d).get("Lore");
                    if (l instanceof List) for (Object x : (List<Object>) l) if (x instanceof String) i.lore.add((String) x);
                }
                Object e = m.get("ench");
                if (e instanceof List) for (Object x : (List<Object>) e) if (x instanceof Map) {
                    Map<String, Object> em = (Map<String, Object>) x;
                    i.ench.add(new int[]{Nbt.intOf(em.get("id"), -1), Nbt.intOf(em.get("lvl"), 1)});
                }
                Object cc = m.get("customColor");
                if (cc instanceof Number) i.color = ((Number) cc).intValue() & 0xFFFFFF;
                Object mu = m.get("map_uuid");
                if (mu instanceof String) { try { i.map = Long.parseLong((String) mu); i.hasMap = true; } catch (Exception ignored) {} }
                else if (mu instanceof Number) { i.map = ((Number) mu).longValue(); i.hasMap = true; }
            }
        } catch (Exception ignored) {}
        INFO.put(ref, i);
        return i;
    }

    private static final String[] ENCH = {"protection", "fire_protection", "feather_falling", "blast_protection", "projectile_protection",
            "thorns", "respiration", "depth_strider", "aqua_affinity", "sharpness", "smite", "bane_of_arthropods", "knockback",
            "fire_aspect", "looting", "efficiency", "silk_touch", "unbreaking", "fortune", "power", "punch", "flame", "infinity",
            "luck_of_the_sea", "lure"};

    /** NBT для Java-слота (с корнем) или один байт 0, если нечего писать. */
    public static byte[] javaNbt(int[] it, int damage, LongToIntFunction mapId) {
        Info in = info(refOf(it));
        boolean any = damage > 0 || (in != null && (in.name != null || !in.lore.isEmpty() || !in.ench.isEmpty() || in.hasMap || in.color != null));
        if (!any) return new byte[]{0};
        JBuf o = new JBuf();
        o.u8(10).i16(0);
        if (damage > 0) { tag(o, 3, "Damage"); o.i32(damage); }
        if (in != null) {
            if (in.name != null || !in.lore.isEmpty() || in.color != null) {
                tag(o, 10, "display");
                if (in.color != null) { tag(o, 3, "color"); o.i32(in.color); }
                if (in.name != null) { tag(o, 8, "Name"); str(o, "{\"text\":\"" + Session.esc(in.name) + "\",\"italic\":false}"); }
                if (!in.lore.isEmpty()) {
                    tag(o, 9, "Lore"); o.u8(8).i32(in.lore.size());
                    for (String l : in.lore) str(o, "{\"text\":\"" + Session.esc(l) + "\",\"italic\":false}");
                }
                o.u8(0);
            }
            if (!in.ench.isEmpty()) {
                List<int[]> ok = new ArrayList<>();
                for (int[] e : in.ench) if (e[0] >= 0 && e[0] < ENCH.length) ok.add(e);
                tag(o, 9, "Enchantments"); o.u8(ok.isEmpty() ? 0 : 10).i32(ok.size());
                for (int[] e : ok) { tag(o, 8, "id"); str(o, "minecraft:" + ENCH[e[0]]); tag(o, 2, "lvl"); o.i16(e[1]); o.u8(0); }
            }
            if (in.hasMap && mapId != null) { tag(o, 3, "map"); o.i32(mapId.applyAsInt(in.map)); }
        }
        o.u8(0);
        return o.bytes();
    }

    private static void tag(JBuf o, int type, String name) { o.u8(type); str(o, name); }
    private static void str(JBuf o, String s) { byte[] d = s.getBytes(StandardCharsets.UTF_8); o.i16(d.length).raw(d); }
}
