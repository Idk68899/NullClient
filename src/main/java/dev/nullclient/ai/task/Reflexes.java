package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.plan.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Survival reflexes that run before anything else: a water-bucket clutch when falling from a height (and scooping the
 * water back up afterwards), and eating when hungry. Boat and vine clutches are not implemented.
 */
public final class Reflexes {
    private static final List<String> FOOD = List.of(
            "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_salmon", "cooked_cod",
            "baked_potato", "bread", "golden_carrot", "apple", "carrot", "melon_slice", "sweet_berries",
            "beef", "porkchop", "mutton");

    private boolean placed;
    private int pickup;
    private int eatTicks;
    private int eatCooldown;
    private boolean useKeyDown;

    private static int hotbar(LocalPlayer p, String id) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (Inv.idOf(inv.getItem(i)).equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Moves an item from the main inventory into the selected hotbar slot (takes effect next tick). */
    private static void pull(LocalPlayer p, String id) {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = p.getInventory();
        if (mc.gameMode == null) {
            return;
        }
        for (Slot sl : p.inventoryMenu.slots) {
            if (sl.container == inv && sl.getContainerSlot() >= 9 && sl.getContainerSlot() < 36
                    && sl.hasItem() && Inv.idOf(sl.getItem()).equals(id)) {
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, sl.index, inv.getSelectedSlot(), ContainerInput.SWAP, p);
                return;
            }
        }
    }

    public void release(Minecraft mc) {
        if (useKeyDown) {
            mc.options.keyUse.setDown(false);
            useKeyDown = false;
        }
        eatTicks = 0;
    }

    // ------------------------------------------------------------------ clutch

    /** Returns true while it is handling a fall or the water pickup (the player stays put). */
    public boolean clutch(Blackboard bb, Minecraft mc, LocalPlayer p, ClientLevel level) {
        boolean falling = !p.onGround() && !p.isInWater() && !p.isInLava() && !p.getAbilities().flying && !p.isFallFlying()
                && p.getDeltaMovement().y < -0.55 && p.fallDistance > 3.0;
        if (pickup > 0) {
            return pickupStep(mc, p);
        }
        if (!falling) {
            placed = false;
            return false;
        }
        if (placed || mc.gameMode == null) {
            return placed;
        }
        int slot = hotbar(p, "water_bucket");
        if (slot < 0) {
            pull(p, "water_bucket");
            return false;
        }
        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(0.0, -6.5, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK || level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) {
            return false;
        }
        double dist = p.getY() - hit.getLocation().y;
        double speed = -p.getDeltaMovement().y;
        double trigger = Math.min(4.0, Math.max(2.8, speed * 2.2));
        if (dist > trigger) {
            return false;
        }
        p.getInventory().setSelectedSlot(slot);
        p.setXRot(90.0f);
        bb.set(AIKeys.PITCH, 90.0f);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        placed = true;
        pickup = 100;
        RunLog.add("water clutch at " + p.blockPosition().toShortString() + " (fell " + Math.round(p.fallDistance) + " blocks)");
        return true;
    }

    private boolean pickupStep(Minecraft mc, LocalPlayer p) {
        pickup--;
        if (!p.onGround() && !p.isInWater()) {
            return true;
        }
        Inventory inv = p.getInventory();
        int bucket = hotbar(p, "bucket");
        if (bucket < 0 || mc.gameMode == null) {
            pickup = 0;
            return false;
        }
        inv.setSelectedSlot(bucket);
        p.setXRot(90.0f);
        if (pickup % 5 == 0) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        }
        if (Inv.idOf(inv.getItem(inv.getSelectedSlot())).equals("water_bucket")) {
            pickup = 0;
        }
        return true;
    }

    // ------------------------------------------------------------------ eating

    /** Returns true while eating (the player stands still). */
    public boolean eat(Minecraft mc, LocalPlayer p) {
        if (Modes.creative() || mc.gameMode == null) {
            release(mc);
            return false;
        }
        if (eatCooldown > 0) {
            eatCooldown--;
        }
        int food = p.getFoodData().getFoodLevel();
        if (eatTicks > 0) {
            mc.options.keyUse.setDown(true);
            useKeyDown = true;
            if (++eatTicks > 42 || food >= 20) {
                release(mc);
                eatCooldown = 20;
            }
            return true;
        }
        boolean need = food <= 14 || (food < 20 && p.getHealth() < 14.0f);
        if (!need || eatCooldown > 0) {
            return false;
        }
        Inventory inv = p.getInventory();
        for (String id : FOOD) {
            int slot = hotbar(p, id);
            if (slot >= 0) {
                inv.setSelectedSlot(slot);
                eatTicks = 1;
                RunLog.add("eating " + id + " (food " + food + ")");
                return true;
            }
        }
        for (String id : FOOD) {
            pull(p, id);
        }
        eatCooldown = 40;
        return false;
    }
}