package com.geniiy.worldbuilder;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.blocks.Rotation;
import com.geniiy.worldbuilder.edit.ChangeSet;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.pe.*;
import com.geniiy.worldbuilder.util.Pos;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class CoreTest {
    static boolean haveWorlds() { return Files.isDirectory(Paths.get("src/test/resources/worlds/world")); }

    static Path copyWorld(String name) throws IOException {
        assumeTrue(haveWorlds(), "нет тестового мира пользователя (src/test/resources/worlds)");
        Path src = Paths.get("src/test/resources/worlds", name);
        Path dst = Files.createTempDirectory("wbtest").resolve(name);
        try (var s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path d = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(d); else Files.copy(p, d);
            }
        }
        return dst;
    }

    @Test void indices() {
        assertEquals(0, PeChunk.bIndex(0, 0, 0));
        assertEquals(32767, PeChunk.bIndex(15, 127, 15));
        assertEquals(16383, PeChunk.nIndex(15, 127, 15));
        assertEquals(255, PeChunk.cIndex(15, 15));
        byte[] a = new byte[16384];
        PeChunk.setNibble(a, 3, 5, 7, 9); PeChunk.setNibble(a, 3, 4, 7, 6);
        assertEquals(9, PeChunk.getNibble(a, 3, 5, 7)); assertEquals(6, PeChunk.getNibble(a, 3, 4, 7));
        for (int x : new int[]{-30000000, -1, 0, 5, 29999999}) for (int z : new int[]{-29999999, -17, 0, 1, 30000000}) {
            long p = Pos.pack(x, 127, z);
            assertEquals(x, Pos.x(p)); assertEquals(z, Pos.z(p)); assertEquals(127, Pos.y(p));
        }
    }

    @Test void chunkNbtRoundTripByteExact() throws IOException {
        assumeTrue(haveWorlds(), "нет тестового мира пользователя");
        for (String wn : new String[]{"world", "nether"}) {
            Path w = Paths.get("src/test/resources/worlds", wn, "region", "r.0.0.mcr");
            RegionFile r = RegionFile.read(w, 0, 0);
            assertTrue(r.count() > 0);
            for (int i = 0; i < 1024; i++) {
                if (r.chunks[i] == null) continue;
                PeChunk c = PeChunk.fromRaw(i & 31, i >> 5, r.chunks[i]);
                assertFalse(c.corrupt, c.corruptReason);
                assertArrayEquals(r.chunks[i].decompress(), c.toNbtBytes(), "чанк " + i);
            }
        }
    }

    @Test void levelDatRoundTrip() throws IOException {
        Path d = copyWorld("world");
        byte[] before = new GZIPInputStream(Files.newInputStream(d.resolve("level.dat"))).readAllBytes();
        LevelDat l = LevelDat.read(d.resolve("level.dat"));
        assertEquals(Tag.INT, l.data().get("Time").type());
        l.write(d.resolve("level.dat"));
        byte[] after = new GZIPInputStream(Files.newInputStream(d.resolve("level.dat"))).readAllBytes();
        assertArrayEquals(before, after);
    }

    @Test void saveUnchangedCopiesBytes() throws IOException {
        Path d = copyWorld("world");
        byte[] orig = Files.readAllBytes(d.resolve("region/r.0.0.mcr"));
        PeWorld w = PeWorld.open(d);
        // меняем один блок
        int x = 130, z = 131;
        ChangeSet cs = new ChangeSet("t", "pencil");
        int top = w.topY(x, z);
        assertTrue(top > 0);
        w.set(x, top + 1, z, 54 << 4 | 2, cs);
        assertNotNull(w.getTile(x, top + 1, z));
        assertEquals("Chest", w.getTile(x, top + 1, z).getString("id", ""));
        Path out = Files.createTempDirectory("wbout");
        List<String> files = w.writeChanged(out, new PeWorld.SaveOptions(), null);
        assertTrue(files.contains("region/r.0.0.mcr"));
        RegionFile a = RegionFile.read(d.resolve("region/r.0.0.mcr"), 0, 0);
        RegionFile b = RegionFile.read(out.resolve("region/r.0.0.mcr"), 0, 0);
        int changed = 0;
        for (int i = 0; i < 1024; i++) {
            if (a.chunks[i] == null) { assertNull(b.chunks[i]); continue; }
            if (!Arrays.equals(a.chunks[i].data, b.chunks[i].data)) changed++;
        }
        assertEquals(1, changed);
        PeWorld w2 = PeWorld.open(out.resolve("..").resolve(out.getFileName()));
        // регион записан в out, level.dat тоже
        PeChunk c = PeChunk.fromRaw(x >> 4, z >> 4, b.chunks[RegionFile.index(x >> 4, z >> 4)]);
        assertEquals(54, c.getId(x & 15, top + 1, z & 15));
        assertEquals(top + 1, c.heightMap[PeChunk.cIndex(x & 15, z & 15)]);
        assertEquals(1, c.getFlag("TerrainPopulated"));
        assertEquals(1, c.getFlag("LightPopulated"));
        assertEquals(Tag.COMPOUND, c.getTile(x & 15, top + 1, z & 15).getList("Items").elemType);
        // undo
        w.undo(cs);
        assertNull(w.getTile(x, top + 1, z));
        assertEquals(0, w.getState(x, top + 1, z));
        assertNotNull(orig);
    }

    @Test void lightTorchInRoom() throws IOException {
        Path d = Files.createTempDirectory("wbl").resolve("w");
        PeWorld w = PeWorld.create(d, LevelDat.create("w", "flat", "2;0;1;", 1, 8, 70, 8, 1, 0), 0x0192BC59);
        // закрытая комната из камня 5x5x5 с факелом в центре
        for (int x = 0; x < 7; x++) for (int y = 60; y < 67; y++) for (int z = 0; z < 7; z++) {
            boolean shell = x == 0 || x == 6 || y == 60 || y == 66 || z == 0 || z == 6;
            w.set(x, y, z, shell ? 1 << 4 : 0, null);
        }
        w.set(3, 61, 3, 50 << 4 | 5, null);
        LightEngine.relight(w, w.dirtyChunks(), null);
        PeChunk c = w.getChunk(0, 0);
        int e = PeBlocks.get().lightEmission[50];
        assertEquals(e, c.getBlockLight(3, 61, 3));
        assertEquals(e - 1, c.getBlockLight(4, 61, 3));
        assertEquals(e - 2, c.getBlockLight(5, 61, 3));
        assertEquals(0, c.getSky(3, 62, 3));
        assertEquals(15, c.getSky(3, 70, 3));
        // дыра в потолке
        w.set(3, 66, 3, 0, null);
        LightEngine.relight(w, w.dirtyChunks(), null);
        assertEquals(15, c.getSky(3, 63, 3));
    }

    @Test void rotationCycles() {
        PeBlocks t = PeBlocks.get();
        for (PeBlocks.BlockInfo b : t.all) for (int m = 0; m < 16; m++) {
            int s = (b.id << 4) | m;
            assertEquals(s, Rotation.rotate(s, 4), "4×90 " + b.id + ":" + m + " " + b.family);
            assertEquals(s, Rotation.mirrorX(Rotation.mirrorX(s)), "MX " + b.id + ":" + m);
            assertEquals(s, Rotation.mirrorZ(Rotation.mirrorZ(s)), "MZ " + b.id + ":" + m);
            assertEquals(s, Rotation.mirrorY(Rotation.mirrorY(s)), "MY " + b.id + ":" + m);
        }
        // ступени east(0) → south(2)
        assertEquals(53 << 4 | 2, Rotation.rotateCW(53 << 4));
    }

    @Test void blockTable() {
        PeBlocks t = PeBlocks.get();
        assertEquals(187, t.all.size());
        assertNotNull(t.resolve(35, 14));
        assertTrue(t.resolve(85, 9).nonStandard());
        assertNull(t.resolve(250, 0));
        assertEquals(53 << 4, t.javaToPe("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom", "shape", "straight", "waterlogged", "false")));
    }
}
