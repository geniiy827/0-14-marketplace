package com.geniiy.bridge14.mixin;

import com.geniiy.bridge14.Bridge14;
import com.geniiy.bridge14.PePauseScreen;
import com.geniiy.bridge14.ReachAround;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Shadow private int itemUseCooldown;

    /** Мобильный режим строительства: ставим блок в воздух у видимой грани, если прицел ни на что не смотрит. */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void bridge14$reachAround(CallbackInfo ci) {
        if (ReachAround.tryPlace((MinecraftClient) (Object) this)) {
            itemUseCooldown = 4;
            ci.cancel();
        }
    }

    /** Меню паузы в стиле 0.14 сразу, без мелькания обычного. */
    @Inject(method = "openGameMenu", at = @At("HEAD"), cancellable = true)
    private void bridge14$pause(boolean pauseOnly, CallbackInfo ci) {
        MinecraftClient mc = (MinecraftClient) (Object) this;
        if (Bridge14.active && mc.world != null) {
            mc.setScreen(new PePauseScreen());
            ci.cancel();
        }
    }
}
