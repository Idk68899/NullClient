package dev.nullclient.ai;

import dev.nullclient.ai.plan.Knowledge;
import dev.nullclient.ai.world.WorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** On/off switch for the AI engine. While off, nothing scans the world or runs, so it costs no CPU or battery. */
public final class AIRuntime {
    private static volatile boolean started;

    private AIRuntime() {
    }

    public static boolean started() {
        return started;
    }

    /** Loads knowledge, turns the world scanner on, loads saved memory. Returns report lines for chat. */
    public static List<String> start() {
        List<String> report = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        Knowledge k = Knowledge.get();
        report.add("knowledge loaded");
        WorldScanner.INSTANCE.setInterest(k.scanIds());
        BlackBox.clear();
        started = true;
        report.add("world scanner on (indexing logs, ores and crafting stations)");
        report.add("flight recorder on (old recording deleted)");
        LocalPlayer p = mc.player;
        if (p != null && mc.level != null) {
            report.add(AIMemory.INSTANCE.load(mc.level, p.blockPosition()));
        } else {
            report.add("memory loads once you are in a world");
        }
        return report;
    }

    /** Stops the scanner and frees its index; nothing runs in the background afterwards. */
    public static void stop() {
        started = false;
        WorldScanner.INSTANCE.setInterest(Set.of());
        AIMemory.INSTANCE.save();
    }
}