package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Condition;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Repeater;
import dev.nullclient.ai.bt.Selector;
import dev.nullclient.ai.bt.Sequence;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.bt.Task;
import dev.nullclient.ai.nav.AStarPathfinder;
import dev.nullclient.ai.nav.Path;
import dev.nullclient.ai.nav.PathFollower;
import dev.nullclient.ai.nav.WorldView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/** Leaf implementations and tree builders for navigation tasks. */
public final class NavTasks {
    private static final int MAX_REPLANS = 12;
    private static final int SEARCH_BUDGET_PER_TICK = 400;
    private static final int MAX_EXPANSIONS = 25_000;

    private NavTasks() {
    }

    // ------------------------------------------------------------------ trees

    /** Reactive goto tree: arrive > follow current path > (re)plan > abort. Digs only if ALLOW_DIG is set. */
    public static Node gotoTree() {
        return new Selector("Goto", false,
                new Sequence("Arrived", false,
                        new Condition("AtGoal", NavTasks::atGoal),
                        new Task("Finish", bb -> {
                            resetNav(bb);
                            return Status.SUCCESS;
                        })),
                new Sequence("FollowPath", false,
                        new Condition("HasPath", bb -> bb.get(AIKeys.PATH) != null),
                        new Task("Walk", NavTasks::followPath)),
                new Sequence("Plan", false,
                        new Condition("CanReplan", bb -> bb.getOrDefault(AIKeys.REPLANS, 0) < MAX_REPLANS),
                        new Task("AStar", NavTasks::planPath)),
                new Task("Abort", bb -> {
                    if (bb.get(AIKeys.FAIL_REASON) == null) {
                        bb.set(AIKeys.FAIL_REASON, "navigation failed after " + MAX_REPLANS + " replans");
                    }
                    return Status.FAILURE;
                }));
    }

    public static Node wanderTree() {
        Node leg = new Sequence("WanderLeg", true,
                new Task("PickGoal", NavTasks::pickWanderGoal),
                new Selector("TryGoto", false,
                        gotoTree(),
                        new Task("SkipLeg", bb -> {
                            bb.set(AIKeys.FAIL_REASON, null);
                            return Status.SUCCESS;
                        })),
                new Task("Idle", NavTasks::idle, bb -> bb.set(AIKeys.WAIT_LEFT, null)),
                new Task("ResetNav", bb -> {
                    resetNav(bb);
                    return Status.SUCCESS;
                }));
        return new Repeater("Wander", -1, leg);
    }

    public static Node followTree() {
        return new Repeater("Follow", -1, new Selector("FollowSel", false,
                new Sequence("NoTarget", false,
                        new Condition("TargetMissing", bb -> findTarget(bb) == null),
                        new Task("WaitForTarget", bb -> {
                            resetNav(bb);
                            return Status.RUNNING;
                        })),
                new Sequence("Near", false,
                        new Condition("TargetNear", bb -> targetDistance(bb) < 3.0),
                        new Task("FaceTarget", NavTasks::faceTarget)),
                new Sequence("Chase", false,
                        new Task("TrackGoal", NavTasks::trackGoal),
                        gotoTree()),
                new Task("Recover", bb -> {
                    resetNav(bb);
                    bb.set(AIKeys.FAIL_REASON, null);
                    return Status.RUNNING;
                })));
    }

    // ------------------------------------------------------------------ goto leaves

    static boolean atGoal(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        BlockPos g = bb.get(AIKeys.GOAL);
        if (p == null || g == null) {
            return false;
        }
        double dx = p.getX() - (g.getX() + 0.5);
        double dz = p.getZ() - (g.getZ() + 0.5);
        return dx * dx + dz * dz <= 0.7 * 0.7 && Math.abs(p.getY() - g.getY()) <= 1.5;
    }

    static Status planPath(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        BlockPos goal = bb.get(AIKeys.GOAL);
        AStarPathfinder.Search search = bb.get(AIKeys.SEARCH);
        if (search == null) {
            if (!p.onGround() && !p.isInWater()) {
                return Status.RUNNING;
            }
            boolean dig = bb.getOrDefault(AIKeys.ALLOW_DIG, false);
            search = new AStarPathfinder.Search(new WorldView(level), p.blockPosition(), goal, MAX_EXPANSIONS, dig);
            bb.set(AIKeys.SEARCH, search);
        }
        switch (search.step(SEARCH_BUDGET_PER_TICK)) {
            case RUNNING:
                return Status.RUNNING;
            case FOUND:
            case PARTIAL:
                Path path = search.result();
                bb.set(AIKeys.SEARCH, null);
                bb.set(AIKeys.PATH, path);
                bb.set(AIKeys.FOLLOWER, new PathFollower(path));
                return Status.SUCCESS;
            default:
                bb.set(AIKeys.SEARCH, null);
                bb.set(AIKeys.FAIL_REASON, "no path to " + goal.toShortString());
                bb.set(AIKeys.REPLANS, MAX_REPLANS);
                return Status.FAILURE;
        }
    }

    static Status followPath(Blackboard bb) {
        PathFollower follower = bb.get(AIKeys.FOLLOWER);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        if (follower == null) {
            bb.set(AIKeys.PATH, null);
            return Status.FAILURE;
        }
        PathFollower.Result r = follower.tick(p, level, bb);
        switch (r) {
            case RUNNING:
                return Status.RUNNING;
            case DONE:
                bb.set(AIKeys.PATH, null);
                bb.set(AIKeys.FOLLOWER, null);
                if (follower.consumed() >= 8) {
                    bb.set(AIKeys.REPLANS, 0);
                }
                return Status.RUNNING;
            default:
                bb.set(AIKeys.PATH, null);
                bb.set(AIKeys.FOLLOWER, null);
                bb.set(AIKeys.REPLANS, follower.consumed() >= 8 ? 0 : bb.getOrDefault(AIKeys.REPLANS, 0) + 1);
                return Status.FAILURE;
        }
    }

    static void resetNav(Blackboard bb) {
        bb.set(AIKeys.PATH, null);
        bb.set(AIKeys.SEARCH, null);
        bb.set(AIKeys.FOLLOWER, null);
        bb.set(AIKeys.REPLANS, 0);
    }

    // ------------------------------------------------------------------ wander leaves

    static Status pickWanderGoal(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        int radius = bb.getOrDefault(AIKeys.WANDER_RADIUS, 16);
        BlockPos origin = bb.get(AIKeys.ORIGIN);
        if (origin == null) {
            origin = p.blockPosition();
            bb.set(AIKeys.ORIGIN, origin);
        }
        WorldView view = new WorldView(level);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int baseY = p.blockPosition().getY();
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = origin.getX() + rnd.nextInt(-radius, radius + 1);
            int z = origin.getZ() + rnd.nextInt(-radius, radius + 1);
            for (int y = baseY + 6; y >= baseY - 6; y--) {
                if (view.canStand(x, y, z)) {
                    bb.set(AIKeys.GOAL, new BlockPos(x, y, z));
                    return Status.SUCCESS;
                }
            }
        }
        bb.set(AIKeys.GOAL, p.blockPosition());
        return Status.SUCCESS;
    }

    static Status idle(Blackboard bb) {
        Integer left = bb.get(AIKeys.WAIT_LEFT);
        if (left == null) {
            left = ThreadLocalRandom.current().nextInt(40, 121);
        }
        left--;
        if (left <= 0) {
            bb.set(AIKeys.WAIT_LEFT, null);
            return Status.SUCCESS;
        }
        bb.set(AIKeys.WAIT_LEFT, left);
        return Status.RUNNING;
    }

    // ------------------------------------------------------------------ follow leaves

    static AbstractClientPlayer findTarget(Blackboard bb) {
        String name = bb.get(AIKeys.TARGET_NAME);
        LocalPlayer self = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        if (name == null || level == null) {
            return null;
        }
        for (AbstractClientPlayer other : level.players()) {
            if (other != self && other.getName().getString().equalsIgnoreCase(name)) {
                return other;
            }
        }
        return null;
    }

    static double targetDistance(Blackboard bb) {
        AbstractClientPlayer t = findTarget(bb);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        return t == null ? Double.MAX_VALUE : Math.hypot(t.getX() - p.getX(), t.getZ() - p.getZ());
    }

    static Status faceTarget(Blackboard bb) {
        AbstractClientPlayer t = findTarget(bb);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        resetNav(bb);
        if (t != null) {
            double dx = t.getX() - p.getX();
            double dz = t.getZ() - p.getZ();
            double dy = t.getEyeY() - p.getEyeY();
            bb.set(AIKeys.YAW, (float) Math.toDegrees(Math.atan2(-dx, dz)));
            bb.set(AIKeys.PITCH, (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        }
        return Status.RUNNING;
    }

    static Status trackGoal(Blackboard bb) {
        AbstractClientPlayer t = findTarget(bb);
        if (t == null) {
            return Status.FAILURE;
        }
        BlockPos tp = t.blockPosition();
        BlockPos cur = bb.get(AIKeys.GOAL);
        if (cur == null || cur.distSqr(tp) > 4.0) {
            bb.set(AIKeys.GOAL, tp);
            bb.set(AIKeys.PATH, null);
            bb.set(AIKeys.SEARCH, null);
            bb.set(AIKeys.FOLLOWER, null);
        }
        return Status.SUCCESS;
    }
}