package dev.nullclient.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * The NullClient desktop. A "Main Menu" window lists every page with an icon; tapping one opens it as its own
 * window. Windows can be dragged by the title bar, collapsed (v), maximized ([]) and closed (x), and every window
 * scrolls (wheel, scrollbar, or dragging empty space). Drawn entirely by our own code (see {@link Ui}).
 */
public final class MenuScreen extends Screen {
    private static final List<Page> PAGES = List.of(new AiPage(), new AccountsPage(), new PovPage(), new ProxyPage(),
            new MacrosPage(), new LoggerPage(), new ServerPage(), new SettingsPage());
    private static final Page MAIN = new MainPage();
    private static final Ui UI = new Ui();
    private static final List<Win> WINS = new ArrayList<>();

    private static boolean callbacksInstalled;
    private static GLFWScrollCallback previousScroll;

    private static final class Win {
        final Page page;
        final boolean closable;
        int x;
        int y;
        boolean max;
        boolean collapsed;
        double scroll;
        int contentH;

        Win(Page page, int x, int y, boolean closable) {
            this.page = page;
            this.x = x;
            this.y = y;
            this.closable = closable;
        }
    }

    static {
        WINS.add(new Win(MAIN, 6, 6, false));
    }

    public MenuScreen() {
        super(Component.literal("NullClient"));
        installCallbacks();
    }

    /** Hooks the mouse wheel and file drops once, passing every event on to the game's own handlers too. */
    private static void installCallbacks() {
        if (callbacksInstalled) {
            return;
        }
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            return;
        }
        callbacksInstalled = true;
        previousScroll = GLFW.glfwSetScrollCallback(window, (w, xo, yo) -> {
            if (Minecraft.getInstance().gui.screen() instanceof MenuScreen) {
                Ui.onScroll(yo);
            }
            if (previousScroll != null) {
                previousScroll.invoke(w, xo, yo);
            }
        });
        DropImages.install();
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
            WINS.add(new Win(p, 30 + WINS.size() * 16, 24 + WINS.size() * 12, true));
        } else if (WINS.get(WINS.size() - 1) == w) {
            WINS.remove(w);
        } else {
            front(w);
        }
    }

    /** The Main Menu window's content: one icon row per page, then quick actions. */
    private static final class MainPage implements Page {
        @Override
        public String title() {
            return "Main Menu";
        }

        @Override
        public int width() {
            return 214;
        }

        @Override
        public int height() {
            return 44 + ((PAGES.size() + 1) / 2) * 24 + 56;
        }

        @Override
        public void draw(Ui ui, int x, int y, int w, int h) {
            int cw = (w - 4) / 2;
            for (int i = 0; i < PAGES.size(); i++) {
                Page p = PAGES.get(i);
                ui.iconButton(p.title(), p.title(), x + (i % 2) * (cw + 4), y + (i / 2) * 24, cw, 20, find(p) != null, () -> toggle(p));
            }
            int yy = y + ((PAGES.size() + 1) / 2) * 24 + 6;
            ui.button("Stop all AI + macros", x, yy, w, 20, () -> Ui.cmd("stopall"));
            ui.button("Close menu", x, yy + 24, w, 20, () -> Minecraft.getInstance().gui.setScreen(null));
        }
    }

    private int[] rect(Win win) {
        int w = win.max ? this.width - 8 : Math.min(win.page.width(), this.width - 8);
        int full = win.max ? this.height - 8 : Math.min(win.page.height(), this.height - 8);
        int h = win.collapsed ? 18 : full;
        int x = win.max ? 4 : Math.max(0, Math.min(win.x, this.width - 60));
        int y = win.max ? 4 : Math.max(0, Math.min(win.y, this.height - 24));
        if (!win.max) {
            win.x = x;
            win.y = y;
        }
        return new int[]{x, y, w, h};
    }

    private void drawBackground() {
        Pics.Pic bg = Pics.get(Theme.slot("background"));
        if (bg != null) {
            UI.pic(bg, 0, 0, this.width, this.height);
            UI.fill(0, 0, this.width, this.height, 0x66000000);
        } else {
            UI.fill(0, 0, this.width, this.height, 0x55000000);
        }
        Fx.draw(UI, Theme.slot("fx"), this.width, this.height);
    }

    private void drawWin(Win win, boolean wheelTarget) {
        int[] r = rect(win);
        int x = r[0];
        int y = r[1];
        int w = r[2];
        int h = r[3];
        UI.area(x, y, w, h, () -> front(win));
        UI.panel(x, y, w, h, "panel");
        UI.fill(x + 1, y + 1, w - 2, 16, 0xCC160A10);
        UI.button(win.collapsed ? ">" : "v", x + 2, y + 2, 14, 14, () -> win.collapsed = !win.collapsed);
        UI.label(win.page.title(), x + 22, y + 5, w - 90, Theme.accent());
        UI.area(x + 18, y, Math.max(10, w - 18 - (win.closable ? 44 : 22)), 16, () -> {
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
        UI.button("[]", x + w - (win.closable ? 42 : 22), y + 2, 18, 14, () -> win.max = !win.max);
        if (win.closable) {
            UI.button("x", x + w - 22, y + 2, 18, 14, () -> WINS.remove(win));
        }
        if (win.collapsed) {
            return;
        }

        int vx = x + 1;
        int vy = y + 18;
        int vw = w - 2;
        int vh = h - 19;
        int cy = y + 24;
        UI.clip(vx, vy, vw, vh);
        try {
            UI.area(vx, vy, vw, vh, () -> {
                int[] last = {UI.mouseY()};
                UI.startDrag((px, py) -> {
                    win.scroll -= py - last[0];
                    last[0] = py;
                });
            });
            UI.beginContent((int) -win.scroll);
            try {
                win.page.draw(UI, x + 8, cy, w - 22, vh - 12);
            } catch (RuntimeException e) {
                UI.text("error: " + e.getMessage(), x + 8, cy, Theme.BAD);
            }
            int bottom = UI.endContent();
            win.contentH = Math.max(0, bottom - cy + 8);
            int maxScroll = Math.max(0, win.contentH - (vh - 8));
            if (wheelTarget) {
                win.scroll -= UI.scrollDelta() * 24.0;
            }
            win.scroll = Math.max(0.0, Math.min(win.scroll, maxScroll));
            if (maxScroll > 0) {
                int trackX = x + w - 8;
                UI.fill(trackX, vy, 6, vh, 0x33FFFFFF);
                int thumbH = Math.max(18, (int) ((long) vh * vh / (vh + maxScroll)));
                int thumbY = vy + (int) ((vh - thumbH) * (win.scroll / maxScroll));
                UI.fill(trackX, thumbY, 6, thumbH, Theme.accent());
                UI.area(trackX - 4, vy, 12, vh, () -> {
                    win.scroll = Math.max(0.0, Math.min((UI.mouseY() - vy) / (double) vh * maxScroll, maxScroll));
                    UI.startDrag((px, py) -> win.scroll = Math.max(0.0, Math.min((py - vy) / (double) vh * maxScroll, maxScroll)));
                });
            }
        } finally {
            UI.resetClips();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        UI.begin(g, mouseX, mouseY);
        drawBackground();
        Win target = null;
        for (Win w : WINS) {
            int[] r = rect(w);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                target = w;
            }
        }
        for (Win win : new ArrayList<>(WINS)) {
            drawWin(win, win == target);
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
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return UI.type(event.codepointAsString()) || super.charTyped(event);
    }
}