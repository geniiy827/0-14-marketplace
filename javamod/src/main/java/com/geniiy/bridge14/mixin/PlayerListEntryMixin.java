package com.geniiy.bridge14.mixin;

import com.geniiy.bridge14.SkinStore;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Скины игроков с сервера 0.14 вместо стандартных Стива/Алекс. */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Shadow public abstract GameProfile getProfile();

    @Inject(method = "getSkinTexture", at = @At("HEAD"), cancellable = true)
    private void bridge14$skin(CallbackInfoReturnable<Identifier> cir) {
        Identifier id = SkinStore.texture(getProfile().getId());
        if (id != null) cir.setReturnValue(id);
    }

    @Inject(method = "hasSkinTexture", at = @At("HEAD"), cancellable = true)
    private void bridge14$hasSkin(CallbackInfoReturnable<Boolean> cir) {
        if (SkinStore.has(getProfile().getId())) cir.setReturnValue(true);
    }

    @Inject(method = "getModel", at = @At("HEAD"), cancellable = true)
    private void bridge14$model(CallbackInfoReturnable<String> cir) {
        Boolean slim = SkinStore.slim(getProfile().getId());
        if (slim != null) cir.setReturnValue(slim ? "slim" : "default");
    }
}
