package dev.nullclient.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.nullclient.NullClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renders the ImGui frame onto the default framebuffer right before buffers are swapped. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void nullclient$renderUi(CallbackInfo ci) {
        NullClient nc = NullClient.get();
        if (nc != null) {
            nc.ui().renderFrame();
        }
    }
}