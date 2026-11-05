package dev.nullclient.mixin;

import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces polled keyboard input with the arbitrated {@link InputOverride} while an AI task or macro runs. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void nullclient$override(CallbackInfo ci) {
        if (!InputOverride.active()) {
            return;
        }
        int a = InputOverride.actions();
        boolean forward = InputOverride.has(a, InputOverride.FORWARD);
        boolean back = InputOverride.has(a, InputOverride.BACK);
        boolean left = InputOverride.has(a, InputOverride.LEFT);
        boolean right = InputOverride.has(a, InputOverride.RIGHT);
        boolean jump = InputOverride.has(a, InputOverride.JUMP);
        boolean sneak = InputOverride.has(a, InputOverride.SNEAK);
        boolean sprint = InputOverride.has(a, InputOverride.SPRINT);
        this.keyPresses = new Input(forward, back, left, right, jump, sneak, sprint);
        float fwd = forward == back ? 0.0f : (forward ? 1.0f : -1.0f);
        float strafe = left == right ? 0.0f : (left ? 1.0f : -1.0f);
        this.moveVector = new Vec2(strafe, fwd).normalized();
    }
}