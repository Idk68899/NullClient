package dev.nullclient.mixin;

import dev.nullclient.gui.ScreenSaver;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips drawing the 3D world while the "screen off" black screen is open. Optional: if the method name differs, nothing happens. */
@Mixin(GameRenderer.class)
public abstract class GameRendererGateMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private void nullclient$gate(CallbackInfo ci) {
        ScreenSaver.hookRan = true;
        if (ScreenSaver.skipWorld()) {
            ci.cancel();
        }
    }
}