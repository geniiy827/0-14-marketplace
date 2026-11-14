package com.geniiy.worldbuilder;

import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.session.WorldTemplates;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

public class TestWorldGen {
    @Test void generateAllBlocksWorld() throws Exception {
        Path d = Paths.get(System.getProperty("java.io.tmpdir"), "wb_testworld");
        com.geniiy.worldbuilder.remote.WorldSource.deleteTree(d);
        PeWorld w = WorldTemplates.testWorld(d);
        PeWorld.SaveOptions o = new PeWorld.SaveOptions(); o.computeLight = false;
        w.writeChanged(d, o, null);
        PeWorld r = PeWorld.open(d);
        assertEquals(53 << 4, r.getState(2, 61, 2 + 5 * indexOf(53)));
    }
    static int indexOf(int id) { int i = 0; for (var b : com.geniiy.worldbuilder.blocks.PeBlocks.get().all) { if (b.id == 0) continue; if (b.id == id) return i; i++; } return -1; }
}
