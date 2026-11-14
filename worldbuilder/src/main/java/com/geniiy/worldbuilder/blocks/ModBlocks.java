package com.geniiy.worldbuilder.blocks;

import net.fabricmc.api.ModInitializer;
import net.minecraft.block.*;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.block.ShapeContext;

/**
 * Собственные блоки мода для блоков PE, которых нет в Java. Регистрируются без предметов
 * (в креатив не попадают) и используются только для показа в редакторе.
 */
public class ModBlocks implements ModInitializer {
    public static Block INVISIBLE_BEDROCK, STONECUTTER, GLOWING_OBSIDIAN, NETHER_REACTOR, INFO_UPDATE, INFO_UPDATE2, RESERVED6, ITEM_FRAME, UNKNOWN;

    public enum ReactorState implements StringIdentifiable {
        NORMAL("normal"), ACTIVE("active"), USED("used");
        private final String n; ReactorState(String n) { this.n = n; }
        public String asString() { return n; }
    }
    public static final EnumProperty<ReactorState> REACTOR_STATE = EnumProperty.of("state", ReactorState.class);

    public static class ReactorBlock extends Block {
        public ReactorBlock(Settings s) { super(s); setDefaultState(getStateManager().getDefaultState().with(REACTOR_STATE, ReactorState.NORMAL)); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> b) { b.add(REACTOR_STATE); }
    }

    public static class FrameBlock extends Block {
        public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
        private static final VoxelShape N = Block.createCuboidShape(2, 2, 15, 14, 14, 16);
        private static final VoxelShape S = Block.createCuboidShape(2, 2, 0, 14, 14, 1);
        private static final VoxelShape W = Block.createCuboidShape(15, 2, 2, 16, 14, 14);
        private static final VoxelShape E = Block.createCuboidShape(0, 2, 2, 1, 14, 14);
        public FrameBlock(Settings s) { super(s); setDefaultState(getStateManager().getDefaultState().with(FACING, net.minecraft.util.math.Direction.NORTH)); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> b) { b.add(FACING); }
        @Override public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
            return switch (state.get(FACING)) { case SOUTH -> S; case WEST -> W; case EAST -> E; default -> N; };
        }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) { return getDefaultState(); }
    }

    private static Block reg(String name, Block b) { return Registry.register(Registries.BLOCK, new Identifier("worldbuilder", name), b); }

    @Override
    public void onInitialize() {
        if (UNKNOWN != null) return;
        AbstractBlock.Settings base = AbstractBlock.Settings.create().strength(-1f).dropsNothing();
        INVISIBLE_BEDROCK = reg("pe_invisible_bedrock", new Block(AbstractBlock.Settings.create().strength(-1f).dropsNothing().nonOpaque()));
        STONECUTTER = reg("pe_stonecutter", new Block(base));
        GLOWING_OBSIDIAN = reg("pe_glowing_obsidian", new Block(AbstractBlock.Settings.create().strength(-1f).dropsNothing().luminance(s -> 12)));
        NETHER_REACTOR = reg("pe_nether_reactor", new ReactorBlock(AbstractBlock.Settings.create().strength(-1f).dropsNothing()));
        INFO_UPDATE = reg("pe_info_update", new Block(base));
        INFO_UPDATE2 = reg("pe_info_update2", new Block(base));
        RESERVED6 = reg("pe_reserved6", new Block(base));
        ITEM_FRAME = reg("item_frame", new FrameBlock(AbstractBlock.Settings.create().strength(-1f).dropsNothing().nonOpaque().noCollision()));
        UNKNOWN = reg("unknown_block", new Block(base));
    }
}
