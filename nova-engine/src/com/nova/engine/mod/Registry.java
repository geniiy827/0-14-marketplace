package com.nova.engine.mod;

import com.nova.engine.render.Atlas;
import com.nova.engine.world.Blocks;

/** what a mod gets at registration time */
public final class Registry {
    public final Atlas atlas;
    public String modId;

    public Registry(Atlas atlas) { this.atlas = atlas; }

    /** texture slot from the game atlas: "grass", "log:1" */
    public int tex(String ref) {
        int c = ref.lastIndexOf(':');
        if (c > 0) return atlas.slot(ref.substring(0, c), Integer.parseInt(ref.substring(c + 1)));
        return atlas.slot(ref, 0);
    }

    public Blocks.Def block(int id, String name) { return Blocks.register(id, name); }
}
