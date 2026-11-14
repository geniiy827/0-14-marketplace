package com.geniiy.worldbuilder.blocks;

import com.geniiy.worldbuilder.WorldBuilderMod;
import com.geniiy.worldbuilder.nbt.Tag;
import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.EmptyBlockView;

/**
 * Кэш «состояние PE (id<<4|meta) → BlockState Java» и вычисление свойств, зависящих от соседей.
 * Строки JSON разбираются один раз при старте.
 */
public final class JavaStates {
    public static final BlockState[] BASE = new BlockState[4096];
    public static final boolean[] NON_STANDARD = new boolean[4096];
    /** Маска граней (по Direction.ordinal) с полной твёрдой стороной для базового состояния. */
    public static final byte[] SOLID_FACES = new byte[4096];
    /** Блок требует пересчёта по соседям или тайлу. */
    public static final boolean[] COMPUTED = new boolean[256];
    public static final boolean[] UNKNOWN_ID = new boolean[256];
    public static BlockState UNKNOWN_STATE;
    private static boolean ready;

    /** Источник данных о соседях (модель PE). */
    public interface Source {
        int peState(int x, int y, int z);
        Tag.CompoundTag tile(int x, int y, int z);
    }

    public static void init() {
        if (ready) return;
        PeBlocks t = PeBlocks.get();
        UNKNOWN_STATE = ModBlocks.UNKNOWN.getDefaultState();
        var wrapper = Registries.BLOCK.getReadOnlyWrapper();
        java.util.HashMap<String, BlockState> parsed = new java.util.HashMap<>();
        int fails = 0;
        for (int id = 0; id < 256; id++) {
            PeBlocks.BlockInfo b = t.block(id);
            UNKNOWN_ID[id] = b == null;
            for (int meta = 0; meta < 16; meta++) {
                int st = (id << 4) | meta;
                if (b == null) { BASE[st] = UNKNOWN_STATE; continue; }
                PeBlocks.Resolved r = t.resolve(id, meta);
                NON_STANDARD[st] = r.nonStandard();
                String js = r.meta() == null ? null : r.meta().java;
                BlockState bs = null;
                if (js != null) {
                    bs = parsed.get(js);
                    if (bs == null) {
                        try {
                            bs = BlockArgumentParser.block(wrapper, js, false).blockState();
                        } catch (Exception e) {
                            fails++;
                            WorldBuilderMod.LOG.warn("[WorldBuilder] Не удалось разобрать состояние '{}' для {}:{}: {}", js, id, meta, e.getMessage());
                            bs = UNKNOWN_STATE;
                        }
                        parsed.put(js, bs);
                    }
                }
                BASE[st] = bs == null ? UNKNOWN_STATE : bs;
            }
        }
        BASE[0] = Blocks.AIR.getDefaultState();
        for (int st = 0; st < 4096; st++) {
            BlockState s = BASE[st];
            int mask = 0;
            for (Direction d : Direction.values()) {
                try { if (s.isSideSolidFullSquare(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, d)) mask |= 1 << d.ordinal(); } catch (Throwable ignored) {}
            }
            SOLID_FACES[st] = (byte) mask;
        }
        for (int id = 0; id < 256; id++) {
            PeBlocks.BlockInfo b = t.block(id);
            if (b == null) continue;
            COMPUTED[id] = switch (b.family) {
                case "fence", "pane", "wall", "stairs", "redstone_wire", "tripwire", "vine", "fire", "snowy",
                     "fence_gate", "door", "double_plant", "flower_pot", "skull" -> true;
                default -> id == 104 || id == 105;
            };
        }
        if (fails > 0) WorldBuilderMod.LOG.warn("[WorldBuilder] Не разобрано состояний: {}", fails);
        ready = true;
    }

    public static boolean solid(int st, Direction d) { return (SOLID_FACES[st & 4095] & (1 << d.ordinal())) != 0; }

    static int idOf(int st) { return st >> 4; }
    static boolean isFence(int id) { return id == 85 || id == 113; }
    static boolean isGate(int id) { return id == 107 || (id >= 183 && id <= 187); }
    static boolean isPane(int id) { return id == 101 || id == 102; }
    static boolean isWall(int id) { return id == 139; }
    static boolean cannotConnect(int id) {
        return id == 18 || id == 161 || id == 86 || id == 91 || id == 103 || id == 95 || id == 0;
    }

    private static final Direction[] H = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** Состояние Java для показа блока с учётом соседей и тайла. */
    public static BlockState compute(Source src, int x, int y, int z) {
        int st = src.peState(x, y, z);
        BlockState s = BASE[st & 4095];
        int id = st >> 4;
        if (!COMPUTED[id]) return s;
        try {
            return computeInner(src, x, y, z, st, s);
        } catch (Throwable e) {
            return s;
        }
    }

    private static int nb(Source src, int x, int y, int z, Direction d) {
        return src.peState(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ());
    }

    private static BlockState computeInner(Source src, int x, int y, int z, int st, BlockState s) {
        int id = st >> 4, meta = st & 15;
        PeBlocks.BlockInfo b = PeBlocks.get().block(id);
        switch (b.family) {
            case "fence": {
                for (Direction d : H) {
                    int n = nb(src, x, y, z, d), nid = n >> 4;
                    boolean c;
                    if (isFence(nid)) c = nid == id;
                    else if (isGate(nid)) c = gateConnects(n, d);
                    else c = !cannotConnect(nid) && solid(n, d.getOpposite());
                    s = s.with(prop(d), c);
                }
                return s;
            }
            case "pane": {
                for (Direction d : H) {
                    int n = nb(src, x, y, z, d), nid = n >> 4;
                    boolean c = isPane(nid) || isWall(nid) || (!cannotConnect(nid) && solid(n, d.getOpposite()));
                    s = s.with(prop(d), c);
                }
                return s;
            }
            case "wall": {
                boolean[] con = new boolean[4];
                int above = src.peState(x, y + 1, z);
                for (int i = 0; i < 4; i++) {
                    Direction d = H[i];
                    int n = nb(src, x, y, z, d), nid = n >> 4;
                    con[i] = isWall(nid) || isPane(nid) || (isGate(nid) && gateConnects(n, d)) || isFence(nid) && false
                            || (!cannotConnect(nid) && solid(n, d.getOpposite()));
                }
                boolean aboveSolid = solid(above, Direction.DOWN) || isWall(above >> 4);
                for (int i = 0; i < 4; i++) {
                    WallShape ws = con[i] ? (aboveSolid ? WallShape.TALL : WallShape.LOW) : WallShape.NONE;
                    s = s.with(wallProp(H[i]), ws);
                }
                boolean straightNS = con[0] && con[2] && !con[1] && !con[3];
                boolean straightEW = con[1] && con[3] && !con[0] && !con[2];
                boolean up = !(straightNS || straightEW) || (above >> 4) != 0 && (aboveSolid && !isWall(above >> 4) ? false : isWall(above >> 4));
                if (straightNS || straightEW) up = isWall(above >> 4) && wallHasPost(src, x, y + 1, z);
                return s.with(WallBlock.UP, up);
            }
            case "stairs":
                return s.with(StairsBlock.SHAPE, stairShape(src, x, y, z, s));
            case "redstone_wire":
                return wire(src, x, y, z, s);
            case "tripwire": {
                for (Direction d : H) {
                    int nid = nb(src, x, y, z, d) >> 4;
                    s = s.with(prop(d), nid == 132 || nid == 131);
                }
                return s;
            }
            case "vine":
                if ((meta & 15) == 0) return s;
                return s.with(VineBlock.UP, solid(src.peState(x, y + 1, z), Direction.DOWN));
            case "fire": {
                int below = src.peState(x, y - 1, z);
                if (solid(below, Direction.UP) || (below >> 4) != 0) return s;
                for (Direction d : H) {
                    int n = nb(src, x, y, z, d);
                    s = s.with(prop(d), (n >> 4) != 0 && solid(n, d.getOpposite()));
                }
                int above = src.peState(x, y + 1, z);
                return s.with(Properties.UP, (above >> 4) != 0);
            }
            case "snowy": {
                int aid = src.peState(x, y + 1, z) >> 4;
                return s.with(Properties.SNOWY, aid == 78 || aid == 80);
            }
            case "fence_gate": {
                Direction f = s.get(FenceGateBlock.FACING);
                int l = nb(src, x, y, z, f.rotateYClockwise()) >> 4, r = nb(src, x, y, z, f.rotateYCounterclockwise()) >> 4;
                return s.with(FenceGateBlock.IN_WALL, isWall(l) || isWall(r));
            }
            case "door": {
                if ((meta & 8) == 0) {
                    int up = src.peState(x, y + 1, z);
                    if ((up >> 4) == id && (up & 8) != 0) {
                        s = s.with(DoorBlock.HINGE, (up & 1) != 0 ? DoorHinge.RIGHT : DoorHinge.LEFT)
                             .with(DoorBlock.POWERED, (up & 2) != 0);
                    }
                    return s;
                } else {
                    int dn = src.peState(x, y - 1, z);
                    if ((dn >> 4) == id && (dn & 8) == 0) {
                        BlockState lower = BASE[dn & 4095];
                        if (lower.contains(DoorBlock.FACING)) s = s.with(DoorBlock.FACING, lower.get(DoorBlock.FACING)).with(DoorBlock.OPEN, lower.get(DoorBlock.OPEN));
                    }
                    return s;
                }
            }
            case "double_plant": {
                if ((meta & 8) != 0) {
                    int dn = src.peState(x, y - 1, z);
                    if ((dn >> 4) == id && (dn & 8) == 0) {
                        BlockState lower = BASE[dn & 4095];
                        return lower.getBlock().getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.UPPER);
                    }
                }
                return s;
            }
            case "flower_pot": {
                Tag.CompoundTag t = src.tile(x, y, z);
                if (t == null) return s;
                Block pb = potBlock(t.getInt("item", 0), t.getInt("data", 0));
                return pb == null ? s : pb.getDefaultState();
            }
            case "skull": {
                Tag.CompoundTag t = src.tile(x, y, z);
                int type = t == null ? 0 : t.getInt("SkullType", 0);
                int rot = t == null ? 0 : t.getInt("Rot", 0) & 15;
                String[] names = {"skeleton", "wither_skeleton", "zombie", "player", "creeper"};
                String n = type >= 0 && type < names.length ? names[type] : "skeleton";
                int m = meta & 7;
                if (m >= 2 && m <= 5) {
                    Direction d = m == 2 ? Direction.NORTH : m == 3 ? Direction.SOUTH : m == 4 ? Direction.WEST : Direction.EAST;
                    return Registries.BLOCK.get(new Identifier("minecraft", n + "_wall_skull")).getDefaultState().with(WallSkullBlock.FACING, d);
                }
                return Registries.BLOCK.get(new Identifier("minecraft", n + "_skull")).getDefaultState().with(SkullBlock.ROTATION, rot);
            }
            default: {
                if ((id == 104 || id == 105) && meta == 7) {
                    int fruit = id == 104 ? 86 : 103;
                    for (Direction d : H) {
                        int nid = nb(src, x, y, z, d) >> 4;
                        if (nid == fruit || (id == 104 && nid == 91)) {
                            Block att = id == 104 ? Blocks.ATTACHED_PUMPKIN_STEM : Blocks.ATTACHED_MELON_STEM;
                            return att.getDefaultState().with(AttachedStemBlock.FACING, d);
                        }
                    }
                }
                return s;
            }
        }
    }

    private static boolean wallHasPost(Source src, int x, int y, int z) {
        int c = 0;
        for (Direction d : H) if (isWall(nb(src, x, y, z, d) >> 4)) c++;
        return c != 2;
    }

    private static boolean gateConnects(int gateState, Direction dirToGate) {
        BlockState g = BASE[gateState & 4095];
        if (!g.contains(FenceGateBlock.FACING)) return false;
        return g.get(FenceGateBlock.FACING).getAxis() == dirToGate.rotateYClockwise().getAxis();
    }

    private static net.minecraft.state.property.BooleanProperty prop(Direction d) {
        return switch (d) { case NORTH -> Properties.NORTH; case EAST -> Properties.EAST; case SOUTH -> Properties.SOUTH; default -> Properties.WEST; };
    }
    private static net.minecraft.state.property.EnumProperty<WallShape> wallProp(Direction d) {
        return switch (d) { case NORTH -> WallBlock.NORTH_SHAPE; case EAST -> WallBlock.EAST_SHAPE; case SOUTH -> WallBlock.SOUTH_SHAPE; default -> WallBlock.WEST_SHAPE; };
    }

    private static boolean isStairsState(BlockState s) { return s.getBlock() instanceof StairsBlock; }

    private static StairShape stairShape(Source src, int x, int y, int z, BlockState state) {
        Direction dir = state.get(StairsBlock.FACING);
        BlockHalf half = state.get(StairsBlock.HALF);
        BlockState front = BASE[nb(src, x, y, z, dir) & 4095];
        if (isStairsState(front) && front.get(StairsBlock.HALF) == half) {
            Direction d2 = front.get(StairsBlock.FACING);
            if (d2.getAxis() != dir.getAxis() && differentOrientation(src, x, y, z, state, d2.getOpposite()))
                return d2 == dir.rotateYCounterclockwise() ? StairShape.OUTER_LEFT : StairShape.OUTER_RIGHT;
        }
        BlockState back = BASE[nb(src, x, y, z, dir.getOpposite()) & 4095];
        if (isStairsState(back) && back.get(StairsBlock.HALF) == half) {
            Direction d3 = back.get(StairsBlock.FACING);
            if (d3.getAxis() != dir.getAxis() && differentOrientation(src, x, y, z, state, d3))
                return d3 == dir.rotateYCounterclockwise() ? StairShape.INNER_LEFT : StairShape.INNER_RIGHT;
        }
        return StairShape.STRAIGHT;
    }

    private static boolean differentOrientation(Source src, int x, int y, int z, BlockState state, Direction d) {
        BlockState o = BASE[nb(src, x, y, z, d) & 4095];
        return !isStairsState(o) || o.get(StairsBlock.FACING) != state.get(StairsBlock.FACING) || o.get(StairsBlock.HALF) != state.get(StairsBlock.HALF);
    }

    private static boolean isRedstoneComponent(int st, Direction fromWire) {
        int id = st >> 4;
        switch (id) {
            case 55: case 75: case 76: case 69: case 77: case 143: case 70: case 72: case 147: case 148:
            case 152: case 28: case 151: case 178: case 131: case 146: case 149: case 150:
                return true;
            case 93: case 94: {
                BlockState r = BASE[st & 4095];
                if (!r.contains(RepeaterBlock.FACING)) return false;
                return r.get(RepeaterBlock.FACING).getAxis() == fromWire.getAxis();
            }
            default: return false;
        }
    }

    private static BlockState wire(Source src, int x, int y, int z, BlockState s) {
        int above = src.peState(x, y + 1, z);
        boolean aboveSolid = (SOLID_FACES[above & 4095] & 0x3F) == 0x3F;
        WireConnection[] c = new WireConnection[4];
        int count = 0;
        for (int i = 0; i < 4; i++) {
            Direction d = H[i];
            int n = nb(src, x, y, z, d);
            WireConnection w = WireConnection.NONE;
            if (isRedstoneComponent(n, d)) w = WireConnection.SIDE;
            else {
                boolean nSolid = (SOLID_FACES[n & 4095] & 0x3F) == 0x3F;
                if (nSolid && !aboveSolid && (src.peState(x + d.getOffsetX(), y + 1, z + d.getOffsetZ()) >> 4) == 55) w = WireConnection.UP;
                else if (!nSolid && (src.peState(x + d.getOffsetX(), y - 1, z + d.getOffsetZ()) >> 4) == 55) w = WireConnection.SIDE;
            }
            c[i] = w;
            if (w != WireConnection.NONE) count++;
        }
        if (count == 0) { for (int i = 0; i < 4; i++) c[i] = WireConnection.SIDE; }
        else if (count == 1) {
            for (int i = 0; i < 4; i++) if (c[i] != WireConnection.NONE) { int o = (i + 2) & 3; if (c[o] == WireConnection.NONE) c[o] = WireConnection.SIDE; break; }
        }
        return s.with(RedstoneWireBlock.WIRE_CONNECTION_NORTH, c[0]).with(RedstoneWireBlock.WIRE_CONNECTION_EAST, c[1])
                .with(RedstoneWireBlock.WIRE_CONNECTION_SOUTH, c[2]).with(RedstoneWireBlock.WIRE_CONNECTION_WEST, c[3]);
    }

    /** Таблица 4.4: содержимое горшка (item, data) → Java potted_*. null — пустой горшок. */
    public static Block potBlock(int item, int data) {
        String n = switch (item) {
            case 6 -> switch (data) { case 0 -> "potted_oak_sapling"; case 1 -> "potted_spruce_sapling"; case 2 -> "potted_birch_sapling";
                case 3 -> "potted_jungle_sapling"; case 4 -> "potted_acacia_sapling"; case 5 -> "potted_dark_oak_sapling"; default -> null; };
            case 31 -> data == 2 ? "potted_fern" : null;
            case 32 -> "potted_dead_bush";
            case 37 -> "potted_dandelion";
            case 38 -> switch (data) { case 0 -> "potted_poppy"; case 1 -> "potted_blue_orchid"; case 2 -> "potted_allium"; case 3 -> "potted_azure_bluet";
                case 4 -> "potted_red_tulip"; case 5 -> "potted_orange_tulip"; case 6 -> "potted_white_tulip"; case 7 -> "potted_pink_tulip";
                case 8 -> "potted_oxeye_daisy"; default -> null; };
            case 39 -> "potted_brown_mushroom";
            case 40 -> "potted_red_mushroom";
            case 81 -> "potted_cactus";
            default -> null;
        };
        return n == null ? null : Registries.BLOCK.get(new Identifier("minecraft", n));
    }

    public static boolean isReady() { return ready; }
}
