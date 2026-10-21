package com.geniiy.bridge14;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * «Строительство как на телефоне»: в 0.14 можно нажать пальцем на боковую грань блока, на краю которого стоишь,
 * и поставить блок себе под ноги, не приседая. Здесь то же самое: прицел ни на что не смотрит, игрок смотрит вниз
 * и стоит на краю — блок ставится к боковой грани опорного блока, ровно туда, куда бы поставил игрок с телефона.
 * Никакой дальности сверх обычной, только одно место под ногами.
 */
public final class ReachAround {
    public static volatile boolean enabled;

    private ReachAround() {}

    public static void toggle() {
        enabled = !enabled;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal(enabled ? "§aСтроительство как на телефоне: ВКЛ" : "§7Строительство как на телефоне: ВЫКЛ"), true);
    }

    /** Попробовать поставить блок; true — поставили (обычное «использование» не нужно). */
    public static boolean tryPlace(MinecraftClient mc) {
        if (!enabled || !Bridge14.active) return false;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null || mc.world == null) return false;
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() != net.minecraft.util.hit.HitResult.Type.MISS) return false;
        if (p.getPitch() < 40f) return false;
        Hand hand = null;
        for (Hand h : Hand.values()) if (p.getStackInHand(h).getItem() instanceof BlockItem) { hand = h; break; }
        if (hand == null) return false;
        BlockHitResult hit = target(mc.world, p);
        if (hit == null) return false;
        ActionResult r = mc.interactionManager.interactBlock(p, hand, hit);
        if (r.isAccepted()) { if (r.shouldSwingHand()) p.swingHand(hand); return true; }
        return false;
    }

    static BlockHitResult target(World w, ClientPlayerEntity p) {
        Vec3d pos = p.getPos();
        BlockPos below = BlockPos.ofFloored(pos.x, pos.y - 0.2, pos.z);
        BlockState bs = w.getBlockState(below);
        if (!bs.isReplaceable()) return null;           // под ногами уже есть блок
        // опорный блок — сосед «пустого» места, на краю которого стоит игрок
        Direction best = null; double bestOver = 0;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos s = below.offset(d);
            if (w.getBlockState(s).isReplaceable()) continue;
            if (w.getBlockState(s).getCollisionShape(w, s).isEmpty()) continue;
            // насколько тело игрока заходит на опорный блок (ширина игрока 0.6)
            double over;
            switch (d) {
                case NORTH: over = (s.getZ() + 1) - (pos.z - 0.3); break;
                case SOUTH: over = (pos.z + 0.3) - s.getZ(); break;
                case WEST: over = (s.getX() + 1) - (pos.x - 0.3); break;
                default: over = (pos.x + 0.3) - s.getX(); break;
            }
            if (over > 0 && over > bestOver) { bestOver = over; best = d; }
        }
        if (best == null) return null;
        BlockPos support = below.offset(best);
        Direction face = best.getOpposite();
        Vec3d hitPos = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, 0, face.getOffsetZ() * 0.5);
        if (p.getEyePos().squaredDistanceTo(hitPos) > 4.5 * 4.5) return null;
        return new BlockHitResult(hitPos, face, support, false);
    }
}
