package com.nova.engine.world;

/** Biomes: terrain depth/scale (as in the Java edition of the time), surface, colours, trees. */
public final class Biomes {
    public static final int OCEAN = 0, PLAINS = 1, DESERT = 2, HILLS = 3, FOREST = 4, TAIGA = 5, SWAMP = 6,
            ICE = 7, JUNGLE = 8, BIRCH = 9, SAVANNA = 10, BEACH = 11, DEEP_OCEAN = 12, COUNT = 13;
    public static final String[] NAME = {"Ocean", "Plains", "Desert", "Extreme Hills", "Forest", "Taiga", "Swampland",
            "Ice Plains", "Jungle", "Birch Forest", "Savanna", "Beach", "Deep Ocean"};
    public static final float[] DEPTH = {-1.0f, 0.125f, 0.125f, 1.0f, 0.1f, 0.2f, -0.2f, 0.125f, 0.1f, 0.1f, 0.125f, 0.0f, -1.8f};
    public static final float[] SCALE = {0.1f, 0.05f, 0.05f, 0.5f, 0.2f, 0.2f, 0.1f, 0.05f, 0.2f, 0.2f, 0.05f, 0.025f, 0.1f};
    public static final int[] GRASS = {0x8EB971, 0x91BD59, 0xBFB755, 0x8AB689, 0x79C05A, 0x86B783, 0x6A7039, 0x80B497,
            0x59C93C, 0x88BB67, 0xBFB755, 0x91BD59, 0x8EB971};
    public static final int[] FOLIAGE = {0x71A74D, 0x77AB2F, 0xAEA42A, 0x6DA36B, 0x59AE30, 0x68A464, 0x6A7039, 0x60A17B,
            0x30BB0B, 0x6BA941, 0xAEA42A, 0x77AB2F, 0x71A74D};
    public static final int[] WATER = {0x3F76E4, 0x3F76E4, 0x3F76E4, 0x3F76E4, 0x3F76E4, 0x3F76E4, 0x617B64, 0x3F76E4,
            0x3F76E4, 0x3F76E4, 0x3F76E4, 0x3F76E4, 0x3F76E4};
    /** trees per chunk (average) */
    public static final float[] TREES = {0, 0.3f, 0, 0.6f, 8, 7, 2, 0.4f, 12, 7, 0.6f, 0, 0};

    private Biomes() { }
}
