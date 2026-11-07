package dev.nullclient.ai;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

public final class Say {
    /** When false, only important messages reach chat; the action bar still shows live status. */
    public static volatile boolean verbose;

    private Say() {
    }

    public static void line(String text) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        p.sendSystemMessage(Component.literal("[NullAI] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    /** Narration: only shown when ".ai talk" is on. */
    public static void talk(String text) {
        if (verbose) {
            line(text);
        }
    }
}