package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.ai.AIAgent;
import dev.nullclient.ai.AIMemory;
import dev.nullclient.ai.AIRuntime;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.ai.task.Combat;
import dev.nullclient.ai.task.Guarded;
import dev.nullclient.ai.world.Vision;
import dev.nullclient.ai.world.WorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The AI control panel: start/stop, vitals, hotbar, quick tasks, behaviour toggles, a task box and live status. */
final class AiPage implements Page {
    private static final String[][] QUICK = {
            {"Wood x16", "get oak_log 16"}, {"Cobble x32", "get cobblestone 32"},
            {"Stone pickaxe", "get stone_pickaxe"}, {"Iron pickaxe", "get iron_pickaxe"},
            {"Diamond pickaxe", "get diamond_pickaxe"}, {"Furnace", "get furnace"},
            {"Torches x16", "get torch 16"}, {"Shield", "get shield"}};

    private final Field task = new Field(120).hint("get me a diamond pickaxe");
    private final String[] status = {"", "", "", "", ""};

    AiPage() {
        task.onEnter = this::go;
    }

    @Override
    public String title() {
        return "AI";
    }

    @Override
    public int width() {
        return 330;
    }

    @Override
    public int height() {
        return 330;
    }

    private static void run(String cmd) {
        if (!AIRuntime.started()) {
            Ui.cmd("start");
        }
        Ui.cmd(cmd);
    }

    private void go() {
        String t = task.text.trim();
        if (!t.isEmpty()) {
            run(t);
        }
    }

    private static int material(String id) {
        if (id.startsWith("diamond")) return 0xFF4AEDD9;
        if (id.startsWith("iron")) return 0xFFD8D8D8;
        if (id.startsWith("golden")) return 0xFFFCEE4B;
        if (id.startsWith("stone") || id.contains("cobble")) return 0xFF8B8B8B;
        if (id.startsWith("wooden") || id.contains("planks") || id.endsWith("_log")) return 0xFFB58B52;
        if (id.startsWith("netherite")) return 0xFF5A3D5C;
        if (id.contains("beef") || id.contains("pork") || id.contains("bread") || id.contains("apple") || id.contains("chicken")) return 0xFFE08040;
        return 0xFF6C7A96;
    }

    private static String abbr(String id) {
        int i = id.lastIndexOf('_');
        String last = i >= 0 ? id.substring(i + 1) : id;
        return last.length() > 6 ? last.substring(0, 6) : last;
    }

    private static void bar(Ui ui, String label, double v, double max, int x, int y, int w, int h, int color) {
        ui.fill(x, y, w, h, 0xFF10131C);
        int fw = (int) Math.round((w - 2) * Math.max(0.0, Math.min(1.0, v / max)));
        ui.fill(x + 1, y + 1, fw, h - 2, color);
        ui.label(label + " " + Math.round(v) + "/" + Math.round(max), x + 4, y + 2, w - 8, Theme.TEXT);
    }

    private static void hotbar(Ui ui, LocalPlayer p, int x, int y, int w) {
        Inventory inv = p.getInventory();
        int slot = (w - 16) / 9;
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getItem(i);
            int sx = x + i * (slot + 2);
            boolean sel = i == inv.getSelectedSlot();
            int edge = sel ? Theme.accent() : 0xFF3A4660;
            ui.fill(sx, y, slot, 24, sel ? 0xFF2A1014 : 0xFF10131C);
            ui.fill(sx, y, slot, 1, edge);
            ui.fill(sx, y + 23, slot, 1, edge);
            ui.fill(sx, y, 1, 24, edge);
            ui.fill(sx + slot - 1, y, 1, 24, edge);
            if (!st.isEmpty()) {
                String id = Inv.idOf(st);
                ui.fill(sx + 1, y + 1, slot - 2, 2, material(id));
                ui.label(abbr(id), sx + 2, y + 5, slot - 3, Theme.TEXT);
                if (st.getCount() > 1) {
                    ui.label(Integer.toString(st.getCount()), sx + 2, y + 14, slot - 3, Theme.MUTED);
                }
            }
        }
    }

    private static void toggle(Ui ui, String label, boolean on, int x, int y, int w, Runnable action) {
        ui.button(label + ": " + (on ? "ON" : "OFF"), x, y, w, 18, action);
        ui.fill(x, y, 3, 18, on ? Theme.GOOD : Theme.BAD);
    }

    private void refreshStatus() {
        NullClient c = Ui.nc();
        Minecraft mc = Minecraft.getInstance();
        AIAgent a = c.ai().activeAgent();
        status[0] = AIRuntime.started()
                ? (a == null ? "AI ready - idle" : "doing: " + a.label() + " [" + a.state() + "]")
                : "AI is OFF - press a quick task or AI: OFF to start it";
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
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        boolean on = AIRuntime.started();
        int third = (w - 8) / 3;
        int half = (w - 4) / 2;

        ui.button(on ? "AI: ON" : "AI: OFF", x, y, third, 20, () -> Ui.cmd(on ? "off" : "start"));
        ui.button("Stop task", x + third + 4, y, third, 20, () -> Ui.cmd("stop"));
        ui.button("Stop all", x + 2 * (third + 4), y, third, 20, () -> Ui.cmd("stopall"));
        y += 26;

        if (p != null) {
            bar(ui, "HP", p.getHealth(), p.getMaxHealth(), x, y, half, 13, 0xFFE03A3A);
            bar(ui, "Food", p.getFoodData().getFoodLevel(), 20, x + half + 4, y, half, 13, 0xFFD89A3A);
            y += 18;
            hotbar(ui, p, x, y, w);
            y += 30;
        }
        String fight = Combat.state();
        if (!fight.isEmpty()) {
            ui.label("Fighting: " + fight, x, y, w, Theme.BAD);
            y += 14;
        }

        ui.text("Quick tasks", x, y, Theme.accent());
        y += 12;
        for (int i = 0; i < QUICK.length; i++) {
            String cmd = QUICK[i][1];
            ui.button(QUICK[i][0], x + (i % 2) * (half + 4), y + (i / 2) * 22, half, 18, () -> run(cmd));
        }
        y += (QUICK.length / 2) * 22 + 4;

        ui.text("Or tell it what to do:", x, y, Theme.accent());
        y += 12;
        ui.field(task, x, y, w - 54, 20);
        ui.button("Run", x + w - 50, y, 50, 20, this::go);
        y += 28;

        ui.text("Behaviour", x, y, Theme.accent());
        y += 12;
        toggle(ui, "Vision", Vision.enabled(), x, y, half, () -> Ui.cmd("vision " + (Vision.enabled() ? "off" : "on")));
        toggle(ui, "Combat", Combat.enabled, x + half + 4, y, half, () -> Combat.enabled = !Combat.enabled);
        y += 22;
        toggle(ui, "Crits", Combat.crits, x, y, half, () -> Combat.crits = !Combat.crits);
        toggle(ui, "W-tap", Combat.wtap, x + half + 4, y, half, () -> Combat.wtap = !Combat.wtap);
        y += 22;
        toggle(ui, "Shield", Combat.shield, x, y, half, () -> Combat.shield = !Combat.shield);
        toggle(ui, "Creeper wall", Combat.creeperWall, x + half + 4, y, half, () -> Combat.creeperWall = !Combat.creeperWall);
        y += 22;
        toggle(ui, "Leave if player near", Guarded.leaveOnPlayer, x, y, half, () -> Ui.cmd("logout " + (Guarded.leaveOnPlayer ? "off" : "on")));
        ui.button("Chat narration", x + half + 4, y, half, 18, () -> Ui.cmd("talk"));
        y += 28;

        if (ui.frame % 20 == 0 || status[0].isEmpty()) {
            refreshStatus();
        }
        for (int i = 0; i < status.length; i++) {
            ui.label(status[i], x, y + i * 12, w, i == 1 ? Theme.accent() : Theme.TEXT);
        }
    }
}