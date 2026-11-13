package dev.nullclient.ui;

import dev.nullclient.NullClient;
import dev.nullclient.ai.AIAgent;
import dev.nullclient.ai.AIMemory;
import dev.nullclient.ai.AIRuntime;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.world.Vision;
import dev.nullclient.ai.world.WorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Start/stop the AI, give it a task, and watch what it is doing. */
final class AiTab implements Tab {
    private static String taskText = "";

    private EditBox taskBox;
    private final Button[] status = new Button[5];
    private int panelW;

    @Override
    public String title() {
        return "AI";
    }

    @Override
    public void build(NullScreen ui, int x, int y, int w) {
        panelW = w;
        boolean on = AIRuntime.started();
        int third = (w - 4) / 3;
        ui.button(on ? "AI: ON" : "AI: OFF", x, y, third, () -> {
            NullScreen.run(on ? "off" : "start");
            ui.reopen();
        });
        ui.button("Stop task", x + third + 2, y, third, () -> NullScreen.run("stop"));
        ui.button("Stop all", x + 2 * (third + 2), y, third, () -> NullScreen.run("stopall"));
        y += 24;
        ui.button("Vision: " + (Vision.enabled() ? "ON (only what it sees)" : "OFF (reads all blocks)"), x, y, w, () -> {
            NullScreen.run("vision " + (Vision.enabled() ? "off" : "on"));
            ui.reopen();
        });
        y += 24;
        ui.label("Tell it what to do, e.g. get me a diamond pickaxe", x, y, w);
        y += 22;
        taskBox = ui.box(taskText, x, y, w - 54, "task", 120);
        ui.button("Run", x + w - 50, y, 50, () -> {
            String t = taskBox.getValue().trim();
            if (t.isEmpty()) {
                return;
            }
            taskText = t;
            if (!AIRuntime.started()) {
                NullScreen.run("start");
            }
            NullScreen.run(t);
        });
        y += 26;
        ui.button("Set home", x, y, third, () -> NullScreen.run("sethome"));
        ui.button("Go home", x + third + 2, y, third, () -> NullScreen.run("home"));
        ui.button("Go back", x + 2 * (third + 2), y, third, () -> NullScreen.run("back"));
        y += 28;
        for (int i = 0; i < status.length; i++) {
            status[i] = ui.label("", x, y + i * 22, w);
        }
        refresh(ui);
    }

    @Override
    public void save() {
        if (taskBox != null) {
            taskText = taskBox.getValue();
        }
    }

    @Override
    public boolean typing() {
        return taskBox != null && taskBox.isFocused();
    }

    private void line(int i, String text) {
        if (status[i] != null) {
            status[i].setMessage(Component.literal(NullScreen.clip(text, panelW)));
        }
    }

    @Override
    public void refresh(NullScreen ui) {
        if (status[0] == null) {
            return;
        }
        NullClient c = NullScreen.nc();
        Minecraft mc = Minecraft.getInstance();
        AIAgent a = c.ai().activeAgent();
        line(0, AIRuntime.started()
                ? (a == null ? "AI ready - idle" : "doing: " + a.label() + " [" + a.state() + "]")
                : "AI is OFF - press Run or the button above to start it");
        line(1, a == null ? "-" : a.thought());
        LocalPlayer p = mc.player;
        if (AIRuntime.started() && p != null && mc.level != null) {
            WorldScanner s = WorldScanner.INSTANCE;
            BlockPos o = p.blockPosition();
            line(2, "scan " + (s.settled() ? "done" : "running") + ": logs " + s.find("log", o, 96, 5000).size()
                    + ", coal " + s.find("coal_ore", o, 96, 5000).size()
                    + ", iron " + s.find("iron_ore", o, 96, 5000).size()
                    + ", diamond " + s.find("diamond_ore", o, 96, 5000).size());
            line(3, AIMemory.INSTANCE.summary(mc.level));
        } else {
            line(2, "-");
            line(3, "-");
        }
        List<String> r = RunLog.recent(1);
        line(4, r.isEmpty() ? "log: -" : r.get(0));
    }
}