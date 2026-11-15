package dev.nullclient.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The NullClient menu, drawn entirely by our own code (see {@link Ui}). It opens on a start screen of tabs;
 * tapping a tab opens that page, and Back (or ESC) returns to the start screen. Does not pause the game.
 */
public final class MenuScreen extends Screen {
    private static final List<Page> PAGES = List.of(new AiPage(), new AccountsPage(), new ProxyPage(), new MacrosPage(), new SettingsPage());
    private static final Ui UI = new Ui();
    private static int current = -1;

    public MenuScreen() {
        super(Component.literal("NullClient"));
        current = -1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True while a text box has focus, so a shortcut key does not close the menu mid-typing. */
    public static boolean typing() {
        return UI.typing();
    }

    private void close() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    private void drawBackground() {
        Pics.Pic bg = Pics.get(Theme.slot("background"));
        if (bg != null) {
            UI.pic(bg, 0, 0, this.width, this.height);
            UI.fill(0, 0, this.width, this.height, 0x66000000);
        } else {
            UI.fill(0, 0, this.width, this.height, 0x77000000);
        }
    }

    private void drawStart() {
        int bw = Math.min(150, (this.width - 40) / 2);
        int gap = 8;
        int total = 2 * bw + gap;
        int rows = (PAGES.size() + 1) / 2;
        int panelH = 28 + rows * (34 + gap) + 36;
        int px = (this.width - total) / 2 - 10;
        int py = Math.max(6, (this.height - panelH) / 2);
        UI.panel(px, py, total + 20, panelH, "panel");
        UI.text("NullClient", px + 10, py + 10, Theme.accent());
        for (int i = 0; i < PAGES.size(); i++) {
            int index = i;
            UI.button(PAGES.get(i).title(), px + 10 + (i % 2) * (bw + gap), py + 28 + (i / 2) * (34 + gap), bw, 34, () -> current = index);
        }
        UI.button("Close", px + 10, py + 28 + rows * (34 + gap), total, 20, this::close);
    }

    private void drawPage() {
        UI.panel(6, 6, this.width - 12, this.height - 12, "panel");
        UI.button("< Back", 12, 12, 56, 18, () -> current = -1);
        UI.text(PAGES.get(current).title(), 76, 17, Theme.accent());
        PAGES.get(current).draw(UI, 14, 36, this.width - 28, this.height - 48);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        UI.begin(g, mouseX, mouseY);
        drawBackground();
        if (current < 0) {
            drawStart();
        } else {
            drawPage();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        UI.click((int) event.x(), (int) event.y());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (UI.key(event.key())) {
            return true;
        }
        if (event.key() == 256 && current >= 0) {
            current = -1;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return UI.type(event.codepointAsString()) || super.charTyped(event);
    }
}