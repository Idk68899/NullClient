package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.plan.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Places cheap solid blocks (used for pillaring up). Only whitelisted block items are ever used. */
public final class Placer {
    private static final Set<String> OK = Set.of(
            "cobblestone", "cobbled_deepslate", "dirt", "stone", "netherrack", "blackstone",
            "andesite", "diorite", "granite", "deepslate", "tuff", "sandstone");

    private Placer() {
    }

    private static boolean placeable(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        String id = Inv.idOf(s);
        return OK.contains(id) || id.endsWith("_planks");
    }

    /** Total placeable blocks in the inventory. */
    public static int count(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (placeable(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Puts a placeable block in the selected hotbar slot. Returns true once one is in hand. */
    private static boolean equip(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (placeable(inv.getItem(i))) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        if (mc.gameMode == null) {
            return false;
        }
        for (Slot sl : p.inventoryMenu.slots) {
            if (sl.container == inv && sl.getContainerSlot() >= 9 && sl.getContainerSlot() < 36
                    && sl.hasItem() && placeable(sl.getItem())) {
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, sl.index, inv.getSelectedSlot(), ContainerInput.SWAP, p);
                return false;
            }
        }
        return false;
    }

    /** Places a block into {@code cell} against the top of the block below it. Returns true once the cell is solid. */
    public static boolean placeInto(Blackboard bb, BlockPos cell) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
            return true;
        }
        if (mc.gameMode == null || !equip(p)) {
            return false;
        }
        BlockPos below = cell.below();
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(below).add(0.0, 0.5, 0.0), Direction.UP, below, false));
        p.swing(InteractionHand.MAIN_HAND);
        return false;
    }
}