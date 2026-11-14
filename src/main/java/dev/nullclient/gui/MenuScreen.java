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
 * The NullClient menu, drawn entirely by our own code (see {@link Ui}) on top of Minecraft's screen system.
 * Sidebar on the left, the open page on the right. Does not pause the game.
 */
public final class MenuScreen extends Screen {
    private static final List<Page> PAGES = List.of(new AiPage(), new AccountsPage(), new ProxyPage(), new MacrosPage(), new SettingsPage());
    private static final Ui UI = new Ui();
    private static int current;

    public MenuScreen() {
        super(Component.literal("NullClient"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True while a text box has focus, so a shortcut key does not close the menu mid-typing. */
    public static boolean typing() {
        return UI.typing();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        UI.begin(g, mouseX, mouseY);
        Pics.Pic bg = Pics.get(Theme.slot("background"));
        if (bg != null) {
            UI.pic(bg, 0, 0, this.width, this.height);
            UI.fill(0, 0, this.width, this.height, 0x66000000);
        } else {
            UI.fill(0, 0, this.width, this.height, 0x77000000);
        }
        int sideW = Math.min(88, this.width / 4);
        UI.panel(6, 6, sideW, this.height - 12, "sidebar");
        UI.text("NullClient", 12, 12, Theme.accent());
        for (int i = 0; i < PAGES.size(); i++) {
            int index = i;
            String title = (i == current ? "> " : "") + PAGES.get(i).title();
            UI.button(title, 10, 28 + i * 22, sideW - 8, 18, () -> current = index);
        }
        UI.button("Close", 10, this.height - 30, sideW - 8, 18, () -> Minecraft.getInstance().gui.setScreen(null));
        int cx = sideW + 12;
        int cw = this.width - cx - 6;
        UI.panel(cx, 6, cw, this.height - 12, "panel");
        PAGES.get(current).draw(UI, cx + 8, 14, cw - 16, this.height - 28);
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
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return UI.type(event.codepointAsString()) || super.charTyped(event);
    }
}