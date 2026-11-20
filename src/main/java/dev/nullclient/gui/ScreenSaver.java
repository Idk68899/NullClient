package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.script.ScriptRunner;
import net.minecraft.client.Minecraft;

/**
 * "Screen off while the AI works". Opens a black screen while a task runs and closes it when the task finishes or fails.
 * The render-gate mixin skips drawing the world while it is open; {@link #hookRan} says whether that hook is active.
 */
public final class ScreenSaver {
    public static volatile boolean enabled;
    public static volatile boolean hookRan;

    private static volatile boolean active;
    private static boolean manual;
    private static boolean suppressed;

    private ScreenSaver() {
    }

    public static boolean skipWorld() {
        return active;
    }

    public static boolean active() {
        return active;
    }

    /** Turns the screen off right now (stays off until you wake it). */
    public static void screenOffNow() {
        manual = true;
        suppressed = false;
    }

    /** Turns the screen back on and keeps it on for the rest of the current task. */
    public static void wake() {
        manual = false;
        suppressed = true;
        active = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof BlackoutScreen) {
            mc.gui.setScreen(null);
        }
    }

    public static void tick(Minecraft mc, NullClient nc) {
        boolean black = mc.gui.screen() instanceof BlackoutScreen;
        active = black;
        boolean working = nc.ai().activeAgent() != null || ScriptRunner.running();
        if (!working) {
            suppressed = false;
        }
        if (black) {
            if (!working && !manual) {
                mc.gui.setScreen(null);
            }
            return;
        }
        if (mc.level != null && mc.gui.screen() == null && (manual || (enabled && working && !suppressed))) {
            mc.gui.setScreen(new BlackoutScreen());
        }
    }
}