package dev.nullclient.ai.task;

import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.plan.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Wears armor it is carrying: fills empty armor slots by shift-click, and upgrades a worse piece with a 3-click swap.
 * Order of quality: netherite, diamond, iron, chainmail, golden, leather. Runs every couple of seconds while no menu is open.
 */
public final class Armor {
    public static volatile boolean enabled = true;

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] SUFFIX = {"_helmet", "_chestplate", "_leggings", "_boots"};
    private static final String[] ORDER = {"netherite_", "diamond_", "iron_", "chainmail_", "golden_", "leather_"};
    private static final int ARMOR_MENU_START = 5;

    private static final Deque<int[]> CLICKS = new ArrayDeque<>();
    private static int timer;
    private static int gap;
    private static boolean broken;

    private Armor() {
    }

    private static String id(ItemStack s) {
        return s.isEmpty() ? "" : Inv.idOf(s);
    }

    private static int rank(String id, int slot) {
        if (id.isEmpty() || !id.endsWith(SUFFIX[slot])) {
            return -1;
        }
        for (int i = 0; i < ORDER.length; i++) {
            if (id.startsWith(ORDER[i])) {
                return ORDER.length - i;
            }
        }
        return id.equals("turtle_helmet") ? 1 : -1;
    }

    /** True if the piece is in the inventory or already worn. */
    public static boolean has(LocalPlayer p, String item) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (id(inv.getItem(i)).equals(item)) {
                return true;
            }
        }
        for (EquipmentSlot s : SLOTS) {
            if (id(p.getItemBySlot(s)).equals(item)) {
                return true;
            }
        }
        return false;
    }

    public static void tick(Minecraft mc, LocalPlayer p) {
        if (!enabled || broken || mc.gameMode == null || p.containerMenu != p.inventoryMenu) {
            CLICKS.clear();
            return;
        }
        if (!CLICKS.isEmpty()) {
            if (--gap > 0) {
                return;
            }
            gap = 2;
            int[] c = CLICKS.poll();
            mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, c[0], 0, c[1] == 1 ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, p);
            return;
        }
        if (++timer < 40) {
            return;
        }
        timer = 0;
        Inventory inv = p.getInventory();
        for (int s = 0; s < 4; s++) {
            String worn = id(p.getItemBySlot(SLOTS[s]));
            ItemStack menuStack = p.inventoryMenu.getSlot(ARMOR_MENU_START + s).getItem();
            if (!id(menuStack).equals(worn)) {
                broken = true;
                RunLog.add("armor: the inventory slot layout differs from what I expected - auto-wear turned off");
                return;
            }
            int wornRank = Math.max(0, rank(worn, s));
            Slot bestSlot = null;
            int best = wornRank;
            for (Slot sl : p.inventoryMenu.slots) {
                if (sl.container == inv && sl.getContainerSlot() < 36 && sl.hasItem()) {
                    int r = rank(id(sl.getItem()), s);
                    if (r > best) {
                        best = r;
                        bestSlot = sl;
                    }
                }
            }
            if (bestSlot == null) {
                continue;
            }
            RunLog.add("wearing " + id(bestSlot.getItem()));
            if (worn.isEmpty()) {
                CLICKS.add(new int[]{bestSlot.index, 1});
            } else {
                CLICKS.add(new int[]{bestSlot.index, 0});
                CLICKS.add(new int[]{ARMOR_MENU_START + s, 0});
                CLICKS.add(new int[]{bestSlot.index, 0});
            }
            gap = 1;
            return;
        }
    }
}