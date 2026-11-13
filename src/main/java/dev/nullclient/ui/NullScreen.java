package dev.nullclient.ui;

import dev.nullclient.NullClient;
import dev.nullclient.core.event.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The NullClient menu: a sidebar of tabs on the left, the open tab on the right. Built only from vanilla
 * widgets so it works on any device. Does not pause the game, so the AI keeps running while it is open.
 */
public final class NullScreen extends Screen {
    private static final List<Tab> TABS = List.of(new AiTab(), new AccountsTab(), new ProxyTab(), new MacrosTab());
    private static int current;
    private int refreshTicks;

    public NullScreen() {
        super(Component.literal("NullClient"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True while a text box has focus (so a shortcut key does not close the menu mid-typing). */
    public boolean typing() {
        return TABS.get(current).typing();
    }

    // ------------------------------------------------------------------ helpers used by the tabs

    static NullClient nc() {
        return NullClient.get();
    }

    static void run(String text) {
        NullClient c = nc();
        if (c != null) {
            c.bus().post(new Events.ChatSend(".ai " + text));
        }
    }

    static String clip(String s, int px) {
        int max = Math.max(8, px / 6);
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }

    Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(clip(text, w)), b -> action.run())
                .bounds(x, y, w, 20).build());
    }

    Button label(String text, int x, int y, int w) {
        Button b = Button.builder(Component.literal(clip(text, w)), ignored -> {
        }).bounds(x, y, w, 20).build();
        b.active = false;
        return addRenderableWidget(b);
    }

    EditBox box(String value, int x, int y, int w, String name, int max) {
        EditBox e = new EditBox(this.font, x, y, w, 20, Component.literal(name));
        e.setMaxLength(max);
        e.setValue(value);
        return addRenderableWidget(e);
    }

    /** Rebuilds the screen (typed text is kept). */
    void reopen() {
        for (Tab t : TABS) {
            t.save();
        }
        Minecraft.getInstance().gui.setScreen(new NullScreen());
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int sideW = Math.min(92, this.width / 4);
        int x = sideW + 14;
        int w = Math.min(this.width - x - 8, 380);
        for (int i = 0; i < TABS.size(); i++) {
            int index = i;
            button((i == current ? "> " : "") + TABS.get(i).title(), 6, 8 + i * 24, sideW - 6, () -> {
                current = index;
                reopen();
            });
        }
        button("Close", 6, this.height - 28, sideW - 6, () -> Minecraft.getInstance().gui.setScreen(null));
        TABS.get(current).build(this, x, 10, w);
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTicks % 20 == 1) {
            TABS.get(current).refresh(this);
        }
    }
}