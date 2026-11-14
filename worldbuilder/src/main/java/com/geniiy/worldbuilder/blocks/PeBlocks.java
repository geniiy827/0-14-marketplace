package com.geniiy.worldbuilder.blocks;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Таблица блоков MCPE 0.14.3 из wb_blocks_pe0143_java1201.json — единственный источник правды о блоках.
 * Этот класс не зависит от классов Minecraft (используется и в юнит-тестах).
 */
public final class PeBlocks {
    public static final String RESOURCE = "/assets/worldbuilder/wb_blocks_pe0143_java1201.json";

    public static final class MetaInfo {
        public int meta;
        public String nameRu, nameEn, java, combine, status, note;
        public boolean canonical = true;
    }

    public static final class BlockInfo {
        public int id;
        public String nameEn, nameRu, category, family, tileEntity, status, notes;
        public Integer metaMask;
        public int lightEmission, lightFilter;
        public boolean solid, transparent;
        public final MetaInfo[] metas = new MetaInfo[16];
        public final List<MetaInfo> metaList = new ArrayList<>();
        public boolean isAir() { return id == 0; }
    }

    /** Результат разрешения id:meta → запись таблицы. */
    public record Resolved(BlockInfo block, MetaInfo meta, boolean nonStandard) {}

    public final BlockInfo[] byId = new BlockInfo[256];
    public final List<BlockInfo> all = new ArrayList<>();
    public final int[] lightEmission = new int[256];
    public final int[] lightFilter = new int[256];

    /** java_to_pe: id Java-блока → список (свойства ключа, состояние PE). */
    public final Map<String, List<JavaKey>> javaToPe = new HashMap<>();
    public record JavaKey(Map<String, String> props, int state) {}

    private static PeBlocks instance;
    public static PeBlocks get() {
        if (instance == null) {
            try (InputStream in = PeBlocks.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IOException("Нет ресурса " + RESOURCE);
                instance = load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        return instance;
    }

    public static PeBlocks load(Reader r) {
        PeBlocks t = new PeBlocks();
        Arrays.fill(t.lightFilter, 15);
        JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
        for (JsonElement e : root.getAsJsonArray("blocks")) {
            JsonObject o = e.getAsJsonObject();
            BlockInfo b = new BlockInfo();
            b.id = o.get("id").getAsInt();
            b.nameEn = str(o, "name_en");
            b.nameRu = str(o, "name_ru");
            b.category = str(o, "category");
            b.family = str(o, "family");
            b.tileEntity = str(o, "tile_entity");
            b.status = str(o, "status");
            b.notes = str(o, "notes");
            b.metaMask = o.has("meta_mask") && !o.get("meta_mask").isJsonNull() ? o.get("meta_mask").getAsInt() : null;
            b.lightEmission = o.has("light_emission") ? o.get("light_emission").getAsInt() : 0;
            b.lightFilter = o.has("light_filter") ? o.get("light_filter").getAsInt() : 15;
            b.solid = o.has("genisys_solid") && o.get("genisys_solid").getAsBoolean();
            b.transparent = o.has("genisys_transparent") && o.get("genisys_transparent").getAsBoolean();
            for (JsonElement me : o.getAsJsonArray("metas")) {
                JsonObject mo = me.getAsJsonObject();
                MetaInfo m = new MetaInfo();
                m.meta = mo.get("meta").getAsInt();
                m.nameRu = str(mo, "name_ru");
                m.nameEn = str(mo, "name_en");
                m.java = str(mo, "java");
                m.combine = str(mo, "combine");
                m.status = str(mo, "status");
                m.note = str(mo, "note");
                if (mo.has("canonical")) m.canonical = mo.get("canonical").getAsBoolean();
                if (m.meta >= 0 && m.meta < 16) b.metas[m.meta] = m;
                b.metaList.add(m);
            }
            if (b.id >= 0 && b.id < 256) {
                t.byId[b.id] = b;
                t.all.add(b);
                t.lightEmission[b.id] = b.lightEmission;
                t.lightFilter[b.id] = b.lightFilter;
            }
        }
        t.lightFilter[0] = 0;
        if (root.has("java_to_pe")) {
            for (var en : root.getAsJsonObject("java_to_pe").entrySet()) {
                String key = en.getKey();
                String[] v = en.getValue().getAsString().split(":");
                int st = (Integer.parseInt(v[0]) << 4) | (v.length > 1 ? Integer.parseInt(v[1]) : 0);
                ParsedState ps = parseStateString(key);
                t.javaToPe.computeIfAbsent(ps.id, k -> new ArrayList<>()).add(new JavaKey(ps.props, st));
            }
        }
        return t;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    public record ParsedState(String id, LinkedHashMap<String, String> props) {}

    /** "minecraft:oak_stairs[facing=east,half=bottom]" → id + свойства. */
    public static ParsedState parseStateString(String s) {
        s = s.trim();
        LinkedHashMap<String, String> props = new LinkedHashMap<>();
        int br = s.indexOf('[');
        String id = br < 0 ? s : s.substring(0, br);
        if (br >= 0) {
            int end = s.lastIndexOf(']');
            String inner = s.substring(br + 1, end < 0 ? s.length() : end);
            for (String p : inner.split(",")) {
                int eq = p.indexOf('=');
                if (eq > 0) props.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
            }
        }
        if (!id.contains(":")) id = "minecraft:" + id;
        return new ParsedState(id, props);
    }

    public BlockInfo block(int id) { return id >= 0 && id < 256 ? byId[id] : null; }

    /** Разрешить id:meta по правилам 4.2 (маска, затем мета 0). null — id нет в таблице. */
    public Resolved resolve(int id, int meta) {
        BlockInfo b = block(id);
        if (b == null) return null;
        MetaInfo m = b.metas[meta & 15];
        if (m != null) return new Resolved(b, m, false);
        if (b.metaMask != null) {
            m = b.metas[meta & b.metaMask];
            if (m != null) return new Resolved(b, m, true);
        }
        m = b.metas[0];
        if (m == null && !b.metaList.isEmpty()) m = b.metaList.get(0);
        return new Resolved(b, m, true);
    }

    /** Подбор состояния PE по Java-состоянию (сравнение по подмножеству свойств ключа). -1 — нет. */
    public int javaToPe(String javaId, Map<String, String> props) {
        List<JavaKey> keys = javaToPe.get(javaId);
        if (keys == null) return -1;
        outer:
        for (JavaKey k : keys) {
            for (var e : k.props.entrySet()) {
                String v = props.get(e.getKey());
                if (v != null && !v.equals(e.getValue())) continue outer;
            }
            return k.state;
        }
        return -1;
    }

    public String displayName(int state) {
        int id = state >> 4, meta = state & 15;
        Resolved r = resolve(id, meta);
        if (r == null) return "Неизвестный блок #" + id;
        return r.meta != null && r.meta.nameRu != null ? r.meta.nameRu : r.block.nameRu;
    }

    public String shortName(int state) {
        Resolved r = resolve(state >> 4, state & 15);
        if (r == null) return "#" + (state >> 4);
        if (r.meta != null && r.meta.nameRu != null && !"variant".equals(r.block.family) && !"color".equals(r.block.family)
                && !"slab".equals(r.block.family) && !"slab_double".equals(r.block.family) && !"log".equals(r.block.family))
            return r.block.nameRu;
        return r.meta != null && r.meta.nameRu != null ? r.meta.nameRu : r.block.nameRu;
    }

    public boolean hasTile(int id) { BlockInfo b = block(id); return b != null && b.tileEntity != null; }
    public String tileId(int id) { BlockInfo b = block(id); return b == null ? null : b.tileEntity; }

    /** Мета задаёт вид блока (шерсть, доски…), а не ориентацию. */
    public static boolean metaIsVariant(BlockInfo b) {
        return switch (b.family) {
            case "variant", "color", "slab", "slab_double", "sapling", "log", "leaves", "fence", "wall", "double_plant" -> true;
            default -> false;
        };
    }

    /** Варианты для палитры: для «вариантных» — канонические меты, для направленных — одна мета. */
    public List<Integer> paletteStates(boolean allMetas) {
        List<Integer> out = new ArrayList<>();
        for (BlockInfo b : all) {
            if (b.id == 0) continue;
            if (allMetas) { for (MetaInfo m : b.metaList) out.add((b.id << 4) | m.meta); continue; }
            if (metaIsVariant(b)) {
                Set<String> seen = new HashSet<>();
                for (MetaInfo m : b.metaList) {
                    if (!m.canonical) continue;
                    String kind = variantKind(b, m);
                    if (seen.add(kind)) out.add((b.id << 4) | m.meta);
                }
                if (seen.isEmpty() && !b.metaList.isEmpty()) out.add((b.id << 4) | b.metaList.get(0).meta);
            } else {
                MetaInfo first = null;
                for (MetaInfo m : b.metaList) if (m.canonical) { first = m; break; }
                if (first == null && !b.metaList.isEmpty()) first = b.metaList.get(0);
                if (first != null) out.add((b.id << 4) | first.meta);
            }
        }
        return out;
    }

    /** Ключ «вида» блока для сворачивания вариантов (тип дерева без оси, плита без верх/низ и т. п.). */
    private static String variantKind(BlockInfo b, MetaInfo m) {
        return switch (b.family) {
            case "log" -> String.valueOf(m.meta & 3);
            case "leaves" -> String.valueOf(m.meta & 3);
            case "slab" -> String.valueOf(m.meta & 7);
            case "sapling" -> String.valueOf(m.meta & 7);
            case "double_plant" -> String.valueOf(m.meta & 7);
            default -> {
                if (b.id == 155) yield String.valueOf(m.meta >= 2 ? 2 : m.meta);
                if (b.id == 118) yield String.valueOf(m.meta == 0 ? 0 : 1);
                if (b.id == 151 || b.id == 178) yield "0";
                yield String.valueOf(m.meta);
            }
        };
    }

    /** Поиск по русскому/английскому имени, id, id:meta, Java-имени. Регистр и ё/е не важны. */
    public boolean matches(int state, String query) {
        String q = norm(query);
        if (q.isEmpty()) return true;
        int id = state >> 4, meta = state & 15;
        if (q.matches("\\d+")) return Integer.parseInt(q) == id;
        if (q.matches("\\d+:\\d+")) { String[] p = q.split(":"); return Integer.parseInt(p[0]) == id && Integer.parseInt(p[1]) == meta; }
        BlockInfo b = block(id);
        if (b == null) return false;
        MetaInfo m = b.metas[meta];
        return contains(b.nameRu, q) || contains(b.nameEn, q) || (m != null && (contains(m.nameRu, q) || contains(m.java, q) || contains(m.nameEn, q)));
    }
    private static boolean contains(String s, String q) { return s != null && norm(s).contains(q); }
    public static String norm(String s) { return s.toLowerCase(Locale.ROOT).replace('ё', 'е').trim(); }

    public static final String[] CATEGORIES = {"building", "wood", "nature", "plants", "colored", "decor", "redstone", "liquids", "special"};
}
