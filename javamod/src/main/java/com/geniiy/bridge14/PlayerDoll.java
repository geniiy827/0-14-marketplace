package com.geniiy.bridge14;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/** Объёмная «кукла» игрока со своим скином для меню (без мира и без сущности). */
public final class PlayerDoll {
    private static PlayerEntityModel<LivingEntity> wide, slim;

    private PlayerDoll() {}

    private static PlayerEntityModel<LivingEntity> model(boolean thin) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (wide == null) wide = new PlayerEntityModel<>(mc.getEntityModelLoader().getModelPart(EntityModelLayers.PLAYER), false);
        if (slim == null) slim = new PlayerEntityModel<>(mc.getEntityModelLoader().getModelPart(EntityModelLayers.PLAYER_SLIM), true);
        return thin ? slim : wide;
    }

    /**
     * @param feetY экранная Y ступней; size — пикселей на блок (рост куклы ≈ 2×size)
     * @param yaw   поворот тела, градусы; lookX/lookY — куда смотрит голова (-1..1)
     */
    public static void draw(DrawContext ctx, int cx, int feetY, int size, float yaw, float lookX, float lookY, Identifier tex, boolean thin, float time) {
        if (tex == null) return;
        PlayerEntityModel<LivingEntity> m;
        try { m = model(thin); } catch (Throwable t) { return; }
        m.child = false; m.sneaking = false; m.riding = false;
        resetPose(m, lookX, lookY, time);
        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(cx, feetY, 150);
        ms.scale(size, size, -size);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        ms.translate(0, -1.5, 0);
        DiffuseLighting.method_34742();
        VertexConsumer vc = ctx.getVertexConsumers().getBuffer(RenderLayer.getEntityTranslucent(tex));
        m.render(ms, vc, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
        ctx.draw();
        DiffuseLighting.enableGuiDepthLighting();
        ms.pop();
    }

    private static void resetPose(PlayerEntityModel<LivingEntity> m, float lookX, float lookY, float t) {
        for (ModelPart p : new ModelPart[]{m.head, m.hat, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg}) p.resetTransform();
        m.head.yaw = lookX * 0.7f;
        m.head.pitch = -lookY * 0.5f;
        float swing = (float) Math.sin(t * 0.08f) * 0.06f;
        m.rightArm.roll = 0.06f + swing;
        m.leftArm.roll = -0.06f - swing;
        m.rightArm.pitch = (float) Math.sin(t * 0.05f) * 0.08f;
        m.leftArm.pitch = -m.rightArm.pitch;
        m.hat.copyTransform(m.head);
        m.jacket.copyTransform(m.body);
        m.rightSleeve.copyTransform(m.rightArm);
        m.leftSleeve.copyTransform(m.leftArm);
        m.rightPants.copyTransform(m.rightLeg);
        m.leftPants.copyTransform(m.leftLeg);
    }
}
