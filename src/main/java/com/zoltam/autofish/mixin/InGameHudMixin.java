package com.zoltam.autofish.mixin;

import com.zoltam.autofish.util.ActionBarTap;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "setOverlayMessage(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"))
    private void addon$hookOverlay(Text message, boolean tinted, CallbackInfo ci) {
        if (message != null) ActionBarTap.set(message.getString());
    }
}
