package dev.nullclient.ui;

import imgui.ImGui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Empty screen whose only job is to release the mouse cursor and route input to ImGui, whose GLFW
 * callbacks run ahead of Minecraft's. Does not pause the game.
 */
public final class NullScreen extends Screen {
    public NullScreen() {
        super(Component.literal("NullClient"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !ImGui.getIO().getWantTextInput();
    }

    @Override
    public void removed() {
        dev.nullclient.NullClient nc = dev.nullclient.NullClient.get();
        if (nc != null && nc.ui() != null) {
            nc.ui().onMenuClosed();
        }
        super.removed();
    }
}