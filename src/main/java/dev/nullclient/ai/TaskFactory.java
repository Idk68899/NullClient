
package dev.nullclient.ai;

import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Sequence;
import dev.nullclient.ai.plan.GoalNode;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.ai.plan.Planner;
import dev.nullclient.ai.plan.Step;
import dev.nullclient.ai.plan.WikiKnowledge;
import dev.nullclient.ai.task.Armor;
import dev.nullclient.ai.task.BasicTasks;
import dev.nullclient.ai.task.CourseNode;
import dev.nullclient.ai.task.Guarded;
import dev.nullclient.ai.task.MineTasks;
import dev.nullclient.ai.task.Modes;
import dev.nullclient.ai.task.NavTasks;
import dev.nullclient.ai.task.Watched;
import dev.nullclient.core.course.Course;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses task strings into agents. goto, down, up, course, armor, get and mine run inside the safety guard and the stuck watchdog. */
public final class TaskFactory {
    public static final String USAGE = "get <item> [count] | armor <material> | plan <item> [count] | goto <x> <y> <z> | down | up | course <name> | mine <block> [count] | wander [radius] | follow <player> | stop";

    private TaskFactory() {
    }

    /** Checks that a plan exists; if the item is unknown, starts a wiki lookup that retries {@code retry} by itself. */
    private static void check(LocalPlayer p, String item, int count, String retry) {
        try {
            Planner.plan(Inv.snapshot(p), Set.of(), item, count);
        } catch (Planner.PlanException e) {
            if (WikiKnowledge.learn(item, retry)) {
                throw new IllegalArgumentException("'" + item + "' is not in my knowledge file - looking it up on the Minecraft Wiki; I will retry by myself if I find a recipe");
            }
            throw new IllegalArgumentException(e.getMessage());
        }
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
                String blocked = Modes.blocker();
                if (blocked != null) {
                    throw new IllegalStateException(blocked);
                }
                BlockPos goal = new BlockPos(coord(args[1], p.getX()), coord(args[2], p.getY()), coord(args[3], p.getZ()));
                bb.set(AIKeys.GOAL, goal);
                return new AIAgent(profile, "goto " + goal.getX() + " " + goal.getY() + " " + goal.getZ(),
                        new Watched(new Guarded(NavTasks.gotoTree())), bb);
            }
            case "down":
            case "up": {
                String blocked = Modes.blocker();
                if (blocked != null) {
                    throw new IllegalStateException(blocked);
                }
                boolean down = kind.equals("down");
                return new AIAgent(profile, down ? "get down" : "get out",
                        new Watched(new Guarded(down ? BasicTasks.downTree() : BasicTasks.upTree())), bb);
            }
            case "course": {
                if (args.length < 2) {
                    throw new IllegalArgumentException("usage: course <name>");
                }
                String blocked = Modes.blocker();
                if (blocked != null) {
                    throw new IllegalStateException(blocked);
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Course c = Course.find(name);
                if (c == null) {
                    throw new IllegalArgumentException("no course called '" + name + "' - make one in the Tools window");
                }
                if (c.points.isEmpty()) {
                    throw new IllegalArgumentException("the course '" + c.name + "' has no checkpoints yet");
                }
                return new AIAgent(profile, "course " + c.name, new Watched(new Guarded(new CourseNode(c.positions()))), bb);
            }
            case "armor": {
                String blocked = Modes.blockerForBlocks();
                if (blocked != null) {
                    throw new IllegalStateException(blocked);
                }
                String mat = args.length > 1 ? args[1] : "iron";
                List<Node> pieces = new ArrayList<>();
                for (String piece : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
                    String item = mat + "_" + piece;
                    if (Armor.has(p, item)) {
                        continue;
                    }
                    check(p, item, 1, "armor " + mat);
                    pieces.add(new GoalNode(item, 1));
                }
                if (pieces.isEmpty()) {
                    throw new IllegalArgumentException("you already have the full " + mat + " set");
                }
                return new AIAgent(profile, "armor " + mat,
                        new Watched(new Guarded(new Sequence("Armor " + mat, true, pieces.toArray(new Node[0])))), bb);
            }
            case "get": {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException("usage: get <item> [count]  e.g. get iron_pickaxe");
                }
                String blocked = Modes.blockerForBlocks();
                if (blocked != null) {
                    throw new IllegalStateException(blocked);
                }
                String item = args[1].toLowerCase(Locale.ROOT).replace("minecraft:", "");
                int count = args.length == 3 ? Integer.parseInt(args[2]) : 1;
                if (count < 1 || count > 512) {
                    throw new IllegalArgumentException("count must be 1-512");
                }
                check(p, item, count, "get " + item + (count > 1 ? " " + count : ""));
                return new AIAgent(profile, "get " + item + " x" + count, new Watched(new Guarded(new GoalNode(item, count))), bb);
            }
            case "mine": {
                if (args.length < 2 || args.length > 3) {
                    throw new IllegalArgumentException("usage: mine <block> [count]  e.g. mine oak_log 8");
                }
                String blocked = Modes.blockerForBlocks();
                if (blocked != null) {
                    throw new IllegalStateException(blocked);
                }
                String block = args[1].toLowerCase(Locale.ROOT).replace("minecraft:", "");
                int count = args.length == 3 ? Integer.parseInt(args[2]) : 1;
                if (count < 1 || count > 256) {
                    throw new IllegalArgumentException("count must be 1-256");
                }
                bb.set(AIKeys.MINE_BLOCK, block);
                bb.set(AIKeys.MINE_COUNT, count);
                bb.set(AIKeys.MINED, 0);
                return new AIAgent(profile, "mine " + block + " x" + count, new Watched(new Guarded(MineTasks.mineTree())), bb);
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
            if (WikiKnowledge.learn(item, "plan " + item + (count > 1 ? " " + count : ""))) {
                throw new IllegalArgumentException("'" + item + "' is not in my knowledge file - looking it up on the Minecraft Wiki");
            }
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