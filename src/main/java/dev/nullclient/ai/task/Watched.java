package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps a whole task with a stuck watchdog. If there is no progress for {@value #LIMIT_TICKS} ticks (no movement
 * of 2+ blocks, no inventory change, nothing mined, no new block being broken, no menu open) it escalates:
 * 1) drop the current target, clear the route and jump; 2) also clear avoided cells, allow tunnels/pillars and run
 * in a random direction for a moment; 3) give up with a clear message. Everything is logged for ".ai why".
 */
public final class Watched implements Node {
    private static final int LIMIT_TICKS = 400;
    private static final int CHECK_EVERY = 10;
    private static final double MOVE_BLOCKS = 2.0;
    private static final int NUDGE_TICKS = 25;

    private final Node child;
    private long tick;
    private BlockPos anchor;
    private long anchorTick;
    private int lastInv = -1;
    private int lastMined = -1;
    private BlockPos lastBreak;
    private int level;
    private int nudge;
    private float nudgeYaw;

    public Watched(Node child) {
        this.child = child;
    }

    @Override
    public Status tick(Blackboard bb) {
        Status s = child.tick(bb);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        if (s != Status.RUNNING || p == null) {
            return s;
        }
        tick++;
        if (nudge > 0) {
            nudge--;
            applyNudge(bb);
            anchorTick = tick;
            return Status.RUNNING;
        }
        if (tick % CHECK_EVERY != 0) {
            return Status.RUNNING;
        }

        boolean menu = p.containerMenu != p.inventoryMenu;
        int inv = invTotal(p);
        int mined = bb.getOrDefault(AIKeys.MINED, 0);
        BlockPos br = bb.get(AIKeys.CUR_BREAK);
        BlockPos here = p.blockPosition();
        boolean real = lastInv != -1 && (inv != lastInv || mined != lastMined);
        boolean moved = anchor == null || Math.sqrt(here.distSqr(anchor)) > MOVE_BLOCKS;
        boolean breakingNew = br != null && !br.equals(lastBreak);
        lastInv = inv;
        lastMined = mined;
        lastBreak = br;

        if (real) {
            level = 0;
        }
        if (real || moved || breakingNew || menu) {
            anchor = here;
            anchorTick = tick;
            return Status.RUNNING;
        }
        if (tick - anchorTick < LIMIT_TICKS) {
            return Status.RUNNING;
        }
        anchorTick = tick;
        return escalate(bb, p);
    }

    private Status escalate(Blackboard bb, LocalPlayer p) {
        level++;
        String where = p.blockPosition().toShortString();
        if (level >= 3) {
            String why = "stuck near " + where + " with no progress for " + (LIMIT_TICKS / 20) + "s";
            RunLog.add(why + " (recovery step 3) - giving up");
            bb.set(AIKeys.FAIL_REASON, why);
            return Status.FAILURE;
        }
        RunLog.add("stuck near " + where + " for " + (LIMIT_TICKS / 20) + "s - recovery step " + level);
        dropAndReset(bb);
        InputOverride.forceJump();
        if (level == 2) {
            bb.set(AIKeys.AVOID, null);
            bb.set(AIKeys.ALLOW_DIG, true);
            nudge = NUDGE_TICKS;
            nudgeYaw = ThreadLocalRandom.current().nextFloat() * 360.0f - 180.0f;
        }
        return Status.RUNNING;
    }

    private static void dropAndReset(Blackboard bb) {
        BlockPos t = bb.get(AIKeys.TARGET);
        if (t != null) {
            AIKeys.BadSet bad = bb.get(AIKeys.BAD_TARGETS);
            if (bad == null) {
                bad = new AIKeys.BadSet();
                bb.set(AIKeys.BAD_TARGETS, bad);
            }
            bad.add(t.asLong());
        }
        Breaker.abort(bb);
        bb.set(AIKeys.TARGET, null);
        bb.set(AIKeys.TUNNEL, null);
        bb.set(AIKeys.BURIED, null);
        bb.set(AIKeys.DIG_STEPS, null);
        bb.set(AIKeys.COLLECT, null);
        NavTasks.resetNav(bb);
    }

    private void applyNudge(Blackboard bb) {
        double rad = Math.toRadians(nudgeYaw);
        bb.set(AIKeys.ACTIONS, InputOverride.FORWARD | InputOverride.JUMP | InputOverride.SPRINT);
        bb.set(AIKeys.YAW, nudgeYaw);
        bb.set(AIKeys.PITCH, 10.0f);
        InputOverride.setDirection(-Math.sin(rad), Math.cos(rad));
    }

    private static int invTotal(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            n += inv.getItem(i).getCount();
        }
        return n;
    }

    @Override
    public void reset(Blackboard bb) {
        child.reset(bb);
        level = 0;
        nudge = 0;
        anchor = null;
    }

    @Override
    public String name() {
        return "Watch";
    }

    @Override
    public void describeActive(StringBuilder sb) {
        child.describeActive(sb);
    }
}