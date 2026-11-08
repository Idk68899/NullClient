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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generic block-gathering tree. The target is a block spec (see BlockSpec). It ends when either MINED reaches
 * MINE_COUNT (the "mine" command) or the inventory holds GOAL_COUNT of GOAL_ITEM (planner gather steps).
 *
 * <p>Target choice: the nearest block it can mine from a standing spot with a clear view, otherwise a buried block.
 * It walks to its standing spot; if something solid is still in the way it breaks the blockers one by one (a
 * tunnel), and for far buried targets it tunnels step by step. Every few ticks it also grabs any matching block
 * within arm's reach, which follows ore veins. After each block it walks to the dropped item.
 */
public final class MineTasks {
    private static final double REACH = 4.0;
    private static final double SPOT_REACH = 3.7;
    private static final double EYE = 1.62;
    private static final double BURY_PENALTY = 14.0;
    private static final double GRAB_RADIUS = 4.0;
    private static final int GRAB_EVERY = 4;
    private static final int SCAN_XZ = 16;
    private static final int SCAN_Y = 8;
    private static final int COLLECT_TICKS = 40;
    private static final int COLLECT_GRACE = 8;
    private static final int MAX_EXPLORES = 6;
    private static final int MAX_BAD = 12;
    private static final int MAX_EXPOSED_EVAL = 14;
    private static final int MAX_BURIED_EVAL = 4;

    private MineTasks() {
    }

    public static Node mineTree() {
        Node ensureTarget = new Selector("EnsureTarget", false,
                new Condition("HasTarget", bb -> bb.get(AIKeys.TARGET) != null),
                new Task("FindBlock", MineTasks::findBlock));
        Node approach = new Selector("Approach", false,
                new Condition("InReach", MineTasks::inReach),
                new Sequence("Walk", false,
                        new Condition("WalkPhase", bb -> !bb.getOrDefault(AIKeys.TUNNEL, false) && !NavTasks.atGoal(bb)),
                        NavTasks.gotoTree()),
                new Task("Open", MineTasks::openWay),
                new Task("DropTarget", MineTasks::dropTarget));
        Node mineOne = new Sequence("MineOne", false,
                new Task("Grab", MineTasks::grabNearby),
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

    /** A safe walkable cell next to the block from which it is within reach, or null (used for stations). */
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

    /** A walkable stand cell if one exists, otherwise a cell that can be tunnelled to (used for stations). */
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

    /**
     * The standing spot (walkable or floating in water) within arm's reach of the target with a clear view of it,
     * closest to {@code from}. Looks up to 3 blocks sideways and from 4 below to 1 above the target.
     */
    private static BlockPos reachSpot(ClientLevel level, WorldView view, BlockPos t, BlockPos from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        Vec3 center = Vec3.atCenterOf(t);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int y = t.getY() - 4; y <= t.getY() + 1; y++) {
                    int x = t.getX() + dx;
                    int z = t.getZ() + dz;
                    double d = Math.abs(x - from.getX()) + Math.abs(z - from.getZ()) + 2.0 * Math.abs(y - from.getY());
                    if (d >= bestD) {
                        continue;
                    }
                    if (!view.canStand(x, y, z) || view.nearHazard(x, y, z)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(x + 0.5, y + EYE, z + 0.5);
                    if (eye.distanceTo(center) > SPOT_REACH || Breaker.firstBlocker(level, eye, t) != null) {
                        continue;
                    }
                    best = new BlockPos(x, y, z);
                    bestD = d;
                }
            }
        }
        return best;
    }

    /** The standable cell closest to a buried target, searched in a small box around it (usually the surface above). */
    private static BlockPos surfaceNear(WorldView view, BlockPos t) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int y = t.getY() + 14; y >= t.getY() - 2; y--) {
                    int x = t.getX() + dx;
                    int z = t.getZ() + dz;
                    if (view.canStand(x, y, z) && !view.nearHazard(x, y, z)) {
                        double dy = y - t.getY();
                        double d = dx * dx + dz * dz + dy * dy;
                        if (d < bestD) {
                            bestD = d;
                            best = new BlockPos(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static List<BlockPos> boxScan(ClientLevel level, BlockPos origin, String spec, int rxz, int ry) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -rxz; dx <= rxz; dx++) {
            for (int dz = -rxz; dz <= rxz; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -ry; dy <= ry; dy++) {
                    mp.set(x, origin.getY() + dy, z);
                    if (BlockSpec.matches(level.getBlockState(mp), spec)) {
                        out.add(mp.immutable());
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(b -> b.distSqr(origin)));
        return out.size() > 48 ? new ArrayList<>(out.subList(0, 48)) : out;
    }

    static Status findBlock(Blackboard bb) {
        bb.set(AIKeys.ALLOW_DIG, null);
        bb.set(AIKeys.TUNNEL, null);
        bb.set(AIKeys.BURIED, null);
        bb.set(AIKeys.DIG_STEPS, null);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        String spec = bb.get(AIKeys.MINE_BLOCK);
        BlockPos origin = p.blockPosition();
        WorldView view = new WorldView(level);
        AIKeys.BadSet bad = bb.get(AIKeys.BAD_TARGETS);
        List<BlockPos> candidates = WorldScanner.INSTANCE.covers(spec)
                ? WorldScanner.INSTANCE.find(spec, origin, 96, 80)
                : boxScan(level, origin, spec, SCAN_XZ, SCAN_Y);

        BlockPos bestT = null;
        BlockPos bestG = null;
        boolean bestBuried = false;
        double bestScore = Double.MAX_VALUE;
        int exposedEval = 0;
        int buriedEval = 0;
        for (BlockPos c : candidates) {
            if (bad != null && bad.contains(c.asLong())) {
                continue;
            }
            if (!BlockSpec.matches(level.getBlockState(c), spec)) {
                continue;
            }
            if (view.touchesHazard(c.getX(), c.getY(), c.getZ())) {
                continue;
            }
            double base = Math.sqrt(c.distSqr(origin)) + 2.0 * Math.abs(c.getY() - origin.getY());
            if (base >= bestScore) {
                continue;
            }
            if (exposedEval < MAX_EXPOSED_EVAL) {
                exposedEval++;
                BlockPos spot = reachSpot(level, view, c, origin);
                if (spot != null) {
                    bestScore = base;
                    bestT = c;
                    bestG = spot;
                    bestBuried = false;
                    continue;
                }
            }
            if (buriedEval < MAX_BURIED_EVAL && base + BURY_PENALTY < bestScore) {
                buriedEval++;
                BlockPos surface = surfaceNear(view, c);
                if (surface != null) {
                    bestScore = base + BURY_PENALTY;
                    bestT = c;
                    bestG = surface;
                    bestBuried = true;
                }
            }
        }

        if (bestT != null) {
            bb.set(AIKeys.TARGET, bestT);
            bb.set(AIKeys.GOAL, bestG);
            bb.set(AIKeys.BURIED, bestBuried ? Boolean.TRUE : null);
            NavTasks.resetNav(bb);
            return Status.SUCCESS;
        }

        String shown = spec.replace("=", "");
        int tries = bb.getOrDefault(AIKeys.EXPLORE_TRIES, 0);
        if (tries >= MAX_EXPLORES) {
            bb.set(AIKeys.FAIL_REASON, "no reachable " + shown + " found after exploring");
            return Status.FAILURE;
        }
        BlockPos explore = pickExploreGoal(level, p);
        if (explore == null) {
            bb.set(AIKeys.FAIL_REASON, "no " + shown + " nearby and nowhere to explore");
            return Status.FAILURE;
        }
        bb.set(AIKeys.EXPLORE_TRIES, tries + 1);
        bb.set(AIKeys.GOAL, explore);
        bb.set(AIKeys.EXPLORING, true);
        NavTasks.resetNav(bb);
        return Status.RUNNING;
    }

    /**
     * Every few ticks: if a matching block is within arm's reach with a clear view and the current target is not,
     * switch to it. After breaking one ore the rest of the vein is right there, so this mines whole veins.
     */
    static Status grabNearby(Blackboard bb) {
        int tick = bb.getOrDefault(AIKeys.GRAB_TICK, 0) + 1;
        bb.set(AIKeys.GRAB_TICK, tick);
        if (tick % GRAB_EVERY != 0) {
            return Status.SUCCESS;
        }
        BlockPos cur = bb.get(AIKeys.TARGET);
        if (cur != null && inReach(bb)) {
            return Status.SUCCESS;
        }
        String spec = bb.get(AIKeys.MINE_BLOCK);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        AIKeys.BadSet bad = bb.get(AIKeys.BAD_TARGETS);
        Vec3 eye = p.getEyePosition();
        BlockPos best = null;
        double bestD = GRAB_RADIUS * GRAB_RADIUS;
        for (BlockPos c : boxScan(level, p.blockPosition(), spec, 4, 4)) {
            if (bad != null && bad.contains(c.asLong())) {
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(c));
            if (d < bestD && Breaker.firstBlocker(level, eye, c) == null) {
                bestD = d;
                best = c;
            }
        }
        if (best != null && !best.equals(cur) && !new WorldView(level).touchesHazard(best.getX(), best.getY(), best.getZ())) {
            Breaker.abort(bb);
            bb.set(AIKeys.TARGET, best);
            bb.set(AIKeys.GOAL, null);
            bb.set(AIKeys.BURIED, null);
            bb.set(AIKeys.TUNNEL, null);
            bb.set(AIKeys.DIG_STEPS, null);
            NavTasks.resetNav(bb);
        }
        return Status.SUCCESS;
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

    /**
     * We are at our standing spot (or already tunnelling) but still cannot mine the target. If it is within reach,
     * break whatever is in the way (never the block under our feet); if it is far, tunnel toward it.
     */
    static Status openWay(Blackboard bb) {
        BlockPos t = bb.get(AIKeys.TARGET);
        if (t == null) {
            return Status.RUNNING;
        }
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        Vec3 eye = p.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(t)) <= REACH) {
            BlockPos blocker = Breaker.firstBlocker(level, eye, t);
            if (blocker == null) {
                return Status.RUNNING;
            }
            BlockPos feet = p.blockPosition();
            boolean underFoot = blocker.getX() == feet.getX() && blocker.getZ() == feet.getZ() && blocker.getY() < feet.getY();
            if (!underFoot) {
                return Breaker.step(bb, blocker) == Breaker.Result.FAILED ? Status.FAILURE : Status.RUNNING;
            }
        }
        bb.set(AIKeys.TUNNEL, true);
        return DigTasks.digToward(bb);
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
        bb.set(AIKeys.TUNNEL, null);
        bb.set(AIKeys.BURIED, null);
        bb.set(AIKeys.DIG_STEPS, null);
        bb.set(AIKeys.FAIL_REASON, null);
        NavTasks.resetNav(bb);
        return Status.RUNNING;
    }

    /** Within reach AND with a clear view of the target; breaking blocks through other blocks is not allowed. */
    static boolean inReach(Blackboard bb) {
        BlockPos t = bb.get(AIKeys.TARGET);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        if (t == null) {
            return false;
        }
        Vec3 eye = p.getEyePosition();
        return eye.distanceTo(Vec3.atCenterOf(t)) <= REACH && Breaker.firstBlocker(level, eye, t) == null;
    }

    static Status breakBlock(Blackboard bb) {
        ClientLevel level = bb.get(AIKeys.LEVEL);
        BlockPos t = bb.get(AIKeys.TARGET);
        if (t == null) {
            return Status.RUNNING;
        }
        String spec = bb.get(AIKeys.MINE_BLOCK);
        BlockState s = level.getBlockState(t);
        if (!BlockSpec.matches(s, spec)) {
            if (t.equals(bb.get(AIKeys.CUR_BREAK))) {
                bb.set(AIKeys.MINED, bb.getOrDefault(AIKeys.MINED, 0) + 1);
                bb.set(AIKeys.LAST_BROKEN, t);
                bb.set(AIKeys.COLLECT, COLLECT_TICKS);
            }
            bb.set(AIKeys.CUR_BREAK, null);
            bb.set(AIKeys.TARGET, null);
            bb.set(AIKeys.TUNNEL, null);
            bb.set(AIKeys.BURIED, null);
            bb.set(AIKeys.DIG_STEPS, null);
            return Status.RUNNING;
        }
        if (!inReach(bb)) {
            return Status.RUNNING;
        }
        return Breaker.step(bb, t) == Breaker.Result.FAILED ? Status.FAILURE : Status.RUNNING;
    }

    private static ItemEntity nearestDrop(ClientLevel level, LocalPlayer p) {
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(6.0))) {
            if (item.isRemoved()) {
                continue;
            }
            double d = item.distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                best = item;
            }
        }
        return best;
    }

    /** Walks to the nearest dropped item for a short while so it gets picked up. */
    static Status collect(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        int left = bb.getOrDefault(AIKeys.COLLECT, 0) - 1;
        bb.set(AIKeys.COLLECT, left <= 0 ? null : left);
        ItemEntity item = nearestDrop(level, p);
        if (item == null) {
            if (left < COLLECT_TICKS - COLLECT_GRACE) {
                bb.set(AIKeys.COLLECT, null);
            }
            return Status.RUNNING;
        }
        double dx = item.getX() - p.getX();
        double dz = item.getZ() - p.getZ();
        double dy = item.getY() - p.getY();
        double hd = Math.hypot(dx, dz);
        if (hd > 0.3) {
            bb.set(AIKeys.YAW, (float) Math.toDegrees(Math.atan2(-dx, dz)));
            bb.set(AIKeys.PITCH, 20.0f);
            InputOverride.setDirection(dx / hd, dz / hd);
            int actions = InputOverride.FORWARD;
            if (dy > 0.6 && p.onGround() && hd < 1.5) {
                actions |= InputOverride.JUMP;
            }
            AIKeys.addActions(bb, actions);
        }
        return Status.RUNNING;
    }
}
          