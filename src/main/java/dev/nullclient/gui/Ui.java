package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.event.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;

/**
 * Immediate-mode drawing and input for the menu. Everything is drawn here with plain rectangles, text and
 * pictures; clickable areas are recorded while drawing and used for the next click.
 */
final class Ui {
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private GuiGraphicsExtractor g;
    private Font font;
    private int mx;
    private int my;
    private List<Hit> building = new ArrayList<>();
    private List<Hit> live = new ArrayList<>();
    private Field focus;
    long frame;

    static NullClient nc() {
        return NullClient.get();
    }

    /** Runs a ".ai ..." command as if typed in chat. */
    static void cmd(String text) {
        NullClient c = nc();
        if (c != null) {
            c.bus().post(new Events.ChatSend(".ai " + text));
        }
    }

    void begin(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        this.g = graphics;
        this.font = Minecraft.getInstance().font;
        this.mx = mouseX;
        this.my = mouseY;
        this.live = building;
        this.building = new ArrayList<>();
        this.frame++;
    }

    // ------------------------------------------------------------------ input

    void click(int x, int y) {
        focus = null;
        for (int i = live.size() - 1; i >= 0; i--) {
            Hit h = live.get(i);
            if (h.contains(x, y)) {
                h.action().run();
                return;
            }
        }
    }

    boolean typing() {
        return focus != null;
    }

    /** Returns true if the key was used by a focused text box. */
    boolean key(int key) {
        if (focus == null) {
            return false;
        }
        if (key == 259) {
            if (!focus.text.isEmpty()) {
                focus.text = focus.text.substring(0, focus.text.length() - 1);
            }
        } else if (key == 257 || key == 335) {
            if (focus.onEnter != null) {
                focus.onEnter.run();
            }
        } else if (key == 256) {
            focus = null;
        }
        return true;
    }

    boolean type(String s) {
        if (focus == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c != 127 && focus.text.length() < focus.max) {
                focus.text += c;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing

    boolean hover(int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    void fill(int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    void text(String s, int x, int y, int color) {
        g.text(font, s, x, y, color);
    }

    /** Text cut to fit the width. */
    void label(String s, int x, int y, int w, int color) {
        g.text(font, font.plainSubstrByWidth(s, w), x, y, color);
    }

    void pic(Pics.Pic p, int x, int y, int w, int h) {
        g.blit(RenderPipelines.GUI_TEXTURED, p.at(System.currentTimeMillis()), x, y, 0f, 0f, w, h, p.w(), p.h(), p.w(), p.h());
    }

    void panel(int x, int y, int w, int h, String slot) {
        fill(x, y, w, h, Theme.panel());
        Pics.Pic p = Pics.get(Theme.slot(slot));
        if (p != null) {
            pic(p, x, y, w, h);
            fill(x, y, w, h, 0x880A0D16);
        }
        g.outline(x, y, w, h, Theme.accent());
    }

    void button(String label, int x, int y, int w, int h, Runnable action) {
        boolean hot = hover(x, y, w, h);
        fill(x, y, w, h, 0xAA101826);
        Pics.Pic p = Pics.get(Theme.slot("button"));
        if (p != null) {
            pic(p, x, y, w, h);
            fill(x, y, w, h, 0x77101826);
        }
        if (hot) {
            fill(x, y, w, h, (0x55 << 24) | (Theme.accent() & 0xFFFFFF));
        }
        g.outline(x, y, w, h, hot ? Theme.accent() : 0xFF3A4660);
        String shown = font.plainSubstrByWidth(label, Math.max(8, w - 6));
        g.text(font, shown, x + (w - font.width(shown)) / 2, y + (h - 8) / 2, Theme.TEXT);
        building.add(new Hit(x, y, w, h, action));
    }

    void field(Field f, int x, int y, int w, int h) {
        boolean has = focus == f;
        fill(x, y, w, h, 0xCC0A0F1A);
        g.outline(x, y, w, h, has ? Theme.accent() : 0xFF3A4660);
        String shown = font.plainSubstrByWidth(f.text, w - 8, true);
        if (f.text.isEmpty() && !has) {
            g.text(font, font.plainSubstrByWidth(f.hint, w - 8), x + 4, y + (h - 8) / 2, Theme.MUTED);
        } else {
            g.text(font, shown, x + 4, y + (h - 8) / 2, Theme.TEXT);
            if (has && (frame / 10) % 2 == 0) {
                g.text(font, "_", x + 4 + font.width(shown), y + (h - 8) / 2, Theme.accent());
            }
        }
        building.add(new Hit(x, y, w, h, () -> focus = f));
    }
}