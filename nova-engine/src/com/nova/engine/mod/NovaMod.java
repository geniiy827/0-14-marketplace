package com.nova.engine.mod;

/**
 * A mod. The vanilla game is a mod too (CoreContent): it registers its blocks through exactly the same API,
 * so any other mod can replace or change any of them (register the same id again, or edit Blocks.* after it).
 * Order: core first, then mods in load order.
 */
public interface NovaMod {
    String id();
    void register(Registry r);
}
