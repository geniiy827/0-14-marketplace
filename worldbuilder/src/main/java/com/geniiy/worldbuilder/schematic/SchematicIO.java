package com.geniiy.worldbuilder.schematic;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.edit.Clipboard;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.nbt.Tag.*;
import com.geniiy.worldbuilder.pe.TileUtil;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Импорт .schem (Sponge v2/v3), .schematic (MCEdit Alpha / Pocket) и экспорт .schematic «Pocket».
 * Всё, что не удалось сопоставить, становится заглушкой (отчёт обязателен — ничего не подменяется молча).
 */
public final class SchematicIO {
    private SchematicIO() {}

    public static final class Report {
        public Clipboard clip;
        public String format = "";
        public long imported;
        public final Map<String, Integer> analogs = new TreeMap<>();   // «что → во что» : количество
        public final Map<String, Integer> stubs = new TreeMap<>();     // Java-состояние : количество
        public final List<String> warnings = new ArrayList<>();
        public int tilesDropped;
    }

    public static CompoundTag readNbt(Path f) throws IOException {
        byte[] raw = Files.readAllBytes(f);
        InputStream in = (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) ? new GZIPInputStream(new ByteArrayInputStream(raw)) : new ByteArrayInputStream(raw);
        return Tag.readRoot(new BufferedInputStream(in)).tag();
    }

    public static Report read(Path f, boolean allowAnalogs) throws IOException {
        CompoundTag root = readNbt(f);
        if (root.has("Schematic") && root.get("Schematic") instanceof CompoundTag c) root = c;
        if (root.has("Materials") || (root.getByteArray("Blocks") != null && root.has("Width"))) return readLegacy(root);
        if (root.has("BlockData") || root.getCompound("Blocks") != null) return readSponge(root, allowAnalogs);
        throw new IOException("Неизвестный формат схематика");
    }

    // ---------- Sponge ----------
    static Report readSponge(CompoundTag root, boolean allowAnalogs) throws IOException {
        Report r = new Report();
        int ver = root.getInt("Version", 2);
        r.format = ".schem (Sponge v" + ver + ")";
        int w = root.getInt("Width", 0) & 0xFFFF, h = root.getInt("Height", 0) & 0xFFFF, l = root.getInt("Length", 0) & 0xFFFF;
        CompoundTag palette; byte[] data; ListTag bes;
        if (ver >= 3) {
            CompoundTag b = root.getCompound("Blocks");
            if (b == null) throw new IOException("Нет Blocks");
            palette = b.getCompound("Palette"); data = b.getByteArray("Data"); bes = b.getList("BlockEntities");
        } else {
            palette = root.getCompound("Palette"); data = root.getByteArray("BlockData"); bes = root.getList("BlockEntities");
            if (bes == null) bes = root.getList("TileEntities");
        }
        if (palette == null || data == null) throw new IOException("Повреждённый .schem: нет палитры или данных");
        if (h > 128) r.warnings.add("Высота схематика " + h + " больше 128 — лишнее обрежется при вставке за пределы мира");
        Clipboard c = new Clipboard(w, h, l);
        Map<Integer, Integer> pal = new HashMap<>();
        Map<Integer, String> palNames = new HashMap<>();
        Map<Integer, CompoundTag> palTiles = new HashMap<>();
        PeBlocks t = PeBlocks.get();
        for (var e : palette.map.entrySet()) {
            int idx = (int) ((CompoundTag) palette).getNumber(e.getKey(), 0);
            palNames.put(idx, e.getKey());
            Mapped m = mapJava(e.getKey(), allowAnalogs);
            if (m.state >= 0) { pal.put(idx, m.state); if (m.analog != null) r.analogs.merge(e.getKey() + " → " + t.displayName(m.state) + " (" + (m.state >> 4) + ":" + (m.state & 15) + ")", 0, Integer::sum); }
            else { int si = c.stubPalette.indexOf(e.getKey()); if (si < 0) { c.stubPalette.add(e.getKey()); si = c.stubPalette.size() - 1; } pal.put(idx, -(si + 1)); }
            if (m.tile != null) palTiles.put(idx, m.tile);
        }
        int[] counts = new int[palNames.size() + 64];
        int i = 0, p = 0, n = w * h * l;
        while (p < data.length && i < n) {
            int v = 0, shift = 0, b;
            do { b = data[p++]; v |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0 && p < data.length);
            int x = i % w, z = (i / w) % l, y = i / (w * l);
            Integer st = pal.get(v);
            int sv = st == null ? 0 : st;
            c.set(x, y, z, sv);
            if (v < counts.length) counts[v]++;
            CompoundTag pt = palTiles.get(v);
            if (pt != null && sv > 0) c.tiles.put(c.idx(x, y, z), TileUtil.moved(pt, x, y, z));
            i++;
        }
        for (var e : pal.entrySet()) {
            int cnt = e.getKey() < counts.length ? counts[e.getKey()] : 0;
            String nm = palNames.get(e.getKey());
            if (e.getValue() < 0) r.stubs.merge(nm, cnt, Integer::sum);
            else if (e.getValue() != 0) r.imported += cnt;
        }
        for (String k : new ArrayList<>(r.analogs.keySet())) {
            String jn = k.substring(0, k.indexOf(" → "));
            int total = 0;
            for (var e : palNames.entrySet()) if (e.getValue().equals(jn) && e.getKey() < counts.length) total += counts[e.getKey()];
            r.analogs.put(k, total);
        }
        // тайлы
        if (bes != null) for (Tag te : bes) {
            if (!(te instanceof CompoundTag ct)) continue;
            int[] pos = ct.getIntArray("Pos");
            if (pos == null || pos.length < 3) continue;
            if (pos[0] < 0 || pos[1] < 0 || pos[2] < 0 || pos[0] >= w || pos[1] >= h || pos[2] >= l) continue;
            int idx = c.idx(pos[0], pos[1], pos[2]);
            int sv = c.data[idx];
            if (sv <= 0) continue;
            String id = ct.getString("Id", ct.getString("id", ""));
            CompoundTag dataT = ct.getCompound("Data") != null ? ct.getCompound("Data") : ct;
            CompoundTag conv = convertJavaTile(id, dataT, sv, pos[0], pos[1], pos[2], r);
            if (conv != null) c.tiles.put(idx, conv);
        }
        addMissingTiles(c);
        r.clip = c;
        return r;
    }

    record Mapped(int state, String analog, CompoundTag tile) {}

    /** Java-состояние → PE (java_to_pe по подмножеству свойств, затем особые случаи и аналоги). */
    public static Mapped mapJava(String stateStr, boolean allowAnalogs) {
        PeBlocks t = PeBlocks.get();
        PeBlocks.ParsedState ps = PeBlocks.parseStateString(stateStr);
        String id = ps.id(); Map<String, String> props = ps.props();
        int st = t.javaToPe(id, props);
        if (st >= 0) return new Mapped(st, null, null);
        String n = id.substring(id.indexOf(':') + 1);
        // горшки
        if (n.startsWith("potted_")) {
            int[] it = potItem(n);
            CompoundTag tile = TileUtil.create("FlowerPot", 0, 0, 0);
            tile.putShort("item", it[0]); tile.putInt("data", it[1]);
            return new Mapped(140 << 4, null, tile);
        }
        // головы
        if (n.endsWith("_skull") || n.endsWith("_head")) {
            String base = n.replace("_wall_skull", "").replace("_wall_head", "").replace("_skull", "").replace("_head", "");
            int type = switch (base) { case "wither_skeleton" -> 1; case "zombie" -> 2; case "player" -> 3; case "creeper" -> 4; default -> 0; };
            CompoundTag tile = TileUtil.create("Skull", 0, 0, 0);
            tile.putByte("SkullType", type);
            if (n.contains("_wall_")) {
                int m = switch (props.getOrDefault("facing", "north")) { case "south" -> 3; case "west" -> 4; case "east" -> 5; default -> 2; };
                return new Mapped(144 << 4 | m, null, tile);
            }
            tile.putByte("Rot", Integer.parseInt(props.getOrDefault("rotation", "0")) & 15);
            return new Mapped(144 << 4 | 1, null, tile);
        }
        if (!allowAnalogs) return new Mapped(-1, null, null);
        String a = analogId(n);
        if (a != null) {
            int s2 = t.javaToPe("minecraft:" + a, props);
            if (s2 < 0) s2 = t.javaToPe("minecraft:" + a, Map.of());
            if (s2 >= 0) return new Mapped(s2, a, null);
        }
        return new Mapped(-1, null, null);
    }

    static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    /** Правила «близкого аналога» для блоков, которых нет в 0.14. */
    static String analogId(String n) {
        for (String c : COLORS) {
            if (n.equals(c + "_concrete") || n.equals(c + "_concrete_powder")) return c + "_wool";
            if (n.equals(c + "_glazed_terracotta")) return c + "_terracotta";
            if (n.equals(c + "_stained_glass")) return "glass";
            if (n.equals(c + "_stained_glass_pane")) return "glass_pane";
            if (n.equals(c + "_bed")) return "red_bed";
            if (n.equals(c + "_banner") || n.equals(c + "_wall_banner") || n.equals(c + "_shulker_box")) return null;
        }
        String[] newWoods = {"mangrove", "cherry", "bamboo", "crimson", "warped"};
        for (String wd : newWoods) {
            if (n.startsWith(wd + "_") || n.startsWith("stripped_" + wd)) {
                String rest = n.replace("stripped_", "").substring(wd.length() + 1);
                return switch (rest) {
                    case "log", "stem", "block", "wood", "hyphae" -> rest.equals("wood") || rest.equals("hyphae") ? "oak_wood" : "oak_log";
                    case "planks", "mosaic" -> "oak_planks";
                    case "stairs", "mosaic_stairs" -> "oak_stairs";
                    case "slab", "mosaic_slab" -> "oak_slab";
                    case "fence" -> "oak_fence";
                    case "fence_gate" -> "oak_fence_gate";
                    case "door" -> "oak_door";
                    case "trapdoor" -> "oak_trapdoor";
                    case "leaves" -> "oak_leaves";
                    case "sign", "wall_sign" -> rest.equals("sign") ? "oak_sign" : "oak_wall_sign";
                    case "button" -> "oak_button";
                    case "pressure_plate" -> "oak_pressure_plate";
                    default -> null;
                };
            }
        }
        if (n.startsWith("stripped_")) return n.substring(9).replace("_wood", "_wood");
        if (n.endsWith("_stairs")) {
            if (n.contains("andesite") || n.contains("diorite") || n.contains("granite") || n.contains("deepslate") || n.contains("tuff") || n.contains("blackstone") || n.contains("mossy_stone")) return "stone_brick_stairs";
            if (n.contains("red_sandstone")) return "red_sandstone_stairs";
            if (n.contains("sandstone")) return "sandstone_stairs";
            if (n.contains("quartz")) return "quartz_stairs";
            if (n.contains("prismarine") || n.contains("end_stone") || n.contains("purpur") || n.contains("mud_brick")) return "stone_brick_stairs";
            if (n.contains("nether") ) return "nether_brick_stairs";
            if (n.contains("cobble") || n.contains("stone")) return "cobblestone_stairs";
            if (n.contains("copper")) return "brick_stairs";
            return "oak_stairs";
        }
        if (n.endsWith("_slab")) {
            if (n.contains("red_sandstone")) return "red_sandstone_slab";
            if (n.contains("sandstone")) return "sandstone_slab";
            if (n.contains("quartz")) return "quartz_slab";
            if (n.contains("nether")) return "nether_brick_slab";
            if (n.contains("brick") || n.contains("copper")) return "brick_slab";
            if (n.contains("cobble") || n.contains("deepslate") || n.contains("blackstone")) return "cobblestone_slab";
            if (n.contains("stone") || n.contains("andesite") || n.contains("diorite") || n.contains("granite") || n.contains("tuff") || n.contains("prismarine")) return "stone_brick_slab";
            return "oak_slab";
        }
        if (n.endsWith("_wall") && !n.endsWith("_wall_sign") && !n.endsWith("_wall_torch")) return n.contains("mossy") ? "mossy_cobblestone_wall" : "cobblestone_wall";
        if (n.endsWith("_fence") && !n.equals("nether_brick_fence")) return "oak_fence";
        if (n.endsWith("_door") && !n.equals("iron_door")) return "oak_door";
        if (n.endsWith("_trapdoor") && !n.equals("iron_trapdoor")) return "oak_trapdoor";
        if (n.endsWith("_button")) return n.contains("stone") || n.contains("blackstone") ? "stone_button" : "oak_button";
        if (n.endsWith("_pressure_plate")) return n.contains("stone") ? "stone_pressure_plate" : "oak_pressure_plate";
        if (n.contains("copper")) return "orange_terracotta";
        if (n.startsWith("deepslate") || n.startsWith("cobbled_deepslate") || n.startsWith("polished_deepslate")) return n.contains("brick") || n.contains("tile") ? "stone_bricks" : "cobblestone";
        if (n.contains("blackstone")) return "cobblestone";
        return switch (n) {
            case "tuff", "calcite", "smooth_stone", "dripstone_block" -> "stone";
            case "smooth_sandstone" -> "cut_sandstone";
            case "smooth_red_sandstone" -> "cut_red_sandstone";
            case "smooth_quartz", "quartz_bricks" -> "quartz_block";
            case "red_nether_bricks" -> "nether_bricks";
            case "packed_ice", "blue_ice", "frosted_ice" -> "ice";
            case "barrier" -> null;
            case "short_grass" -> "grass";
            case "moss_block", "moss_carpet" -> "green_wool";
            case "mud", "packed_mud", "rooted_dirt" -> "dirt";
            case "mud_bricks" -> "bricks";
            case "lantern", "soul_lantern", "sea_lantern", "shroomlight", "froglight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight" -> "glowstone";
            case "soul_torch" -> "torch";
            case "soul_wall_torch" -> "wall_torch";
            case "chain" -> "iron_bars";
            case "barrel", "smoker", "blast_furnace" -> "furnace";
            case "cartography_table", "fletching_table", "smithing_table", "loom" -> "crafting_table";
            case "basalt", "polished_basalt", "smooth_basalt" -> "stone";
            case "magma_block" -> "netherrack";
            case "bone_block" -> "quartz_pillar";
            case "terracotta" -> "terracotta";
            case "end_stone_bricks", "end_stone", "purpur_block", "purpur_pillar" -> "sandstone";
            case "prismarine", "prismarine_bricks", "dark_prismarine" -> "stone_bricks";
            default -> null;
        };
    }

    static int[] potItem(String n) {
        return switch (n) {
            case "potted_oak_sapling" -> new int[]{6, 0}; case "potted_spruce_sapling" -> new int[]{6, 1}; case "potted_birch_sapling" -> new int[]{6, 2};
            case "potted_jungle_sapling" -> new int[]{6, 3}; case "potted_acacia_sapling" -> new int[]{6, 4}; case "potted_dark_oak_sapling" -> new int[]{6, 5};
            case "potted_fern" -> new int[]{31, 2}; case "potted_dead_bush" -> new int[]{32, 0}; case "potted_dandelion" -> new int[]{37, 0};
            case "potted_poppy" -> new int[]{38, 0}; case "potted_blue_orchid" -> new int[]{38, 1}; case "potted_allium" -> new int[]{38, 2};
            case "potted_azure_bluet" -> new int[]{38, 3}; case "potted_red_tulip" -> new int[]{38, 4}; case "potted_orange_tulip" -> new int[]{38, 5};
            case "potted_white_tulip" -> new int[]{38, 6}; case "potted_pink_tulip" -> new int[]{38, 7}; case "potted_oxeye_daisy" -> new int[]{38, 8};
            case "potted_brown_mushroom" -> new int[]{39, 0}; case "potted_red_mushroom" -> new int[]{40, 0}; case "potted_cactus" -> new int[]{81, 0};
            default -> new int[]{0, 0};
        };
    }

    static String plainText(String json) {
        if (json == null) return "";
        String s = json.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) return s.substring(1, s.length() - 1).replace("\\\"", "\"");
        if (s.startsWith("{") || s.startsWith("[")) {
            StringBuilder sb = new StringBuilder();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(s);
            while (m.find()) sb.append(m.group(1).replace("\\\"", "\""));
            return sb.toString();
        }
        return s.equals("null") ? "" : s;
    }

    static CompoundTag convertJavaTile(String id, CompoundTag d, int pe, int x, int y, int z, Report r) {
        String n = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        n = n.toLowerCase(Locale.ROOT);
        int peId = pe >> 4;
        String need = TileUtil.tileFor(peId);
        if (need == null) { r.tilesDropped++; return null; }
        CompoundTag t = TileUtil.create(need, x, y, z);
        switch (need) {
            case "Sign" -> {
                CompoundTag front = d.getCompound("front_text");
                if (front != null && front.getList("messages") != null) {
                    ListTag msgs = front.getList("messages");
                    for (int i = 0; i < Math.min(4, msgs.size()); i++) t.putString("Text" + (i + 1), plainText(((StringTag) msgs.get(i)).v));
                } else for (int i = 1; i <= 4; i++) t.putString("Text" + i, plainText(d.getString("Text" + i, "")));
            }
            case "Chest", "Furnace", "Dispenser", "Dropper", "BrewingStand" -> {
                ListTag items = d.getList("Items");
                if (items != null && items.size() > 0) r.warnings.add("Содержимое контейнера " + id + " в " + x + "," + y + "," + z + " не перенесено (пустой тайл)");
            }
            case "Skull" -> {
                if (d.has("SkullType")) t.putByte("SkullType", d.getInt("SkullType", 0));
                if (d.has("Rot")) t.putByte("Rot", d.getInt("Rot", 0));
            }
            case "MobSpawner" -> t.putInt("EntityId", 0);
            default -> {}
        }
        return t;
    }

    static void addMissingTiles(Clipboard c) {
        for (int y = 0; y < c.h; y++) for (int z = 0; z < c.l; z++) for (int x = 0; x < c.w; x++) {
            int v = c.get(x, y, z);
            if (v <= 0) continue;
            String need = TileUtil.tileFor(v >> 4);
            int i = c.idx(x, y, z);
            if (need != null && !c.tiles.containsKey(i)) c.tiles.put(i, TileUtil.create(need, x, y, z));
        }
    }

    // ---------- MCEdit / WorldEdit legacy ----------
    static Report readLegacy(CompoundTag root) throws IOException {
        Report r = new Report();
        String mat = root.getString("Materials", "Alpha");
        boolean pocket = "Pocket".equalsIgnoreCase(mat);
        r.format = ".schematic (" + mat + ")";
        int w = root.getInt("Width", 0) & 0xFFFF, h = root.getInt("Height", 0) & 0xFFFF, l = root.getInt("Length", 0) & 0xFFFF;
        byte[] blocks = root.getByteArray("Blocks"), data = root.getByteArray("Data"), add = root.getByteArray("AddBlocks");
        if (blocks == null || data == null) throw new IOException("Повреждённый .schematic");
        if (h > 128) r.warnings.add("Высота схематика " + h + " больше 128 — лишнее обрежется при вставке за пределы мира");
        Clipboard c = new Clipboard(w, h, l);
        PeBlocks t = PeBlocks.get();
        for (int y = 0; y < h; y++) for (int z = 0; z < l; z++) for (int x = 0; x < w; x++) {
            int i = (y * l + z) * w + x;
            if (i >= blocks.length) continue;
            int id = blocks[i] & 0xFF;
            if (add != null && (i >> 1) < add.length) id |= ((i & 1) == 0 ? (add[i >> 1] & 0x0F) : ((add[i >> 1] >> 4) & 0x0F)) << 8;
            int meta = i < data.length ? data[i] & 15 : 0;
            int v;
            if (pocket) v = (id & 0xFF) << 4 | meta;
            else {
                int[] m = legacyToPe(id, meta);
                if (m[0] < 0) {
                    String name = "legacy:" + id + ":" + meta;
                    int si = c.stubPalette.indexOf(name); if (si < 0) { c.stubPalette.add(name); si = c.stubPalette.size() - 1; }
                    v = -(si + 1);
                    r.stubs.merge(name, 1, Integer::sum);
                } else {
                    v = m[0] << 4 | m[1];
                    if (m[2] == 1) r.analogs.merge(id + ":" + meta + " → " + t.displayName(v) + " (" + m[0] + ":" + m[1] + ")", 1, Integer::sum);
                }
            }
            if (v > 0 && t.block(v >> 4) == null) {
                String name = (pocket ? "pocket:" : "legacy:") + (v >> 4) + ":" + (v & 15);
                int si = c.stubPalette.indexOf(name); if (si < 0) { c.stubPalette.add(name); si = c.stubPalette.size() - 1; }
                r.stubs.merge(name, 1, Integer::sum);
                v = -(si + 1);
            }
            c.set(x, y, z, v);
            if (v > 0) r.imported++;
        }
        ListTag tes = root.getList("TileEntities");
        if (tes != null) for (Tag te : tes) {
            if (!(te instanceof CompoundTag ct)) continue;
            int x = ct.getInt("x", -1), y = ct.getInt("y", -1), z = ct.getInt("z", -1);
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l) continue;
            int idx = c.idx(x, y, z);
            int v = c.data[idx];
            if (v <= 0) continue;
            if (pocket) { c.tiles.put(idx, ct.copy()); continue; }
            String id = ct.getString("id", "");
            CompoundTag conv = convertLegacyTile(id, ct, v, x, y, z, r);
            if (conv != null) c.tiles.put(idx, conv);
        }
        addMissingTiles(c);
        r.clip = c;
        return r;
    }

    static CompoundTag convertLegacyTile(String id, CompoundTag d, int pe, int x, int y, int z, Report r) {
        String need = TileUtil.tileFor(pe >> 4);
        if (need == null) { r.tilesDropped++; return null; }
        CompoundTag t = TileUtil.create(need, x, y, z);
        switch (need) {
            case "Sign" -> { for (int i = 1; i <= 4; i++) t.putString("Text" + i, plainText(d.getString("Text" + i, ""))); }
            case "FlowerPot" -> {
                Tag it = d.get("Item");
                int item = 0;
                if (it instanceof StringTag s) item = switch (s.v.replace("minecraft:", "")) { case "sapling" -> 6; case "tallgrass" -> 31; case "deadbush" -> 32; case "yellow_flower" -> 37; case "red_flower" -> 38; case "brown_mushroom" -> 39; case "red_mushroom" -> 40; case "cactus" -> 81; default -> 0; };
                else if (it != null) item = (int) d.getNumber("Item", 0);
                t.putShort("item", item);
                t.putInt("data", d.getInt("Data", 0));
            }
            case "Skull" -> { t.putByte("SkullType", d.getInt("SkullType", 0)); t.putByte("Rot", d.getInt("Rot", 0)); }
            case "Chest", "Furnace", "Dispenser", "Dropper", "BrewingStand" -> {
                ListTag items = d.getList("Items");
                if (items != null && items.size() > 0) r.warnings.add("Содержимое " + id + " в " + x + "," + y + "," + z + " не перенесено (пустой тайл)");
            }
            case "MobSpawner" -> {
                String e = d.getString("EntityId", "");
                int eid = switch (e) { case "Chicken" -> 10; case "Cow" -> 11; case "Pig" -> 12; case "Sheep" -> 13; case "Wolf" -> 14; case "Villager" -> 15; case "Zombie" -> 32; case "Creeper" -> 33; case "Skeleton" -> 34; case "Spider" -> 35; case "PigZombie" -> 36; case "Slime" -> 37; case "Enderman" -> 38; case "Silverfish" -> 39; case "CaveSpider" -> 40; case "Ghast" -> 41; case "LavaSlime" -> 42; case "Blaze" -> 43; default -> 0; };
                t.putInt("EntityId", eid);
            }
            default -> {}
        }
        return t;
    }

    /** Приложение Б: Java legacy → PE 0.14. Возвращает {id, meta, analog(0/1)}; id −1 — заглушка. */
    public static int[] legacyToPe(int id, int m) {
        switch (id) {
            case 3: return m == 2 ? new int[]{243, 0, 0} : new int[]{3, m & 1, 0};
            case 29: case 33: case 34: case 36: case 84: case 119: case 122: case 130: case 137: case 138: case 168: case 169:
            case 176: case 177: case 198: case 199: case 200: case 201: case 202: case 203: case 204: case 205: case 206: case 209:
            case 210: case 211: case 217: case 218: case 255: case 213: case 214: case 216:
                return new int[]{-1, 0, 0};
            case 43: {
                if (m == 6) return new int[]{43, 7, 0};
                if (m == 7) return new int[]{43, 6, 0};
                if (m == 8) return new int[]{43, 0, 0};
                if (m == 9) return new int[]{24, 2, 0};
                if (m == 15) return new int[]{155, 0, 0};
                return new int[]{43, m, 0};
            }
            case 44: {
                int t = m & 7, top = m & 8;
                if (t == 6) t = 7; else if (t == 7) t = 6;
                return new int[]{44, t | top, 0};
            }
            case 77: case 143: {
                int f = m & 7, p = m & 8;
                int pe = switch (f) { case 0 -> 0; case 1 -> 5; case 2 -> 4; case 3 -> 3; case 4 -> 2; case 5 -> 1; default -> 1; };
                return new int[]{id, pe | p, 0};
            }
            case 95: return new int[]{20, 0, 1};
            case 96: case 167: {
                int d = m & 3;
                int pd = switch (d) { case 0 -> 3; case 1 -> 2; case 2 -> 1; default -> 0; };
                int nm = pd | ((m & 4) != 0 ? 8 : 0) | ((m & 8) != 0 ? 4 : 0);
                return new int[]{id, nm, 0};
            }
            case 125: return new int[]{157, m, 0};
            case 126: return new int[]{158, m, 0};
            case 155: return m == 3 ? new int[]{155, 6, 0} : m == 4 ? new int[]{155, 10, 0} : new int[]{155, m, 0};
            case 157: return new int[]{126, m, 0};
            case 158: return new int[]{125, m, 0};
            case 160: return new int[]{102, 0, 1};
            case 166: return new int[]{95, 0, 0};
            case 188: return new int[]{85, 1, 0};
            case 189: return new int[]{85, 2, 0};
            case 190: return new int[]{85, 3, 0};
            case 191: return new int[]{85, 5, 0};
            case 192: return new int[]{85, 4, 0};
            case 207: { int[] a = {0, 2, 4, 7}; return new int[]{244, a[Math.min(3, m & 3)], 0}; }
            case 208: return new int[]{198, 0, 0};
            case 212: return new int[]{79, 0, 1};
            case 215: return new int[]{112, 0, 1};
            default:
                if (id >= 219 && id <= 234) return new int[]{-1, 0, 0};
                if (id >= 235 && id <= 250) return new int[]{159, id - 235, 1};
                if (id == 251 || id == 252) return new int[]{35, m, 1};
                if (id > 255) return new int[]{-1, 0, 0};
                return new int[]{id, m, 0};
        }
    }

    // ---------- экспорт .schematic Pocket ----------
    public static void writePocket(Clipboard c, Path f) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putShort("Width", c.w); root.putShort("Height", c.h); root.putShort("Length", c.l);
        root.putString("Materials", "Pocket");
        byte[] blocks = new byte[c.w * c.h * c.l], data = new byte[blocks.length];
        ListTag tiles = new ListTag(Tag.COMPOUND);
        for (int y = 0; y < c.h; y++) for (int z = 0; z < c.l; z++) for (int x = 0; x < c.w; x++) {
            int i = (y * c.l + z) * c.w + x;
            int v = c.get(x, y, z);
            if (v == Clipboard.NONE || v < 0) v = 0;
            blocks[i] = (byte) (v >> 4); data[i] = (byte) (v & 15);
            CompoundTag t = c.tiles.get(c.idx(x, y, z));
            if (t != null) tiles.add(TileUtil.moved(t, x, y, z));
        }
        root.put("Blocks", new ByteArrayTag(blocks));
        root.put("Data", new ByteArrayTag(data));
        root.put("Entities", new ListTag(Tag.COMPOUND));
        root.put("TileEntities", tiles);
        Path tmp = f.resolveSibling(f.getFileName() + ".wbtmp");
        try (OutputStream out = new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) { Tag.writeRoot(out, "Schematic", root); }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }
}
