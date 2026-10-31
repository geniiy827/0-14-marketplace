package com.nova.engine.mod;

import com.nova.engine.world.Blocks;

/** The vanilla blocks - registered like any mod would do it. Ids/data as in MCPE 0.14 (worlds stay compatible). */
public final class CoreContent implements NovaMod {
    public String id() { return "nova:core"; }

    public void register(Registry r) {
        int G = Blocks.TINT_GRASS, F = Blocks.TINT_FOLIAGE;
        r.block(1, "stone").tex(r.tex("stone:0"))
                .data(1, r.tex("stone:1")).data(2, r.tex("stone:2")).data(3, r.tex("stone:3"))
                .data(4, r.tex("stone:4")).data(5, r.tex("stone:5")).data(6, r.tex("stone:6"));
        r.block(2, "grass").tex(r.tex("grass:0"), r.tex("dirt"), r.tex("grass:3")).tintTop(G);
        r.block(3, "dirt").tex(r.tex("dirt"));
        r.block(4, "cobblestone").tex(r.tex("cobblestone"));
        Blocks.Def planks = r.block(5, "planks").tex(r.tex("planks:0"));
        for (int d = 1; d < 6; d++) planks.data(d, r.tex("planks:" + d));
        r.block(7, "bedrock").tex(r.tex("bedrock"));
        r.block(8, "flowing_water").tex(r.tex("still_water")).tint(Blocks.TINT_WATER).liquid();
        r.block(9, "water").tex(r.tex("still_water")).tint(Blocks.TINT_WATER).liquid();
        r.block(10, "flowing_lava").tex(r.tex("still_lava")).liquid().light(15);
        r.block(11, "lava").tex(r.tex("still_lava")).liquid().light(15);
        Blocks.layer[10] = Blocks.layer[11] = Blocks.OPAQUE;
        r.block(12, "sand").tex(r.tex("sand:0")).data(1, r.tex("sand:1"));
        r.block(13, "gravel").tex(r.tex("gravel"));
        r.block(14, "gold_ore").tex(r.tex("gold_ore"));
        r.block(15, "iron_ore").tex(r.tex("iron_ore"));
        r.block(16, "coal_ore").tex(r.tex("coal_ore"));
        Blocks.Def log = r.block(17, "log").tex(r.tex("log:1"), r.tex("log:1"), r.tex("log:0"));
        for (int d = 1; d < 4; d++) log.data(d, r.tex("log:" + (d * 2 + 1)), r.tex("log:" + (d * 2 + 1)), r.tex("log:" + d * 2));
        Blocks.Def leaves = r.block(18, "leaves").tex(r.tex("leaves:0")).tint(F).cutout().selfFaces();
        for (int d = 1; d < 4; d++) leaves.data(d, r.tex("leaves:" + d));
        r.block(20, "glass").tex(r.tex("glass")).cutout();
        r.block(21, "lapis_ore").tex(r.tex("lapis_ore"));
        r.block(24, "sandstone").tex(r.tex("sandstone:0"), r.tex("sandstone:0"), r.tex("sandstone:1"));
        r.block(31, "tallgrass").tex(r.tex("tallgrass:0")).tint(G).cross()
                .data(1, r.tex("tallgrass:0")).data(2, r.tex("tallgrass:2"));
        r.block(32, "deadbush").tex(r.tex("tallgrass:1")).cross();
        Blocks.Def wool = r.block(35, "wool").tex(r.tex("wool:0"));
        for (int d = 1; d < 16; d++) wool.data(d, r.tex("wool:" + d));
        r.block(37, "yellow_flower").tex(r.tex("flower1")).cross();
        Blocks.Def fl = r.block(38, "red_flower").tex(r.tex("flower2:0")).cross();
        for (int d = 1; d < 9; d++) fl.data(d, r.tex("flower2:" + d));
        r.block(45, "brick").tex(r.tex("brick"));
        r.block(48, "mossy_cobblestone").tex(r.tex("cobblestone_mossy"));
        r.block(49, "obsidian").tex(r.tex("obsidian"));
        r.block(56, "diamond_ore").tex(r.tex("diamond_ore"));
        r.block(73, "redstone_ore").tex(r.tex("redstone_ore"));
        r.block(78, "snow_layer").tex(r.tex("snow")).height(0.125f).notSolid();
        r.block(79, "ice").tex(r.tex("ice")).translucent();
        r.block(80, "snow").tex(r.tex("snow"));
        r.block(81, "cactus").tex(r.tex("cactus:1"), r.tex("cactus:2"), r.tex("cactus:0")).cutout();
        r.block(82, "clay").tex(r.tex("clay"));
        r.block(83, "reeds").tex(r.tex("reeds")).cross();
        r.block(89, "glowstone").tex(r.tex("glowstone")).light(15);
        r.block(98, "stonebrick").tex(r.tex("stonebrick:0"));
        r.block(103, "melon").tex(r.tex("melon:1"), r.tex("melon:1"), r.tex("melon:0"));
        r.block(155, "quartz_block").tex(r.tex("quartz_block:0"));
        // used by the 0.14.3 generator
        r.block(110, "mycelium").tex(r.tex("mycelium:1"), r.tex("dirt"), r.tex("mycelium:0"));
        Blocks.Def sc = r.block(159, "stained_hardened_clay").tex(r.tex("stained_clay:0"));
        for (int d = 1; d < 16; d++) sc.data(d, r.tex("stained_clay:" + d));
        r.block(172, "hardened_clay").tex(r.tex("hardened_clay"));
        r.block(243, "podzol").tex(r.tex("dirt:1"), r.tex("dirt"), r.tex("dirt"));
    }

    /** hotbar of the beta */
    public static final int[] HOTBAR = {1, 3, 2, 4, 5, 17, 20, 89, 98};
}
