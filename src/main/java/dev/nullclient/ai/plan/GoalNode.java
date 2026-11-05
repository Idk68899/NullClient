package dev.nullclient.ai.plan;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.Say;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Sequence;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.task.StepTasks;
import dev.nullclient.ai.world.WorldScanner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Think-act loop for "hold N of item": observe inventory and nearby stations, plan, execute the plan as a
 * behavior tree, then observe again. Failures trigger a re-plan, up to a limit.
 */
public final class GoalNode implements Node {
    private static final int MAX_PLANS = 5;

    private final String item;
    private final int count;
    private Node tree;
    private int plans;
    private String lastReason;

    public GoalNode(String item, int count) {
        this.item = item;
        this.count = count;
    }

    @Override
    public Status tick(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        if (Inv.count(p, item) >= count) {
            return Status.SUCCESS;
        }
        if (tree == null) {
            if (plans >= MAX_PLANS) {
                bb.set(AIKeys.FAIL_REASON, lastReason != null ? lastReason : "gave up after " + MAX_PLANS + " plans");
                return Status.FAILURE;
            }
            plans++;
            List<Step> steps;
            try {
                steps = Planner.plan(Inv.snapshot(p), stations(p), item, count);
            } catch (Planner.PlanException e) {
                bb.set(AIKeys.FAIL_REASON, e.getMessage());
                return Status.FAILURE;
            }
            if (steps.isEmpty()) {
                return Status.SUCCESS;
            }
            Say.line("plan " + plans + " for " + item + " x" + count + ":");
            int i = 1;
            for (Step s : steps) {
                Say.line("  " + i++ + ". " + s.describe());
            }
            tree = new Sequence("Plan", true, steps.stream().map(StepTasks::node).toArray(Node[]::new));
            bb.set(AIKeys.FAIL_REASON, null);
        }
        Status s = tree.tick(bb);
        if (s == Status.RUNNING) {
            return Status.RUNNING;
        }
        if (s == Status.FAILURE) {
            lastReason = bb.get(AIKeys.FAIL_REASON);
        }
        tree.reset(bb);
        tree = null;
        return Status.RUNNING;
    }

    private static Set<String> stations(LocalPlayer p) {
        Set<String> out = new HashSet<>();
        BlockPos o = p.blockPosition();
        if (WorldScanner.INSTANCE.has("=crafting_table", o, 32)) {
            out.add("crafting_table");
        }
        if (WorldScanner.INSTANCE.has("=furnace", o, 32)) {
            out.add("furnace");
        }
        return out;
    }

    @Override
    public void reset(Blackboard bb) {
        tree = null;
        plans = 0;
        lastReason = null;
    }

    @Override
    public String name() {
        return "Goal " + item + " x" + count;
    }

    @Override
    public void describeActive(StringBuilder sb) {
        sb.append(name());
        if (tree != null) {
            sb.append(" > ");
            tree.describeActive(sb);
        }
    }
}