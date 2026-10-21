package com.geniiy.bridge14.mixin;

import com.geniiy.bridge14.ReachAround;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Строительство как на телефоне»: ставим блок под ноги, если прицел ни на что не смотрит. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Shadow private int itemUseCooldown;

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void bridge14$reachAround(CallbackInfo ci) {
        if (ReachAround.tryPlace((MinecraftClient) (Object) this)) {
            itemUseCooldown = 4;
            ci.cancel();
        }
    }
}
