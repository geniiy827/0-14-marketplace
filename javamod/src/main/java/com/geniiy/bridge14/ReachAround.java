package com.geniiy.bridge14;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Мобильный режим строительства: как на телефоне можно «ткнуть» в воздух рядом с гранью блока, которую видно,
 * и блок встанет туда. Прицел ни на что не смотрит → идём по лучу взгляда (в пределах обычной дальности)
 * и берём первую пустую клетку, у которой есть видимая с нашей стороны грань соседнего блока.
 */
public final class ReachAround {
    public static volatile boolean enabled;
    /** Куда сейчас встанет блок (для обводки), или null. */
    public static volatile BlockPos preview;

    private ReachAround() {}

    public static String label() { return "Мобильный режим строительства: " + (enabled ? "ВКЛ" : "ВЫКЛ"); }

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) preview = null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal((enabled ? "§a" : "§7") + label()), true);
    }

    private static Hand blockHand(ClientPlayerEntity p) {
        for (Hand h : Hand.values()) if (p.getStackInHand(h).getItem() instanceof BlockItem) return h;
        return null;
    }

    private static boolean usable(MinecraftClient mc) {
        if (!enabled || !Bridge14.active || mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        return mc.crosshairTarget == null || mc.crosshairTarget.getType() == HitResult.Type.MISS;
    }

    /** Каждый тик: обновить обводку. */
    public static void tick(MinecraftClient mc) {
        if (!usable(mc) || blockHand(mc.player) == null) { preview = null; return; }
        BlockHitResult h = target(mc.world, mc.player, mc.interactionManager.getReachDistance());
        preview = h == null ? null : h.getBlockPos().offset(h.getSide());
    }

    /** Поставить блок; true — поставили (обычное «использование» не нужно). */
    public static boolean tryPlace(MinecraftClient mc) {
        if (!usable(mc)) return false;
        ClientPlayerEntity p = mc.player;
        Hand hand = blockHand(p);
        if (hand == null) return false;
        BlockHitResult hit = target(mc.world, p, mc.interactionManager.getReachDistance());
        if (hit == null) return false;
        ActionResult r = mc.interactionManager.interactBlock(p, hand, hit);
        if (r.isAccepted()) { if (r.shouldSwingHand()) p.swingHand(hand); return true; }
        return false;
    }

    /**
     * Ищем по лучу взгляда пустые клетки, приставленные к грани блока, и берём ту грань, мимо которой луч проходит
     * ближе всего (а не первую попавшуюся) — так обводка стоит там, куда смотрит прицел, и не «прыгает» под углом.
     * Грань должна быть видна; исключение — край блока под ногами (как на телефоне: строить вперёд, глядя вниз).
     */
    static BlockHitResult target(World w, ClientPlayerEntity p, double reach) {
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1f).normalize();
        Box self = p.getBoundingBox();
        int feetLayer = (int) Math.floor(p.getY() + 0.01) - 1;   // слой блока, на котором стоим
        BlockPos last = null;
        BlockHitResult best = null; double bestScore = Double.MAX_VALUE;
        for (double t = 0.3; t <= reach; t += 0.05) {
            Vec3d pt = eye.add(look.multiply(t));
            BlockPos cell = BlockPos.ofFloored(pt);
            if (cell.equals(last)) continue;
            last = cell;
            BlockState cs = w.getBlockState(cell);
            if (!cs.isReplaceable()) break;                        // луч упёрся в блок
            if (self.intersects(new Box(cell))) continue;           // в себя ставить нельзя
            for (Direction d : Direction.values()) {
                BlockPos s = cell.offset(d);
                BlockState ss = w.getBlockState(s);
                if (ss.isReplaceable() || ss.getCollisionShape(w, s).isEmpty()) continue;
                if (ss.isOf(Blocks.BARRIER) || ss.getRenderType() == BlockRenderType.INVISIBLE) continue; // к невидимому не приставляем
                Direction face = d.getOpposite();
                Vec3d fc = Vec3d.ofCenter(s).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                Vec3d toEye = eye.subtract(fc);
                boolean visible = toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() > 0.01;
                boolean bridge = face.getAxis().isHorizontal() && cell.getY() == feetLayer && s.getY() == feetLayer
                        && horiz(Vec3d.ofCenter(s), p.getPos()) < 2.2;
                if (!visible && !bridge) continue;
                if (eye.squaredDistanceTo(fc) > reach * reach) continue;
                // насколько близко луч проходит мимо центра грани
                Vec3d rel = fc.subtract(eye);
                double along = rel.dotProduct(look);
                if (along <= 0) continue;
                double miss = rel.subtract(look.multiply(along)).length();
                if (miss > 0.95) continue;
                double score = miss + along * 0.03;
                if (score < bestScore) { bestScore = score; best = new BlockHitResult(fc, face, s, false); }
            }
        }
        return best;
    }

    private static double horiz(Vec3d a, Vec3d b) { double dx = a.x - b.x, dz = a.z - b.z; return Math.sqrt(dx * dx + dz * dz); }
}
