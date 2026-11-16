package dev.nullclient.ai.task;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;

/** What game mode the player is in, and what that rules out. */
public final class Modes {
    private Modes() {
    }

    public static GameType type() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameMode == null ? null : mc.gameMode.getPlayerMode();
    }

    public static boolean creative() {
        return type() == GameType.CREATIVE;
    }

    /** A reason the AI cannot act at all in this mode, or null. */
    public static String blocker() {
        return type() == GameType.SPECTATOR ? "you are in spectator mode - the AI cannot act" : null;
    }

    /** A reason the AI cannot break or place blocks in this mode, or null. */
    public static String blockerForBlocks() {
        if (type() == GameType.ADVENTURE) {
            return "adventure mode cannot break blocks - switch to survival or creative";
        }
        return blocker();
    }
}