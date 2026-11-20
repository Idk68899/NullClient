package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.nav.AStarPathfinder;
import dev.nullclient.ai.nav.Path;
import dev.nullclient.ai.nav.PathFollower;
import dev.nullclient.ai.nav.PathNode;
import dev.nullclient.ai.nav.WorldView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Runs a parkour course: checkpoints one after another, using the normal pathfinder (walk, step, drop, sprint jumps of
 * 2-4 blocks) with digging and pillaring switched off. When a leg cannot be done it fails with a message that lists the
 * blocks in the way and the gaps, instead of digging through them.
 */
public final class CourseNode implements Node {
    private static final long SEARCH_NANOS = 6_000_000L;
    private static final int CHUNK = 40;
    private static final int MAX_EXPANSIONS = 20_000;
    private static final int MAX_REPLANS = 10;
    private static final int MAX_PLANS = 40;

    private final List<BlockPos> pts;
    private final AIKeys.BadSet avoid = new AIKeys.BadSet();
    private int leg;
    private int replans;
    private int plans;
    private AStarPathfinder.Search search;
    private PathFollower follower;

    public CourseNode(List<BlockPos> pts) {
        this.pts = List.copyOf(pts);
    }

    private void clearNav(Blackboard bb) {
        search = null;
        follower = null;
        bb.set(AIKeys.PATH, null);
        bb.set(AIKeys.SEARCH, null);
        bb.set(AIKeys.FOLLOWER, null);
    }

    private Status fail(Blackboard bb, LocalPlayer p, ClientLevel level, BlockPos goal, String why) {
        List<String> blockers = Obstacles.between(level, p.blockPosition(), goal);
        String msg = "checkpoint " + (leg + 1) + " at " + goal.toShortString() + ": " + why
                + (blockers.isEmpty() ? "" : " - in the way: " + String.join("; ", blockers));
        RunLog.add(msg);
        bb.set(AIKeys.FAIL_REASON, msg);
        clearNav(bb);
        return Status.FAILURE;
    }

    @Override
    public Status tick(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        if (leg >= pts.size()) {
            return Status.SUCCESS;
        }
        BlockPos goal = pts.get(leg);
        bb.set(AIKeys.GOAL, goal);
        if (NavTasks.atGoal(bb)) {
            RunLog.add("reached checkpoint " + (leg + 1) + "/" + pts.size() + " at " + goal.toShortString());
            leg++;
            replans = 0;
            plans = 0;
            clearNav(bb);
            return leg >= pts.size() ? Status.SUCCESS : Status.RUNNING;
        }
        if (follower != null) {
            PathFollower.Result r = follower.tick(p, level, bb);
            if (r == PathFollower.Result.RUNNING) {
                return Status.RUNNING;
            }
            if (r == PathFollower.Result.DONE) {
                clearNav(bb);
                return Status.RUNNING;
            }
            replans++;
            PathNode n = follower.currentNode();
            if (!n.move.isDig()) {
                avoid.add(BlockPos.asLong(n.x, n.y, n.z));
            }
            String why = follower.failure();
            clearNav(bb);
            RunLog.add("parkour leg " + (leg + 1) + " failed: " + why + " (try " + replans + ")");
            if (replans >= MAX_REPLANS) {
                return fail(bb, p, level, goal, "gave up after " + replans + " tries (last: " + why + ")");
            }
            return Status.RUNNING;
        }
        return plan(bb, p, level, goal);
    }

    private Status plan(Blackboard bb, LocalPlayer p, ClientLevel level, BlockPos goal) {
        if (search == null) {
            if (!p.onGround() && !p.isInWater()) {
                return Status.RUNNING;
            }
            if (++plans > MAX_PLANS) {
                return fail(bb, p, level, goal, "could not settle on it after " + MAX_PLANS + " plans");
            }
            search = new AStarPathfinder.Search(new WorldView(level, avoid), p.blockPosition(), goal, MAX_EXPANSIONS, false, false)
                    .maxFall(NavTasks.fallLimit(p));
            bb.set(AIKeys.SEARCH, search);
        }
        long end = System.nanoTime() + SEARCH_NANOS;
        AStarPathfinder.State st = search.state();
        while (st == AStarPathfinder.State.RUNNING && System.nanoTime() < end) {
            st = search.step(CHUNK);
        }
        if (st == AStarPathfinder.State.RUNNING) {
            return Status.RUNNING;
        }
        bb.set(AIKeys.SEARCH, null);
        if (st == AStarPathfinder.State.FAILED) {
            search = null;
            return fail(bb, p, level, goal, "no walking or jumping route from " + p.blockPosition().toShortString());
        }
        Path path = search.result();
        search = null;
        if (path.size() < 2) {
            return fail(bb, p, level, goal, "the route is empty");
        }
        follower = new PathFollower(path);
        bb.set(AIKeys.PATH, path);
        bb.set(AIKeys.FOLLOWER, follower);
        return Status.RUNNING;
    }

    @Override
    public void reset(Blackboard bb) {
        leg = 0;
        replans = 0;
        plans = 0;
        avoid.clear();
        clearNav(bb);
    }

    @Override
    public String name() {
        return "Course";
    }

    @Override
    public void describeActive(StringBuilder sb) {
        sb.append("Course checkpoint ").append(leg + 1).append('/').append(pts.size());
    }
}