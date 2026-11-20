package dev.nullclient.gui;

import dev.nullclient.ai.AIAgent;
import dev.nullclient.core.script.ScriptRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** A plain black screen with a few lines of status and a way back. Does not pause the game. */
final class BlackoutScreen extends Screen {
    private final Ui ui = new Ui();

    BlackoutScreen() {
        super(Component.literal("Screen off"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ScreenSaver.wake();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ui.begin(g, mouseX, mouseY);
        ui.fill(0, 0, this.width, this.height, 0xFF000000);
        int x = Math.max(8, this.width / 2 - 125);
        int y = Math.max(8, this.height / 2 - 50);
        ui.text("Screen off - saving resources", x, y, Theme.accent());
        y += 14;
        AIAgent a = Ui.nc().ai().activeAgent();
        String what = a != null ? a.label() + " - " + a.thought() : ScriptRunner.running() ? ScriptRunner.status() : "nothing is running";
        ui.label(what, x, y, 250, Theme.TEXT);
        y += 12;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            ui.label("HP " + Math.round(p.getHealth()) + "   food " + p.getFoodData().getFoodLevel() + "   at " + p.blockPosition().toShortString(), x, y, 250, Theme.MUTED);
            y += 12;
        }
        ui.label(ScreenSaver.hookRan ? "world drawing is skipped" : "render hook not active - only the display is black", x, y, 250,
                ScreenSaver.hookRan ? Theme.GOOD : Theme.WARN);
        y += 18;
        ui.button("Screen on", x, y, 120, 22, ScreenSaver::wake);
        ui.button("Stop task + screen on", x + 126, y, 124, 22, () -> {
            Ui.cmd("stopall");
            ScreenSaver.wake();
        });
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        ui.click((int) event.x(), (int) event.y());
        return true;
    }
}