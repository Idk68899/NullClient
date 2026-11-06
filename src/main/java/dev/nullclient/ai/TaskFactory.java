package dev.nullclient.ai;

import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.plan.GoalNode;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.ai.plan.Planner;
import dev.nullclient.ai.plan.Step;
import dev.nullclient.ai.task.MineTasks;
import dev.nullclient.ai.task.NavTasks;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses task strings into agents. */
public final class TaskFactory {
    public static final String USAGE = "get <item> [count] | plan <item> [count] | goto <x> <y> <z> | mine <block> [count] | wander [radius] | follow <player> | stop";

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
            case "get": {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException("usage: get <item> [count]  e.g. get iron_pickaxe");
                }
                String item = args[1].toLowerCase(Locale.ROOT).replace("minecraft:", "");
                int count = args.length == 3 ? Integer.parseInt(args[2]) : 1;
                if (count < 1 || count > 512) {
                    throw new IllegalArgumentException("count must be 1-512");
                }
                try {
                    Planner.plan(Inv.snapshot(p), Set.of(), item, count);
                } catch (Planner.PlanException e) {
                    throw new IllegalArgumentException(e.getMessage());
                }
                return new AIAgent(profile, "get " + item + " x" + count, new GoalNode(item, count), bb);
            }
            case "mine": {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException("usage: mine <block> [count]  e.g. mine oak_log 8");
                }
                String block = args[1].toLowerCase(Locale.ROOT).replace("minecraft:", "");
                int count = args.length == 3 ? Integer.parseInt(args[2]) : 1;
                if (count < 1 || count > 256) {
                    throw new IllegalArgumentException("count must be 1-256");
                }
                bb.set(AIKeys.MINE_BLOCK, block);
                bb.set(AIKeys.MINE_COUNT, count);
                bb.set(AIKeys.MINED, 0);
                return new AIAgent(profile, "mine " + block + " x" + count, MineTasks.mineTree(), bb);
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

    /** Dry run: the plan for {@code <item> [count]} against the current inventory, one line per step. */
    public static List<String> preview(String[] args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            throw new IllegalStateException("Not in a world");
        }
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: plan <item> [count]");
        }
        String item = args[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
        int count = args.length >= 2 ? Integer.parseInt(args[1]) : 1;
        List<String> out = new ArrayList<>();
        try {
            int i = 1;
            for (Step s : Planner.plan(Inv.snapshot(mc.player), Set.of(), item, count)) {
                out.add(i++ + ". " + s.describe());
            }
        } catch (Planner.PlanException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (out.isEmpty()) {
            out.add("already have it");
        }
        return out;
    }

    private static int coord(String s, double base) {
        if (s.startsWith("~")) {
            double off = s.length() == 1 ? 0 : Double.parseDouble(s.substring(1));
            return (int) Math.floor(base + off);
        }
        return (int) Math.floor(Double.parseDouble(s));
    }
}