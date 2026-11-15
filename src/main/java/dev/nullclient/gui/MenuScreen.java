package dev.nullclient.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The NullClient desktop: a taskbar of tabs along the bottom, and each tab opens as its own window. Several windows
 * can be open at once; drag by the title bar, [] maximizes, x closes, tapping a window brings it to the front.
 * Windows keep their place between openings. Drawn entirely by our own code (see {@link Ui}).
 */
public final class MenuScreen extends Screen {
    private static final List<Page> PAGES = List.of(new AiPage(), new AccountsPage(), new PovPage(), new ProxyPage(), new MacrosPage(), new SettingsPage());
    private static final Ui UI = new Ui();
    private static final List<Win> WINS = new ArrayList<>();

    private static final class Win {
        final Page page;
        int x;
        int y;
        boolean max;

        Win(Page page, int x, int y) {
            this.page = page;
            this.x = x;
            this.y = y;
        }
    }

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

    private static Win find(Page p) {
        for (Win w : WINS) {
            if (w.page == p) {
                return w;
            }
        }
        return null;
    }

    private static void front(Win w) {
        WINS.remove(w);
        WINS.add(w);
    }

    private static void toggle(Page p) {
        Win w = find(p);
        if (w == null) {
            WINS.add(new Win(p, 24 + WINS.size() * 18, 10 + WINS.size() * 14));
        } else if (WINS.get(WINS.size() - 1) == w) {
            WINS.remove(w);
        } else {
            front(w);
        }
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
            UI.fill(0, 0, this.width, this.height, 0x55000000);
        }
    }

    private void drawWin(Win win) {
        int w = win.max ? this.width - 8 : Math.min(win.page.width(), this.width - 8);
        int h = win.max ? this.height - 30 : Math.min(win.page.height(), this.height - 30);
        int x = win.max ? 4 : Math.max(0, Math.min(win.x, this.width - 60));
        int y = win.max ? 4 : Math.max(0, Math.min(win.y, this.height - 40));
        if (!win.max) {
            win.x = x;
            win.y = y;
        }
        UI.area(x, y, w, h, () -> front(win));
        UI.panel(x, y, w, h, "panel");
        UI.fill(x + 1, y + 1, w - 2, 15, 0xCC0F1626);
        UI.label(win.page.title(), x + 6, y + 5, w - 60, Theme.accent());
        UI.area(x, y, w - 44, 16, () -> {
            front(win);
            if (!win.max) {
                int ox = win.x - UI.mouseX();
                int oy = win.y - UI.mouseY();
                UI.startDrag((px, py) -> {
                    win.x = px + ox;
                    win.y = py + oy;
                });
            }
        });
        UI.button("[]", x + w - 42, y + 1, 18, 14, () -> win.max = !win.max);
        UI.button("x", x + w - 22, y + 1, 18, 14, () -> WINS.remove(win));
        UI.clip(x + 1, y + 17, w - 2, h - 18);
        try {
            win.page.draw(UI, x + 8, y + 22, w - 16, h - 28);
        } catch (RuntimeException e) {
            UI.text("error: " + e.getMessage(), x + 8, y + 24, Theme.BAD);
        } finally {
            UI.unclip();
        }
    }

    private void drawTaskbar() {
        int bh = 20;
        int y0 = this.height - bh - 2;
        UI.fill(0, y0 - 2, this.width, bh + 4, 0xDD070A12);
        int n = PAGES.size();
        int closeW = 50;
        int bw = Math.max(36, (this.width - closeW - 12 - n * 3) / n);
        for (int i = 0; i < n; i++) {
            Page p = PAGES.get(i);
            boolean open = find(p) != null;
            UI.button((open ? "* " : "") + p.title(), 4 + i * (bw + 3), y0, bw, bh, () -> toggle(p));
        }
        UI.button("Close", this.width - closeW - 4, y0, closeW, bh, this::close);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        UI.begin(g, mouseX, mouseY);
        drawBackground();
        if (WINS.isEmpty()) {
            UI.text("Tap a tab below to open a window", Math.max(4, this.width / 2 - 90), this.height / 2 - 4, Theme.MUTED);
        }
        for (Win win : new ArrayList<>(WINS)) {
            drawWin(win);
        }
        drawTaskbar();
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