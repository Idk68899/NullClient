package dev.nullclient.ui;

import dev.nullclient.NullClient;
import dev.nullclient.ai.AIAgent;
import dev.nullclient.ai.AIMemory;
import dev.nullclient.ai.AIRuntime;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.world.WorldScanner;
import dev.nullclient.core.event.Events;
import dev.nullclient.core.macro.Macro;
import dev.nullclient.core.macro.MacroManager;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The NullClient menu, built only from vanilla widgets (buttons and text boxes) so it works everywhere,
 * including phone launchers. Does not pause the game, so the AI keeps running while it is open.
 */
public final class NullScreen extends Screen {
    private enum Tab {
        AI("AI"), PROFILES("Profiles"), MACROS("Macros");

        final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    private static Tab tab = Tab.AI;
    private static String taskText = "";
    private static String nameText = "";
    private static String macroText = "";
    private static String note = "";

    private EditBox taskBox;
    private EditBox nameBox;
    private EditBox macroBox;
    private EditBox passBox;
    private final Button[] status = new Button[5];
    private int refresh;
    private int left;
    private int panelW;

    public NullScreen() {
        super(Component.literal("NullClient"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True while a text box has focus (so a shortcut key does not close the menu mid-typing). */
    public boolean typing() {
        return (taskBox != null && taskBox.isFocused())
                || (nameBox != null && nameBox.isFocused())
                || (macroBox != null && macroBox.isFocused())
                || (passBox != null && passBox.isFocused());
    }

    // ------------------------------------------------------------------ helpers

    private static NullClient nc() {
        return NullClient.get();
    }

    private static String clip(String s, int px) {
        int max = Math.max(8, px / 6);
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(clip(text, w)), b -> action.run())
                .bounds(x, y, w, 20).build());
    }

    private Button label(String text, int x, int y, int w) {
        Button b = Button.builder(Component.literal(clip(text, w)), ignored -> {
        }).bounds(x, y, w, 20).build();
        b.active = false;
        return addRenderableWidget(b);
    }

    private EditBox box(String value, int x, int y, int w, String name, int max) {
        EditBox e = new EditBox(this.font, x, y, w, 20, Component.literal(name));
        e.setMaxLength(max);
        e.setValue(value);
        return addRenderableWidget(e);
    }

    private void save() {
        if (taskBox != null) {
            taskText = taskBox.getValue();
        }
        if (nameBox != null) {
            nameText = nameBox.getValue();
        }
        if (macroBox != null) {
            macroText = macroBox.getValue();
        }
    }

    private void reopen() {
        save();
        Minecraft.getInstance().gui.setScreen(new NullScreen());
    }

    private static void run(String text) {
        NullClient c = nc();
        if (c != null) {
            c.bus().post(new Events.ChatSend(".ai " + text));
        }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        panelW = Math.min(this.width - 16, 380);
        left = (this.width - panelW) / 2;
        int tw = panelW / Tab.values().length;
        int i = 0;
        for (Tab t : Tab.values()) {
            String text = t == tab ? "[ " + t.title + " ]" : t.title;
            button(text, left + i * tw, 8, tw - 2, () -> {
                save();
                tab = t;
                reopen();
            });
            i++;
        }
        int y = 34;
        switch (tab) {
            case AI -> buildAi(y);
            case PROFILES -> buildProfiles(y);
            case MACROS -> buildMacros(y);
        }
    }

    private void buildAi(int y) {
        boolean on = AIRuntime.started();
        int third = (panelW - 4) / 3;
        button(on ? "AI: ON" : "AI: OFF", left, y, third, () -> {
            run(on ? "off" : "start");
            reopen();
        });
        button("Stop task", left + third + 2, y, third, () -> run("stop"));
        button("Stop all", left + 2 * (third + 2), y, third, () -> run("stopall"));
        y += 24;
        label("Tell it what to do, e.g. get me a diamond pickaxe", left, y, panelW);
        y += 22;
        taskBox = box(taskText, left, y, panelW - 54, "task", 120);
        button("Run", left + panelW - 50, y, 50, () -> {
            String t = taskBox.getValue().trim();
            if (t.isEmpty()) {
                return;
            }
            taskText = t;
            if (!AIRuntime.started()) {
                run("start");
            }
            run(t);
        });
        y += 26;
        button("Set home", left, y, third, () -> run("sethome"));
        button("Go home", left + third + 2, y, third, () -> run("home"));
        button("Go back", left + 2 * (third + 2), y, third, () -> run("back"));
        y += 28;
        for (int i = 0; i < status.length; i++) {
            status[i] = label("", left, y + i * 22, panelW);
        }
        refreshStatus();
    }

    private void buildProfiles(int y) {
        NullClient c = nc();
        if (c.store().isLocked()) {
            label("Profile store is locked. Enter the master password:", left, y, panelW);
            y += 22;
            passBox = box("", left, y, panelW - 64, "password", 128);
            button("Unlock", left + panelW - 60, y, 60, () -> {
                boolean ok = c.store().unlock(passBox.getValue().toCharArray());
                note = ok ? "unlocked" : "wrong password";
                reopen();
            });
            y += 26;
            label(note, left, y, panelW);
            return;
        }
        List<Profile> list = new ArrayList<>(c.profiles().all());
        Profile active = c.profiles().active();
        int shown = Math.min(list.size(), 7);
        if (list.isEmpty()) {
            label("No profiles yet - create one below.", left, y, panelW);
            y += 22;
        }
        for (int i = 0; i < shown; i++) {
            Profile p = list.get(i);
            button(p.name + (p == active ? "  (active)" : ""), left, y, panelW - 26, () -> {
                c.profiles().setActive(p);
                note = "active: " + p.name;
                reopen();
            });
            button("x", left + panelW - 22, y, 22, () -> {
                c.profiles().delete(p);
                note = "deleted " + p.name;
                reopen();
            });
            y += 22;
        }
        if (list.size() > shown) {
            label("... and " + (list.size() - shown) + " more", left, y, panelW);
            y += 22;
        }
        y += 6;
        nameBox = box(nameText, left, y, panelW - 134, "name", 24);
        button("Create", left + panelW - 130, y, 62, () -> {
            try {
                Profile p = c.profiles().create(nameBox.getValue());
                c.profiles().setActive(p);
                nameText = "";
                note = "created " + p.name;
            } catch (RuntimeException ex) {
                note = String.valueOf(ex.getMessage());
            }
            reopen();
        });
        button("Random", left + panelW - 64, y, 64, () -> {
            Profile p = c.profiles().generate();
            c.profiles().setActive(p);
            note = "generated " + p.name;
            reopen();
        });
        y += 26;
        label(note, left, y, panelW);
    }

    private void buildMacros(int y) {
        MacroManager m = nc().macros();
        macroBox = box(macroText, left, y, panelW - 124, "macro name", 32);
        if (m.isRecording()) {
            button("Stop & save", left + panelW - 120, y, 120, () -> {
                m.stopRecording(true);
                note = "saved";
                reopen();
            });
        } else {
            button("Record", left + panelW - 120, y, 120, () -> {
                boolean ok = m.startRecording(macroBox.getValue());
                note = ok ? "recording - close this menu and move" : "need a name, and nothing else running";
                reopen();
            });
        }
        y += 26;
        if (m.isPlaying()) {
            button("Stop playback", left, y, panelW, () -> {
                m.stopPlayback();
                reopen();
            });
            y += 24;
        }
        List<Macro> list = new ArrayList<>(m.all());
        int shown = Math.min(list.size(), 7);
        if (list.isEmpty()) {
            label("No macros yet.", left, y, panelW);
            y += 22;
        }
        for (int i = 0; i < shown; i++) {
            Macro mac = list.get(i);
            button(String.format(Locale.ROOT, "Play: %s (%.1fs)", mac.name, mac.length / 20.0), left, y, panelW - 26, () -> {
                note = m.play(mac.name, false) ? "playing " + mac.name : "cannot play right now";
                reopen();
            });
            button("x", left + panelW - 22, y, 22, () -> {
                m.delete(mac.name);
                reopen();
            });
            y += 22;
        }
        y += 6;
        label(note, left, y, panelW);
    }

    // ------------------------------------------------------------------ live status

    private void setLine(int i, String text) {
        if (status[i] != null) {
            status[i].setMessage(Component.literal(clip(text, panelW)));
        }
    }

    private void refreshStatus() {
        if (status[0] == null) {
            return;
        }
        NullClient c = nc();
        Minecraft mc = Minecraft.getInstance();
        AIAgent a = c.ai().activeAgent();
        setLine(0, AIRuntime.started()
                ? (a == null ? "AI ready - idle" : "doing: " + a.label() + " [" + a.state() + "]")
                : "AI is OFF - press Run or the button above to start it");
        setLine(1, a == null ? "-" : a.thought());
        LocalPlayer p = mc.player;
        if (AIRuntime.started() && p != null && mc.level != null) {
            WorldScanner s = WorldScanner.INSTANCE;
            BlockPos o = p.blockPosition();
            setLine(2, "scan " + (s.settled() ? "done" : "running") + ": logs " + s.find("log", o, 96, 5000).size()
                    + ", coal " + s.find("coal_ore", o, 96, 5000).size()
                    + ", iron " + s.find("iron_ore", o, 96, 5000).size()
                    + ", diamond " + s.find("diamond_ore", o, 96, 5000).size());
            setLine(3, AIMemory.INSTANCE.summary(mc.level));
        } else {
            setLine(2, "-");
            setLine(3, "-");
        }
        List<String> r = RunLog.recent(1);
        setLine(4, r.isEmpty() ? "log: -" : r.get(0));
    }

    @Override
    public void tick() {
        super.tick();
        if (++refresh % 20 == 1) {
            refreshStatus();
        }
    }
}