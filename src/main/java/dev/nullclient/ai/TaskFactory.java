package dev.nullclient.ai;

import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.task.NavTasks;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.Locale;

/** Parses task strings into agents. Supported: goto, wander, follow. */
public final class TaskFactory {
    public static final String USAGE = "goto <x> <y> <z> | wander [radius] | follow <player> | stop";

    private TaskFactory() {
    }

    public static AIAgent create(Profile profile, String[] args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            throw new IllegalStateException("Not in a world");
        }
        String kind = args[0].toLowerCase(Locale.ROOT);
        Blackboard bb = new Blackboard();
        bb.set(AIKeys.REPLANS, 0);
        switch (kind) {
            case "goto": {
                if (args.length != 4) {
                    throw new IllegalArgumentException("usage: goto <x> <y> <z>  (~ for relative)");
                }
                BlockPos goal = new BlockPos(coord(args[1], p.getX()), coord(args[2], p.getY()), coord(args[3], p.getZ()));
                bb.set(AIKeys.GOAL, goal);
                return new AIAgent(profile, "goto " + goal.getX() + " " + goal.getY() + " " + goal.getZ(), NavTasks.gotoTree(), bb);
            }
            case "wander": {
                int radius = args.length >= 2 ? Integer.parseInt(args[1]) : 16;
                if (radius < 2 || radius > 128) {
                    throw new IllegalArgumentException("radius must be 2-128");
                }
                bb.set(AIKeys.WANDER_RADIUS, radius);
                return new AIAgent(profile, "wander " + radius, NavTasks.wanderTree(), bb);
            }
            case "follow": {
                if (args.length != 2) {
                    throw new IllegalArgumentException("usage: follow <player>");
                }
                bb.set(AIKeys.TARGET_NAME, args[1]);
                return new AIAgent(profile, "follow " + args[1], NavTasks.followTree(), bb);
            }
            default:
                throw new IllegalArgumentException("unknown task '" + args[0] + "'. Tasks: " + USAGE);
        }
    }

    private static int coord(String s, double base) {
        if (s.startsWith("~")) {
            double off = s.length() == 1 ? 0 : Double.parseDouble(s.substring(1));
            return (int) Math.floor(base + off);
        }
        return (int) Math.floor(Double.parseDouble(s));
    }
}