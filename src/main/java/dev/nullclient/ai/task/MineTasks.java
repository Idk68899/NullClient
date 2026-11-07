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
import net.minecraft.util.Mth;
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
 * <p>Exposed targets are reached by walking. Buried targets are reached by walking to the closest standable
 * spot near them and then tunnelling toward them greedily (see DigTasks). A block is only broken with a clear
 * line of sight. After each block the agent walks to the dropped item. Unreachable targets are blacklisted;
 * when nothing is indexed nearby it explores and rescans.
 */
public final class MineTasks {
    private static final double REACH = 4.0;
    private static final int SCAN_XZ = 20;
    private static final int SCAN_Y = 10;
    private static final int COLLECT_TICKS = 40;
    private static final int COLLECT_GRACE = 8;
    private static final int MAX_EXPLORES = 6;
    private static final int MAX_BAD = 12;
    private static final int MAX_BURIED_CHECKS = 6;

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
                new Sequence("Tunnel", false,
                        new Condition("DigPhase", bb -> bb.getOrDefault(AIKeys.TUNNEL, false) || NavTasks.atGoal(bb)),
                        new Task("Dig", DigTasks::digToward)),
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
        bb.set(AIKeys.ALLOW_DIG, null);
        bb.set(AIKeys.TUNNEL, null);
        bb.set(AIKeys.DIG_STEPS, null);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        String spec = bb.get(AIKeys.MINE_BLOCK);
        BlockPos origin = p.blockPosition();
        WorldView view = new WorldView(level);
        AIKeys.BadSet bad = bb.get(AIKeys.BAD_TARGETS);
        List<BlockPos> candidates = WorldScanner.INSTANCE.covers(spec)
                ? WorldScanner.INSTANCE.find(spec, origin, 96, 64)
                : boxScan(level, origin, spec);

        BlockPos exposed = null;
        BlockPos exposedStand = null;
        double exposedD = Double.MAX_VALUE;
        BlockPos buried = null;
        BlockPos buriedStand = null;
        double buriedD = Double.MAX_VALUE;
        int buriedChecked = 0;
        for (BlockPos c : candidates) {
            if (bad != null && bad.contains(c.asLong())) {
                continue;
            }
            BlockState s = level.getBlockState(c);
            if (s.isAir() || !BlockSpec.test(idOf(s), spec)) {
                continue;
            }
            if (!view.clearable(c.getX(), c.getY(), c.getZ())) {
                continue;
            }
            double d = Math.sqrt(c.distSqr(origin));
            BlockPos walk = standCell(view, c);
            if (walk != null) {
                if (d < exposedD) {
                    exposedD = d;
                    exposed = c;
                    exposedStand = walk;
                }
            } else if (d < buriedD && buriedChecked < MAX_BURIED_CHECKS) {
                buriedChecked++;
                BlockPos surface = surfaceNear(view, c);
                if (surface != null) {
                    buriedD = d;
                    buried = c;
                    buriedStand = surface;
                }
            }
        }

        BlockPos target = null;
        BlockPos goal = null;
        if (exposed != null && (buried == null || exposedD <= buriedD * 2.0 + 20.0)) {
            target = exposed;
            goal = exposedStand;
        } else if (buried != null) {
            target = buried;
            goal = buriedStand;
        }
        if (target != null) {
            bb.set(AIKeys.TARGET, target);
            bb.set(AIKeys.GOAL, goal);
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
        bb.set(AIKeys.TUNNEL, null);
        bb.set(AIKeys.DIG_STEPS, null);
        bb.set(AIKeys.FAIL_REASON, null);
        NavTasks.resetNav(bb);
        return Status.RUNNING;
    }

    /** True if nothing solid lies between the point and the centre of the target block. */
    static boolean clearLine(ClientLevel level, Vec3 from, BlockPos target) {
        Vec3 to = Vec3.atCenterOf(target);
        Vec3 d = to.subtract(from);
        int steps = Math.max(2, (int) Math.ceil(d.length() / 0.2));
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i = 1; i < steps; i++) {
            double f = i / (double) steps;
            mp.set(Mth.floor(from.x + d.x * f), Mth.floor(from.y + d.y * f), Mth.floor(from.z + d.z * f));
            if (mp.equals(target)) {
                return true;
            }
            if (!level.getBlockState(mp).getCollisionShape(level, mp).isEmpty()) {
                return false;
            }
        }
        return true;
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
        return eye.distanceTo(Vec3.atCenterOf(t)) <= REACH && clearLine(level, eye, t);
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
            bb.set(AIKeys.TUNNEL, null);
            bb.set(AIKeys.DIG_STEPS, null);
            return Status.RUNNING;
        }
        if (!inReach(bb)) {
            // Not in reach yet: the approach step (walk or tunnel) handles it on the next tick.
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