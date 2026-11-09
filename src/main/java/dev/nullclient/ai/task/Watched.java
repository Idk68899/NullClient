package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.BlackBox;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.nav.PathFollower;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps a whole task with a stuck watchdog and the flight recorder.
 * Stuck means: no progress for {@value #LIMIT_TICKS} ticks (no movement of 2+ blocks, no inventory change, nothing
 * mined, no new block being broken, no menu open) OR spinning in place (turning more than 540 degrees in 3 seconds
 * without leaving a 1.5 block area). It then escalates: 1) drop the target, clear the route and jump; 2) also
 * clear avoided cells, allow tunnels/pillars and run in a random direction for a moment; 3) give up with a clear
 * message. Everything is logged for ".ai why" and the recording is saved when something goes wrong.
 */
public final class Watched implements Node {
    private static final int LIMIT_TICKS = 400;
    private static final int CHECK_EVERY = 10;
    private static final double MOVE_BLOCKS = 2.0;
    private static final int NUDGE_TICKS = 25;
    private static final int SPIN_WINDOW = 60;
    private static final double SPIN_DEGREES = 540.0;
    private static final double SPIN_MOVE = 1.5;
    private static final int SAMPLE_EVERY = 2;

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
    private float lastYaw = Float.NaN;
    private double spinSum;
    private int spinTicks;
    private BlockPos spinAnchor;

    public Watched(Node child) {
        this.child = child;
    }

    @Override
    public Status tick(Blackboard bb) {
        Status s = child.tick(bb);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        if (p == null) {
            return s;
        }
        tick++;
        if (tick % SAMPLE_EVERY == 0) {
            BlackBox.sample(tick, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(),
                    bb.getOrDefault(AIKeys.ACTIONS, 0), note(bb));
        }
        if (s == Status.FAILURE) {
            String reason = bb.get(AIKeys.FAIL_REASON);
            BlackBox.dump("task failed: " + (reason == null ? "unknown" : reason));
            return s;
        }
        if (s != Status.RUNNING) {
            return s;
        }
        if (nudge > 0) {
            nudge--;
            applyNudge(bb);
            anchorTick = tick;
            return Status.RUNNING;
        }

        float yawNow = p.getYRot();
        if (!Float.isNaN(lastYaw)) {
            spinSum += Math.abs(Mth.wrapDegrees(yawNow - lastYaw));
        }
        lastYaw = yawNow;
        if (spinAnchor == null) {
            spinAnchor = p.blockPosition();
        }
        if (++spinTicks >= SPIN_WINDOW) {
            double moved = Math.sqrt(p.blockPosition().distSqr(spinAnchor));
            boolean spinning = spinSum > SPIN_DEGREES && moved < SPIN_MOVE;
            double turned = spinSum;
            spinSum = 0.0;
            spinTicks = 0;
            spinAnchor = p.blockPosition();
            if (spinning) {
                RunLog.add("spinning in place near " + p.blockPosition().toShortString() + ": turned "
                        + Math.round(turned) + " degrees without moving");
                BlackBox.dump("spinning in place");
                anchorTick = tick;
                return escalate(bb, p, "spinning in place");
            }
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
        BlackBox.dump("no progress for " + (LIMIT_TICKS / 20) + "s");
        return escalate(bb, p, "no progress for " + (LIMIT_TICKS / 20) + "s");
    }

    private static String note(Blackboard bb) {
        StringBuilder sb = new StringBuilder();
        BlockPos t = bb.get(AIKeys.TARGET);
        if (t != null) {
            sb.append("tgt=").append(t.toShortString()).append(' ');
        }
        BlockPos g = bb.get(AIKeys.GOAL);
        if (g != null) {
            sb.append("goal=").append(g.toShortString()).append(' ');
        }
        PathFollower f = bb.get(AIKeys.FOLLOWER);
        if (f != null) {
            sb.append(f.currentNode().move.name().toLowerCase()).append(' ')
                    .append(f.index()).append('/').append(f.path().size() - 1);
        }
        return sb.toString().trim();
    }

    private Status escalate(Blackboard bb, LocalPlayer p, String why) {
        level++;
        String where = p.blockPosition().toShortString();
        if (level >= 3) {
            String msg = "stuck near " + where + " (" + why + ")";
            RunLog.add(msg + " - recovery step 3, giving up");
            bb.set(AIKeys.FAIL_REASON, msg);
            return Status.FAILURE;
        }
        RunLog.add("stuck near " + where + " (" + why + ") - recovery step " + level);
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
        spinSum = 0.0;
        spinTicks = 0;
        spinAnchor = null;
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