package dev.nullclient.ai;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class Say {
    private Say() {
    }

    public static void line(String text) {
        Minecraft.getInstance().gui.hud.getChat().addMessage(Component.literal("[NullAI] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }
}