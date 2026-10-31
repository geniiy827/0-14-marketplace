package com.nova.engine.world;

/**
 * Biomes of MCPE 0.14.3 with their real ids (the names follow the original's Biome:: members).
 * depth/scale = terrain heights (Biome +0x64/+0x68), temperature, class (for the layer rules), surface blocks.
 */
public final class Biomes {
    public static final int OCEAN = 0, PLAINS = 1, DESERT = 2, EXTREME_HILLS = 3, FOREST = 4, TAIGA = 5, SWAMPLAND = 6,
            RIVER = 7, HELL = 8, SKY = 9, FROZEN_OCEAN = 10, FROZEN_RIVER = 11, ICE_FLATS = 12, ICE_MOUNTAINS = 13,
            MUSHROOM_ISLAND = 14, MUSHROOM_ISLAND_SHORE = 15, BEACHES = 16, DESERT_HILLS = 17, FOREST_HILLS = 18,
            TAIGA_HILLS = 19, SMALLER_EXTREME_HILLS = 20, JUNGLE = 21, JUNGLE_HILLS = 22, JUNGLE_EDGE = 23, DEEP_OCEAN = 24,
            STONE_BEACH = 25, COLD_BEACH = 26, BIRCH_FOREST = 27, BIRCH_FOREST_HILLS = 28, ROOFED_FOREST = 29,
            TAIGA_COLD = 30, TAIGA_COLD_HILLS = 31, REDWOOD_TAIGA = 32, REDWOOD_TAIGA_HILLS = 33,
            EXTREME_HILLS_WITH_TREES = 34, SAVANNA = 35, SAVANNA_ROCK = 36, MESA = 37, MESA_ROCK = 38, MESA_CLEAR_ROCK = 39,
            MUTATED_PLAINS = 129;

    // classes (Biome subclass of the original; the layers compare them)
    public static final int C_OCEAN = 0, C_PLAINS = 1, C_DESERT = 2, C_HILLS = 3, C_FOREST = 4, C_FOREST_MUT = 5, C_TAIGA = 6,
            C_SWAMP = 7, C_RIVER = 8, C_HELL = 9, C_END = 10, C_SNOW = 11, C_MUSHROOM = 12, C_BEACH = 13, C_JUNGLE = 14,
            C_STONE_BEACH = 15, C_SAVANNA = 16, C_SAVANNA_MUT = 17, C_MESA = 18;
    public static final int T_OCEAN = 0, T_COLD = 1, T_MEDIUM = 2, T_WARM = 3;

    public static final boolean[] EXISTS = new boolean[256];
    public static final String[] NAME = new String[256];
    public static final float[] DEPTH = new float[256], SCALE = new float[256], TEMP = new float[256];
    public static final int[] CLASS = new int[256], TYPE = new int[256], MUTATION = new int[256];
    public static final boolean[] SNOWY = new boolean[256], MUTATED = new boolean[256];
    public static final int[] TOP = new int[256], TOP_DATA = new int[256], FILLER = new int[256];
    public static final int[] GRASS = new int[256], FOLIAGE = new int[256], WATER = new int[256];
    /** trees per chunk (interim decoration until the original decorators are ported) */
    public static final float[] TREES = new float[256];

    private static int last;

    private static void b(int id, String name, float depth, float scale, float temp, int cls, int grass, int foliage, float trees) {
        EXISTS[id] = true; NAME[id] = name; DEPTH[id] = depth; SCALE[id] = scale; TEMP[id] = temp; CLASS[id] = cls;
        GRASS[id] = grass; FOLIAGE[id] = foliage; TREES[id] = trees; MUTATION[id] = -1;
        TOP[id] = 2; FILLER[id] = 3; WATER[id] = 0x3F76E4;
        last = id;
    }
    private static void top(int top, int data, int filler) { TOP[last] = top; TOP_DATA[last] = data; FILLER[last] = filler; }
    /** mutated variant of base */
    private static void m(int id, int base, String name, float depth, float scale, float temp) {
        b(id, name, depth, scale, temp, CLASS[base], GRASS[base], FOLIAGE[base], TREES[base]);
        TOP[id] = TOP[base]; TOP_DATA[id] = TOP_DATA[base]; FILLER[id] = FILLER[base]; WATER[id] = WATER[base];
        SNOWY[id] = SNOWY[base]; TYPE[id] = TYPE[base]; MUTATED[id] = true; MUTATION[base] = id;
    }

    static {
        b(0, "Ocean", -1.0f, 0.1f, 0.5f, C_OCEAN, 0x8EB971, 0x71A74D, 0);
        b(1, "Plains", 0.125f, 0.05f, 0.8f, C_PLAINS, 0x91BD59, 0x77AB2F, 0.1f);
        b(2, "Desert", 0.125f, 0.05f, 2.0f, C_DESERT, 0xBFB755, 0xAEA42A, 0); top(12, 0, 12);
        b(3, "Extreme Hills", 1.0f, 0.5f, 0.2f, C_HILLS, 0x8AB689, 0x6DA36B, 0.1f);
        b(4, "Forest", 0.1f, 0.2f, 0.7f, C_FOREST, 0x79C05A, 0x59AE30, 10);
        b(5, "Taiga", 0.2f, 0.2f, 0.25f, C_TAIGA, 0x86B783, 0x68A464, 10);
        b(6, "Swampland", -0.2f, 0.1f, 0.8f, C_SWAMP, 0x6A7039, 0x6A7039, 2); WATER[6] = 0x617B64;
        b(7, "River", -0.5f, 0.0f, 0.5f, C_RIVER, 0x8EB971, 0x71A74D, 0);
        b(8, "Hell", 0.1f, 0.2f, 2.0f, C_HELL, 0xBFB755, 0xAEA42A, 0);
        b(9, "The End", 0.1f, 0.2f, 0.5f, C_END, 0x8EB971, 0x71A74D, 0);
        b(10, "Frozen Ocean", -1.0f, 0.1f, 0.0f, C_OCEAN, 0x80B497, 0x60A17B, 0); SNOWY[10] = true;
        b(11, "Frozen River", -0.5f, 0.0f, 0.0f, C_RIVER, 0x80B497, 0x60A17B, 0); SNOWY[11] = true;
        b(12, "Ice Plains", 0.125f, 0.05f, 0.0f, C_SNOW, 0x80B497, 0x60A17B, 0); SNOWY[12] = true;
        b(13, "Ice Mountains", 0.45f, 0.3f, 0.0f, C_SNOW, 0x80B497, 0x60A17B, 0); SNOWY[13] = true;
        b(14, "Mushroom Island", 0.2f, 0.3f, 0.9f, C_MUSHROOM, 0x55C93F, 0x2BBB0F, 0); top(110, 0, 3);
        b(15, "Mushroom Island Shore", 0.0f, 0.025f, 0.9f, C_MUSHROOM, 0x55C93F, 0x2BBB0F, 0); top(110, 0, 3);
        b(16, "Beach", 0.0f, 0.025f, 0.8f, C_BEACH, 0x91BD59, 0x77AB2F, 0); top(12, 0, 12);
        b(17, "Desert Hills", 0.45f, 0.3f, 2.0f, C_DESERT, 0xBFB755, 0xAEA42A, 0); top(12, 0, 12);
        b(18, "Forest Hills", 0.45f, 0.3f, 0.7f, C_FOREST, 0x79C05A, 0x59AE30, 10);
        b(19, "Taiga Hills", 0.45f, 0.3f, 0.25f, C_TAIGA, 0x86B783, 0x68A464, 10);
        b(20, "Extreme Hills Edge", 0.8f, 0.3f, 0.2f, C_HILLS, 0x8AB689, 0x6DA36B, 3); TYPE[20] = 1;
        b(21, "Jungle", 0.1f, 0.2f, 0.95f, C_JUNGLE, 0x59C93C, 0x30BB0B, 50);
        b(22, "Jungle Hills", 0.45f, 0.3f, 0.95f, C_JUNGLE, 0x59C93C, 0x30BB0B, 50);
        b(23, "Jungle Edge", 0.1f, 0.2f, 0.95f, C_JUNGLE, 0x64C73F, 0x3EB80F, 2);
        b(24, "Deep Ocean", -1.8f, 0.1f, 0.5f, C_OCEAN, 0x8EB971, 0x71A74D, 0);
        b(25, "Stone Beach", 0.1f, 0.8f, 0.2f, C_STONE_BEACH, 0x8AB689, 0x6DA36B, 0); top(1, 0, 1);
        b(26, "Cold Beach", 0.0f, 0.025f, 0.05f, C_BEACH, 0x80B497, 0x60A17B, 0); top(12, 0, 12); SNOWY[26] = true;
        b(27, "Birch Forest", 0.1f, 0.2f, 0.6f, C_FOREST, 0x88BB67, 0x6BA941, 10);
        b(28, "Birch Forest Hills", 0.45f, 0.3f, 0.6f, C_FOREST, 0x88BB67, 0x6BA941, 10);
        b(29, "Roofed Forest", 0.1f, 0.2f, 0.7f, C_FOREST, 0x507A32, 0x59AE30, 10);
        b(30, "Cold Taiga", 0.2f, 0.2f, -0.5f, C_TAIGA, 0x80B497, 0x60A17B, 10); SNOWY[30] = true;
        b(31, "Cold Taiga Hills", 0.45f, 0.3f, -0.5f, C_TAIGA, 0x80B497, 0x60A17B, 10); SNOWY[31] = true;
        b(32, "Mega Taiga", 0.2f, 0.2f, 0.3f, C_TAIGA, 0x86B87F, 0x68A464, 10); TYPE[32] = 1;
        b(33, "Mega Taiga Hills", 0.45f, 0.3f, 0.3f, C_TAIGA, 0x86B87F, 0x68A464, 10); TYPE[33] = 1;
        b(34, "Extreme Hills+", 1.0f, 0.5f, 0.2f, C_HILLS, 0x8AB689, 0x6DA36B, 3); TYPE[34] = 1;
        b(35, "Savanna", 0.125f, 0.05f, 1.2f, C_SAVANNA, 0xBFB755, 0xAEA42A, 1);
        b(36, "Savanna Plateau", 1.5f, 0.025f, 1.0f, C_SAVANNA, 0xBFB755, 0xAEA42A, 1);
        b(37, "Mesa", 0.1f, 0.2f, 2.0f, C_MESA, 0x90814D, 0x9E814D, 0); top(12, 1, 159);
        b(38, "Mesa Plateau F", 1.5f, 0.025f, 2.0f, C_MESA, 0x90814D, 0x9E814D, 0.5f); top(12, 1, 159);
        b(39, "Mesa Plateau", 1.5f, 0.025f, 2.0f, C_MESA, 0x90814D, 0x9E814D, 0); top(12, 1, 159);
        m(129, 1, "Sunflower Plains", 0.125f, 0.05f, 0.8f);
        m(130, 2, "Desert M", 0.225f, 0.25f, 2.0f);
        m(131, 3, "Extreme Hills M", 1.0f, 0.5f, 0.2f); TYPE[131] = 2;
        m(132, 4, "Flower Forest", 0.1f, 0.4f, 0.7f);
        m(133, 5, "Taiga M", 0.3f, 0.4f, 0.25f);
        m(134, 6, "Swampland M", -0.1f, 0.3f, 0.8f);
        m(140, 12, "Ice Plains Spikes", 0.425f, 0.45f, 0.0f); TOP[140] = 80;
        m(149, 21, "Jungle M", 0.2f, 0.4f, 0.95f);
        m(151, 23, "Jungle Edge M", 0.2f, 0.4f, 0.95f);
        m(155, 27, "Birch Forest M", 0.2f, 0.4f, 0.6f); CLASS[155] = C_FOREST_MUT;
        m(156, 28, "Birch Forest Hills M", 0.55f, 0.5f, 0.6f); CLASS[156] = C_FOREST_MUT;
        m(157, 29, "Roofed Forest M", 0.2f, 0.4f, 0.7f);
        m(158, 30, "Cold Taiga M", 0.3f, 0.4f, -0.5f);
        m(160, 32, "Mega Spruce Taiga", 0.2f, 0.2f, 0.25f); TYPE[160] = 2;
        m(161, 33, "Mega Spruce Taiga Hills", 0.2f, 0.2f, 0.25f); TYPE[161] = 2;
        m(162, 34, "Extreme Hills+ M", 1.0f, 0.5f, 0.2f); TYPE[162] = 2;
        m(163, 35, "Savanna M", 0.3625f, 1.225f, 1.1f); CLASS[163] = C_SAVANNA_MUT;
        m(164, 36, "Savanna Plateau M", 1.05f, 1.2125f, 1.0f); CLASS[164] = C_SAVANNA_MUT;
        m(165, 37, "Mesa (Bryce)", 0.1f, 0.2f, 2.0f);
        m(166, 38, "Mesa Plateau F M", 0.45f, 0.3f, 2.0f);
        m(167, 39, "Mesa Plateau M", 0.45f, 0.3f, 2.0f);
    }

    public static boolean valid(int id) { return id >= 0 && id < 256 && EXISTS[id]; }

    public static int tempCategory(int id) {
        if (CLASS[id] == C_OCEAN) return T_OCEAN;
        float t = TEMP[id];
        return t < 0.2f ? T_COLD : t < 1.0f ? T_MEDIUM : T_WARM;
    }

    public static boolean isOceanic(int id) { return id == OCEAN || id == DEEP_OCEAN || id == FROZEN_OCEAN; }

    private Biomes() { }
}
