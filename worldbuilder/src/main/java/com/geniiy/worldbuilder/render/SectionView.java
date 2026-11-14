package com.geniiy.worldbuilder.render;

import com.geniiy.worldbuilder.blocks.JavaStates;
import com.geniiy.worldbuilder.edit.Selection;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.pe.PeChunk;
import com.geniiy.worldbuilder.pe.PeWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;

/**
 * Снимок области 18×18×18 вокруг секции 16³ для построения меша в фоновом потоке:
 * Java-состояния (с вычисленными по соседям свойствами), свет и цвета биомов. Реализует BlockRenderView,
 * чтобы рисовать стандартным BlockRenderManager игры.
 */
public final class SectionView implements BlockRenderView {
    public static final int N = 18, B = 20; // область с полями 1 и 2
    public final int ox, oy, oz; // мировой угол секции
    final BlockState[] states = new BlockState[N * N * N];
    final byte[] sky = new byte[N * N * N];
    final byte[] blk = new byte[N * N * N];
    final int[] colors = new int[N * N];
    final int[] base = new int[B * B * B];
    boolean fullBright;
    public boolean empty = true;

    public static final class Options {
        public volatile boolean fullBright = true, showInvisible, onlySelection;
        public volatile int sliceY = 999;
        public volatile Selection selection;
    }

    private SectionView(int ox, int oy, int oz) { this.ox = ox; this.oy = oy; this.oz = oz; }

    static int bi(int x, int y, int z) { return ((y + 2) * B + (z + 2)) * B + (x + 2); }
    static int ni(int x, int y, int z) { return ((y + 1) * N + (z + 1)) * N + (x + 1); }

    static boolean hiddenId(int id) { return id == 95 || id == 248 || id == 249 || id == 255; }

    public static SectionView capture(PeWorld w, int cx, int sy, int cz, Options opt) {
        SectionView v = new SectionView(cx * 16, sy * 16, cz * 16);
        v.fullBright = opt.fullBright;
        PeChunk[] ch = new PeChunk[9];
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ch[(dx + 1) * 3 + (dz + 1)] = w.chunkExists(cx + dx, cz + dz) ? w.getChunk(cx + dx, cz + dz) : null;
        boolean stubs = !w.stubs.isEmpty();
        Selection sel = opt.onlySelection ? opt.selection : null;
        int slice = opt.sliceY;
        for (int x = -2; x < 18; x++) for (int z = -2; z < 18; z++) {
            int wx = v.ox + x, wz = v.oz + z;
            PeChunk c = ch[(Math.floorDiv(wx, 16) - cx + 1) * 3 + (Math.floorDiv(wz, 16) - cz + 1)];
            for (int y = -2; y < 18; y++) {
                int wy = v.oy + y;
                int st = 0;
                if (c != null && wy >= 0 && wy < 128 && wy <= slice) {
                    st = c.getState(wx & 15, wy, wz & 15);
                    if (!opt.showInvisible && hiddenId(st >> 4)) st = 0;
                }
                if (stubs && wy >= 0 && wy < 128 && w.stubs.containsKey(com.geniiy.worldbuilder.util.Pos.pack(wx, wy, wz))) st = -1;
                if (sel != null && st != 0 && !sel.contains(wx, wy, wz)) st = 0;
                v.base[bi(x, y, z)] = st;
                if (st != 0 && x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) v.empty = false;
            }
        }
        if (v.empty) return v;
        JavaStates.Source src = new JavaStates.Source() {
            public int peState(int x, int y, int z) {
                int lx = x - v.ox, ly = y - v.oy, lz = z - v.oz;
                if (lx < -2 || ly < -2 || lz < -2 || lx >= 18 || ly >= 18 || lz >= 18) return w.getState(x, y, z);
                int s = v.base[bi(lx, ly, lz)];
                return s < 0 ? 0 : s;
            }
            public Tag.CompoundTag tile(int x, int y, int z) { return w.getTile(x, y, z); }
        };
        for (int x = -1; x < 17; x++) for (int z = -1; z < 17; z++) {
            int wx = v.ox + x, wz = v.oz + z;
            PeChunk c = ch[(Math.floorDiv(wx, 16) - cx + 1) * 3 + (Math.floorDiv(wz, 16) - cz + 1)];
            v.colors[(z + 1) * N + (x + 1)] = c == null ? w.defaultBiomeColor : c.getBiomeColor(wx & 15, wz & 15);
            for (int y = -1; y < 17; y++) {
                int i = ni(x, y, z);
                int st = v.base[bi(x, y, z)];
                v.states[i] = st == 0 ? Blocks.AIR.getDefaultState() : st < 0 ? JavaStates.UNKNOWN_STATE : JavaStates.compute(src, wx, v.oy + y, wz);
                int wy = v.oy + y;
                if (c != null && wy >= 0 && wy < 128) {
                    v.sky[i] = (byte) c.getSky(wx & 15, wy, wz & 15);
                    v.blk[i] = (byte) c.getBlockLight(wx & 15, wy, wz & 15);
                } else v.sky[i] = 15;
            }
        }
        return v;
    }

    private int idx(BlockPos p) {
        int x = p.getX() - ox, y = p.getY() - oy, z = p.getZ() - oz;
        if (x < -1 || y < -1 || z < -1 || x > 16 || y > 16 || z > 16) return -1;
        return ni(x, y, z);
    }

    public BlockState local(int x, int y, int z) { return states[ni(x, y, z)]; }

    @Override public BlockState getBlockState(BlockPos pos) { int i = idx(pos); return i < 0 ? Blocks.AIR.getDefaultState() : states[i]; }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public float getBrightness(Direction d, boolean shaded) {
        if (!shaded) return 1f;
        return switch (d) { case DOWN -> 0.5f; case UP -> 1f; case NORTH, SOUTH -> 0.8f; default -> 0.6f; };
    }
    @Override public LightingProvider getLightingProvider() { return null; }
    @Override public int getLightLevel(LightType type, BlockPos pos) {
        if (fullBright) return 15;
        int i = idx(pos);
        if (i < 0) return type == LightType.SKY ? 15 : 0;
        return type == LightType.SKY ? sky[i] : blk[i];
    }
    @Override public int getBaseLightLevel(BlockPos pos, int ambientDarkness) {
        return Math.max(getLightLevel(LightType.SKY, pos) - ambientDarkness, getLightLevel(LightType.BLOCK, pos));
    }
    @Override public boolean isSkyVisible(BlockPos pos) { return getLightLevel(LightType.SKY, pos) >= 15; }
    @Override public int getColor(BlockPos pos, ColorResolver r) {
        if (r == BiomeColors.WATER_COLOR) return 0x3F76E4;
        int x = Math.max(-1, Math.min(16, pos.getX() - ox)), z = Math.max(-1, Math.min(16, pos.getZ() - oz));
        int c = colors[(z + 1) * N + (x + 1)] & 0xFFFFFF;
        return c == 0 ? 0x5A7A3A : c;
    }
    @Override public int getHeight() { return 128; }
    @Override public int getBottomY() { return 0; }
}
