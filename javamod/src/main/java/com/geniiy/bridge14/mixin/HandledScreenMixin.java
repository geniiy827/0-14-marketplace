package com.geniiy.bridge14.mixin;

import com.geniiy.bridge14.Bridge14;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Classic GUI: окна инвентаря и сундуков в цветах 0.14 (бежево-серая панель, тёмные ячейки). */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow @Final protected ScreenHandler handler;

    @Inject(method = "render", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawBackground(Lnet/minecraft/client/gui/DrawContext;FII)V"))
    private void bridge14$tint(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (Bridge14.active && Bridge14.classicGui) RenderSystem.setShaderColor(0.86f, 0.80f, 0.76f, 1f);
    }

    @Inject(method = "render", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawBackground(Lnet/minecraft/client/gui/DrawContext;FII)V", shift = At.Shift.AFTER))
    private void bridge14$slots(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!(Bridge14.active && Bridge14.classicGui)) return;
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        for (Slot s : handler.slots) {
            int sx = x + s.x, sy = y + s.y;
            ctx.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF6F6763);
            ctx.fill(sx, sy, sx + 16, sy + 16, 0xFF3B3735);
        }
    }
}
