package dev.nullclient.ai.plan;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public final class Inv {
    private Inv() {
    }

    public static String idOf(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    public static Map<String, Integer> snapshot(LocalPlayer p) {
        Map<String, Integer> m = new HashMap<>();
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                m.merge(idOf(s), s.getCount(), Integer::sum);
            }
        }
        return m;
    }

    /** Count of an item, or of all members of a "#tag". */
    public static int count(Map<String, Integer> inv, String token) {
        int n = 0;
        for (String m : Knowledge.get().members(token)) {
            n += inv.getOrDefault(m, 0);
        }
        return n;
    }

    public static int count(LocalPlayer p, String token) {
        return count(snapshot(p), token);
    }
}