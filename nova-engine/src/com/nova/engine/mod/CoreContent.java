package com.nova.engine.mod;

import com.nova.engine.world.Blocks;

/** The vanilla blocks - registered like any mod would do it. Ids as in MCPE 0.14 (worlds stay compatible). */
public final class CoreContent implements NovaMod {
    static final int GRASS = 0x79C05A, FOLIAGE = 0x59AE30, WATER = 0x3F76E4;

    public String id() { return "nova:core"; }

    public void register(Registry r) {
        r.block(1, "stone").tex(r.tex("stone"));
        r.block(2, "grass").tex(r.tex("grass:0"), r.tex("dirt"), r.tex("grass:3")).tintTop(GRASS);
        r.block(3, "dirt").tex(r.tex("dirt"));
        r.block(4, "cobblestone").tex(r.tex("cobblestone"));
        r.block(5, "planks").tex(r.tex("planks"));
        r.block(7, "bedrock").tex(r.tex("bedrock"));
        r.block(9, "water").tex(r.tex("still_water")).tint(WATER).liquid();
        r.block(12, "sand").tex(r.tex("sand"));
        r.block(13, "gravel").tex(r.tex("gravel"));
        r.block(14, "gold_ore").tex(r.tex("gold_ore"));
        r.block(15, "iron_ore").tex(r.tex("iron_ore"));
        r.block(16, "coal_ore").tex(r.tex("coal_ore"));
        r.block(17, "log").tex(r.tex("log:1"), r.tex("log:1"), r.tex("log:0"));
        r.block(18, "leaves").tex(r.tex("leaves:0")).tint(FOLIAGE).cutout().selfFaces();
        r.block(20, "glass").tex(r.tex("glass")).cutout();
        r.block(24, "sandstone").tex(r.tex("sandstone:0"), r.tex("sandstone:0"), r.tex("sandstone:1"));
        r.block(31, "tallgrass").tex(r.tex("tallgrass:0")).tint(GRASS).cross();
        r.block(35, "wool").tex(r.tex("wool:0"));
        r.block(37, "yellow_flower").tex(r.tex("flower1")).cross();
        r.block(38, "red_flower").tex(r.tex("flower2")).cross();
        r.block(45, "brick").tex(r.tex("brick"));
        r.block(49, "obsidian").tex(r.tex("obsidian"));
        r.block(56, "diamond_ore").tex(r.tex("diamond_ore"));
        r.block(79, "ice").tex(r.tex("ice")).translucent();
        r.block(80, "snow").tex(r.tex("snow"));
        r.block(89, "glowstone").tex(r.tex("glowstone")).light(15);
    }

    /** hotbar of the beta */
    public static final int[] HOTBAR = {1, 3, 2, 4, 5, 17, 18, 20, 89};
}
