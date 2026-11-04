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
        r.block(2, "grass").tex(r.tex("grass:0"), r.tex("dirt"), r.tex("nova_grass_side")).tintTop(G)
                .sideOverlay(r.tex("nova_grass_side_overlay"), G);
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
        r.block(24, "sandstone").tex(r.tex("sandstone:0"), r.tex("sandstone:4"), r.tex("sandstone:3"))
                .data(1, r.tex("sandstone:0"), r.tex("sandstone:0"), r.tex("sandstone:1"))
                .data(2, r.tex("sandstone:0"), r.tex("sandstone:0"), r.tex("sandstone:2"));
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
        r.block(81, "cactus").tex(r.tex("cactus:0"), r.tex("cactus:2"), r.tex("cactus:1")).cutout().inset(1f / 16f);
        r.block(82, "clay").tex(r.tex("clay"));
        r.block(83, "reeds").tex(r.tex("reeds")).cross();
        r.block(89, "glowstone").tex(r.tex("glowstone")).light(15);
        r.block(98, "stonebrick").tex(r.tex("stonebrick:0"));
        r.block(103, "melon").tex(r.tex("melon:1"), r.tex("melon:1"), r.tex("melon:0"));
        r.block(155, "quartz_block").tex(r.tex("quartz_block:1"), r.tex("quartz_block:1"), r.tex("quartz_block:0"));
        r.block(39, "brown_mushroom").tex(r.tex("mushroom_brown")).cross();
        r.block(40, "red_mushroom").tex(r.tex("mushroom_red")).cross();
        // pumpkin: the carved face on the side given by data (0 south, 1 west, 2 north, 3 east)
        Blocks.Def pk = r.block(86, "pumpkin").tex(r.tex("pumpkin:0"), r.tex("pumpkin:0"), r.tex("pumpkin:1"));
        int[] faceOf = {3, 4, 2, 5};
        for (int d = 0; d < 4; d++) pk.data(d, r.tex("pumpkin:0"), r.tex("pumpkin:0"), r.tex("pumpkin:1")).dataFace(d, faceOf[d], r.tex("pumpkin:2"));
        Blocks.grassSnowSide = r.tex("grass:1");
        r.block(111, "waterlily").tex(r.tex("waterlily:0")).tint(F).cutout().height(1f / 64f).notSolid();
        // used by the 0.14.3 generator
        r.block(110, "mycelium").tex(r.tex("mycelium:1"), r.tex("dirt"), r.tex("mycelium:0"));
        Blocks.Def sc = r.block(159, "stained_hardened_clay").tex(r.tex("stained_clay:0"));
        for (int d = 1; d < 16; d++) sc.data(d, r.tex("stained_clay:" + d));
        r.block(172, "hardened_clay").tex(r.tex("hardened_clay"));
        r.block(161, "leaves2").tex(r.tex("leaves2:0")).tint(F).cutout().selfFaces().data(1, r.tex("leaves2:1"));
        r.block(162, "log2").tex(r.tex("log2:1"), r.tex("log2:1"), r.tex("log2:0"))
                .data(1, r.tex("log2:3"), r.tex("log2:3"), r.tex("log2:2"));
        // double plants: lower half data = type, upper half 8 | type (grass and fern tinted in the mesher)
        Blocks.Def dp = r.block(175, "double_plant").tex(r.tex("double_plant_bottom:0")).tint(G).cross();
        for (int d = 0; d < 6; d++) { dp.data(d, r.tex("double_plant_bottom:" + d)); dp.data(8 | d, r.tex("double_plant_top:" + d)); }
        // huge mushrooms: data 1..9 cap on top and the outer sides, 0 pores, 10 stem, 14 all cap, 15 all stem
        for (int id = 99; id <= 100; id++) {
            int cap = r.tex("mushroom_block:" + (id - 99)), pores = r.tex("mushroom_block:2"), stem = r.tex("mushroom_block:3");
            Blocks.Def m = r.block(id, id == 99 ? "brown_mushroom_block" : "red_mushroom_block").tex(pores);
            for (int d = 0; d < 16; d++) {
                if (d == 10) { m.data(d, pores, pores, stem); continue; }
                if (d == 14) { m.data(d, cap); continue; }
                if (d == 15) { m.data(d, stem); continue; }
                m.data(d, d >= 1 && d <= 9 ? cap : pores, pores, pores);
                if (d >= 1 && d <= 9) {
                    if (d % 3 == 1) m.dataFace(d, 4, cap);
                    if (d % 3 == 0) m.dataFace(d, 5, cap);
                    if (d <= 3) m.dataFace(d, 2, cap);
                    if (d >= 7) m.dataFace(d, 3, cap);
                }
            }
        }
        r.block(44, "stone_slab").tex(r.tex("stone_slab:0"), r.tex("stone_slab:0"), r.tex("stone_slab:1")).height(0.5f)
                .data(1, r.tex("sandstone:0"), r.tex("sandstone:4"), r.tex("sandstone:3"));
        r.block(179, "red_sandstone").tex(r.tex("redsandstone:0"), r.tex("redsandstone:4"), r.tex("redsandstone:3"));
        r.block(52, "mob_spawner").tex(r.tex("mob_spawner")).cutout();
        // chest: block model from the inventory tiles (top, side, front) until the chest entity model exists
        Blocks.Def ch = r.block(54, "chest").tex(r.tex("chest_inventory:0"), r.tex("chest_inventory:0"), r.tex("chest_inventory:1")).inset(1f / 16f);
        for (int d = 2; d <= 5; d++) ch.data(d, r.tex("chest_inventory:0"), r.tex("chest_inventory:0"), r.tex("chest_inventory:1")).dataFace(d, d, r.tex("chest_inventory:2"));
        r.block(129, "emerald_ore").tex(r.tex("emerald_ore"));
        r.block(243, "podzol").tex(r.tex("dirt:1"), r.tex("dirt"), r.tex("dirt:2"));
    }

    /** hotbar of the beta */
    public static final int[] HOTBAR = {1, 3, 2, 4, 5, 17, 20, 89, 98};
}
