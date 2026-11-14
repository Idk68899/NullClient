package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.ai.AIAgent;
import dev.nullclient.ai.AIMemory;
import dev.nullclient.ai.AIRuntime;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.world.Vision;
import dev.nullclient.ai.world.WorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Start/stop the AI, give it a task, and watch what it is doing. */
final class AiPage implements Page {
    private final Field task = new Field(120).hint("get me a diamond pickaxe");
    private final String[] status = {"", "", "", "", ""};

    AiPage() {
        task.onEnter = this::go;
    }

    @Override
    public String title() {
        return "AI";
    }

    private void go() {
        String t = task.text.trim();
        if (t.isEmpty()) {
            return;
        }
        if (!AIRuntime.started()) {
            Ui.cmd("start");
        }
        Ui.cmd(t);
    }

    private void refreshStatus() {
        NullClient c = Ui.nc();
        Minecraft mc = Minecraft.getInstance();
        AIAgent a = c.ai().activeAgent();
        status[0] = AIRuntime.started()
                ? (a == null ? "AI ready - idle" : "doing: " + a.label() + " [" + a.state() + "]")
                : "AI is OFF - press Run or AI: OFF to start it";
        status[1] = a == null ? "-" : a.thought();
        LocalPlayer p = mc.player;
        if (AIRuntime.started() && p != null && mc.level != null) {
            WorldScanner s = WorldScanner.INSTANCE;
            BlockPos o = p.blockPosition();
            status[2] = "scan " + (s.settled() ? "done" : "running") + ": logs " + s.find("log", o, 96, 5000).size()
                    + ", coal " + s.find("coal_ore", o, 96, 5000).size()
                    + ", iron " + s.find("iron_ore", o, 96, 5000).size()
                    + ", diamond " + s.find("diamond_ore", o, 96, 5000).size();
            status[3] = AIMemory.INSTANCE.summary(mc.level);
        } else {
            status[2] = "-";
            status[3] = "-";
        }
        List<String> r = RunLog.recent(1);
        status[4] = r.isEmpty() ? "log: -" : r.get(0);
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        boolean on = AIRuntime.started();
        int third = (w - 8) / 3;
        ui.button(on ? "AI: ON" : "AI: OFF", x, y, third, 20, () -> Ui.cmd(on ? "off" : "start"));
        ui.button("Stop task", x + third + 4, y, third, 20, () -> Ui.cmd("stop"));
        ui.button("Stop all", x + 2 * (third + 4), y, third, 20, () -> Ui.cmd("stopall"));
        y += 26;
        ui.button("Vision: " + (Vision.enabled() ? "ON - only what it sees" : "OFF - reads all blocks"), x, y, w, 20,
                () -> Ui.cmd("vision " + (Vision.enabled() ? "off" : "on")));
        y += 26;
        ui.text("Tell it what to do:", x, y, Theme.MUTED);
        y += 12;
        ui.field(task, x, y, w - 54, 20);
        ui.button("Run", x + w - 50, y, 50, 20, this::go);
        y += 26;
        ui.button("Set home", x, y, third, 20, () -> Ui.cmd("sethome"));
        ui.button("Go home", x + third + 4, y, third, 20, () -> Ui.cmd("home"));
        ui.button("Go back", x + 2 * (third + 4), y, third, 20, () -> Ui.cmd("back"));
        y += 28;
        if (ui.frame % 20 == 0 || status[0].isEmpty()) {
            refreshStatus();
        }
        for (int i = 0; i < status.length; i++) {
            ui.label(status[i], x, y + i * 12, w, i == 1 ? Theme.accent() : Theme.TEXT);
        }
    }
}