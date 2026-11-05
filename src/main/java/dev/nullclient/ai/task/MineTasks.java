package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Condition;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Selector;
import dev.nullclient.ai.bt.Sequence;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.bt.Task;
import dev.nullclient.ai.nav.WorldView;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.ai.world.BlockSpec;
import dev.nullclient.ai.world.WorldScanner;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generic block-gathering tree. The target is a block spec (see BlockSpec). It ends when either MINED reaches
 * MINE_COUNT (the "mine" command) or the inventory holds GOAL_COUNT of GOAL_ITEM (planner gather steps).
 * Targets that are buried are reached by tunnelling; unreachable targets are blacklisted; when nothing is
 * indexed nearby it explores and rescans.
 */
public final class MineTasks {
    private static final double REACH = 4.0;
    private static final int SCAN_XZ = 20;
    private static final int SCAN_Y = 10;
    private static final int COLLECT_TICKS = 6;
    private static final int MAX_EXPLORES = 6;
    private static final int MAX_BAD = 12;

    private MineTasks() {
    }

    public static Node mineTree() {
        Node ensureTarget = new Selector("EnsureTarget", false,
                new Condition("HasTarget", bb -> bb.get(AIKeys.TARGET) != null),
                new Task("FindBlock", MineTasks::findBlock));
        Node approach = new Selector("Approach", false,
                new Condition("InReach", MineTasks::inReach),
                NavTasks.gotoTree(),
                new Task("DropTarget", MineTasks::dropTarget));
        Node mineOne = new Sequence("MineOne", false,
                ensureTarget,
                approach,
                new Task("Break", MineTasks::breakBlock));
        Node roamNav = NavTasks.gotoTree();
        return new Selector("Mine", false,
                new Sequence("Collect", false,
                        new Condition("Collecting", bb -> bb.getOrDefault(AIKeys.COLLECT, 0) > 0),
                        new Task("Pickup", MineTasks::collect)),
                new Sequence("Done", false,
                        new Condition("Enough", MineTasks::enough),
                        new Task("Finish", bb -> Status.SUCCESS)),
                new Sequence("Explore", false,
                        new Condition("Exploring", bb -> bb.getOrDefault(AIKeys.EXPLORING, false)),
                        new Task("Roam", bb -> roam(bb, roamNav))),
                mineOne);
    }

    static boolean enough(Blackboard bb) {
        String item = bb.get(AIKeys.GOAL_ITEM);
        if (item != null) {
            return Inv.count(bb.get(AIKeys.PLAYER), item) >= bb.getOrDefault(AIKeys.GOAL_COUNT, 1);
        }
        return bb.getOrDefault(AIKeys.MINED, 0) >= bb.getOrDefault(AIKeys.MINE_COUNT, 1);
    }

    static String idOf(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    /** A safe walkable cell next to the block from which it is within reach, or null. */
    static BlockPos standCell(WorldView view, BlockPos t) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = t.getX() + d[0];
            int nz = t.getZ() + d[1];
            for (int fy = t.getY(); fy >= t.getY() - 1; fy--) {
                if (view.canStand(nx, fy, nz) && !view.nearHazard(nx, fy, nz)) {
                    return new BlockPos(nx, fy, nz);
                }
            }
        }
        return null;
    }

    /** A walkable stand cell if one exists, otherwise a cell that can be tunnelled to. */
    static BlockPos reachCell(WorldView view, BlockPos t) {
        BlockPos walk = standCell(view, t);
        if (walk != null) {
            return walk;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = t.getX() + d[0];
            int nz = t.getZ() + d[1];
            for (int fy = t.getY(); fy >= t.getY() - 1; fy--) {
                boolean floor = view.isSolidTop(nx, fy - 1, nz);
                if (floor && view.clearable(nx, fy, nz) && view.clearable(nx, fy + 1, nz) && !view.nearHazard(nx, fy, nz)) {
                    return new BlockPos(nx, fy, nz);
                }
            }
        }
        return null;
    }

    private static List<BlockPos> boxScan(ClientLevel level, BlockPos origin, String spec) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -SCAN_XZ; dx <= SCAN_XZ; dx++) {
            for (int dz = -SCAN_XZ; dz <= SCAN_XZ; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -SCAN_Y; dy <= SCAN_Y; dy++) {
                    mp.set(x, origin.getY() + dy, z);
                    BlockState s = level.getBlockState(mp);
                    if (!s.isAir() && BlockSpec.test(idOf(s), spec)) {
                        out.add(mp.immutable());
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(b -> b.distSqr(origin)));
        return out.size() > 48 ? new ArrayList<>(out.subList(0, 48)) : out;
    }

    static Status findBlock(Blackboard bb) {
        bb.set(AIKeys.ALLOW_DIG, true);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        String spec = bb.get(AIKeys.MINE_BLOCK);
        BlockPos origin = p.blockPosition();
        WorldView view = new WorldView(level);
        AIKeys.BadSet bad = bb.get(AIKeys.BAD_TARGETS);
        List<BlockPos> candidates = WorldScanner.INSTANCE.covers(spec)
                ? WorldScanner.INSTANCE.find(spec, origin, 96, 64)
                : boxScan(level, origin, spec);
        for (BlockPos c : candidates) {
            if (bad != null && bad.contains(c.asLong())) {
                continue;
            }
            BlockState s = level.getBlockState(c);
            if (s.isAir() || !BlockSpec.test(idOf(s), spec)) {
                continue;
            }
            BlockPos stand = reachCell(view, c);
            if (stand == null) {
                continue;
            }
            bb.set(AIKeys.TARGET, c);
            bb.set(AIKeys.GOAL, stand);
            NavTasks.resetNav(bb);
            return Status.SUCCESS;
        }
        String shown = spec.replace("=", "");
        int tries = bb.getOrDefault(AIKeys.EXPLORE_TRIES, 0);
        if (tries >= MAX_EXPLORES) {
            bb.set(AIKeys.FAIL_REASON, "no reachable " + shown + " found after exploring");
            return Status.FAILURE;
        }
        BlockPos goal = pickExploreGoal(level, p);
        if (goal == null) {
            bb.set(AIKeys.FAIL_REASON, "no " + shown + " nearby and nowhere to explore");
            return Status.FAILURE;
        }
        bb.set(AIKeys.EXPLORE_TRIES, tries + 1);
        bb.set(AIKeys.GOAL, goal);
        bb.set(AIKeys.EXPLORING, true);
        NavTasks.resetNav(bb);
        return Status.RUNNING;
    }

    private static BlockPos pickExploreGoal(ClientLevel level, LocalPlayer p) {
        WorldView view = new WorldView(level);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        BlockPos o = p.blockPosition();
        for (int attempt = 0; attempt < 14; attempt++) {
            double angle = rnd.nextDouble(0.0, Math.PI * 2.0);
            double dist = 48.0 - attempt * 3.0;
            int x = o.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = o.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            for (int y = o.getY() + 12; y >= o.getY() - 24; y--) {
                if (view.canStand(x, y, z)) {
                    return new BlockPos(x, y, z);
                }
            }
        }
        return null;
    }

    static Status roam(Blackboard bb, Node nav) {
        Status s = nav.tick(bb);
        if (s != Status.RUNNING) {
            bb.set(AIKeys.EXPLORING, null);
            nav.reset(bb);
            NavTasks.resetNav(bb);
            bb.set(AIKeys.FAIL_REASON, null);
        }
        return Status.RUNNING;
    }

    /** The current target cannot be reached: remember that and pick another next tick. */
    static Status dropTarget(Blackboard bb) {
        BlockPos t = bb.get(AIKeys.TARGET);
        AIKeys.BadSet bad = bb.get(AIKeys.BAD_TARGETS);
        if (bad == null) {
            bad = new AIKeys.BadSet();
            bb.set(AIKeys.BAD_TARGETS, bad);
        }
        if (t != null) {
            bad.add(t.asLong());
        }
        if (bad.size() > MAX_BAD) {
            bb.set(AIKeys.FAIL_REASON, "gave up: too many unreachable targets");
            return Status.FAILURE;
        }
        Breaker.abort(bb);
        bb.set(AIKeys.TARGET, null);
        bb.set(AIKeys.FAIL_REASON, null);
        NavTasks.resetNav(bb);
        return Status.RUNNING;
    }

    static boolean inReach(Blackboard bb) {
        BlockPos t = bb.get(AIKeys.TARGET);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        return t != null && p.getEyePosition().distanceTo(Vec3.atCenterOf(t)) <= REACH;
    }

    static Status breakBlock(Blackboard bb) {
        ClientLevel level = bb.get(AIKeys.LEVEL);
        BlockPos t = bb.get(AIKeys.TARGET);
        if (t == null) {
            return Status.RUNNING;
        }
        String spec = bb.get(AIKeys.MINE_BLOCK);
        BlockState s = level.getBlockState(t);
        if (s.isAir() || !BlockSpec.test(idOf(s), spec)) {
            if (t.equals(bb.get(AIKeys.CUR_BREAK))) {
                bb.set(AIKeys.MINED, bb.getOrDefault(AIKeys.MINED, 0) + 1);
                bb.set(AIKeys.LAST_BROKEN, t);
                bb.set(AIKeys.COLLECT, COLLECT_TICKS);
            }
            bb.set(AIKeys.CUR_BREAK, null);
            bb.set(AIKeys.TARGET, null);
            return Status.RUNNING;
        }
        if (!inReach(bb)) {
            Breaker.abort(bb);
            bb.set(AIKeys.TARGET, null);
            return Status.RUNNING;
        }
        return Breaker.step(bb, t) == Breaker.Result.FAILED ? Status.FAILURE : Status.RUNNING;
    }

    /** Steps toward the broken block's cell for a few ticks so dropped items are picked up. */
    static Status collect(Blackboard bb) {
        int left = bb.getOrDefault(AIKeys.COLLECT, 0) - 1;
        bb.set(AIKeys.COLLECT, left <= 0 ? null : left);
        BlockPos last = bb.get(AIKeys.LAST_BROKEN);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        if (last != null) {
            Vec3 c = Vec3.atCenterOf(last);
            double dx = c.x - p.getX();
            double dz = c.z - p.getZ();
            if (Math.hypot(dx, dz) > 0.3) {
                bb.set(AIKeys.YAW, (float) Math.toDegrees(Math.atan2(-dx, dz)));
                bb.set(AIKeys.PITCH, 20.0f);
                AIKeys.addActions(bb, InputOverride.FORWARD);
            }
        }
        return Status.RUNNING;
    }
}