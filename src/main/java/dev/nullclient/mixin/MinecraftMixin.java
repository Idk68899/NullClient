package dev.nullclient.mixin;

import dev.nullclient.NullClient;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the ImGui menu at the end of each rendered frame (26.x has no Window#updateDisplay). */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "renderFrame", at = @At("TAIL"), require = 0)
    private void nullclient$renderUi(CallbackInfo ci) {
        InputOverride.uiHookRan = true;
        NullClient nc = NullClient.get();
        if (nc != null && nc.ui() != null) {
            nc.ui().renderFrame();
        }
    }
}