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
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Tree and leaves for {@code mine <block> [count]}: find, walk into reach, aim, pick tool, break, collect. */
public final class MineTasks {
    private static final double REACH = 4.0;
    private static final int SCAN_XZ = 20;
    private static final int SCAN_Y = 10;
    private static final int COLLECT_TICKS = 12;

    private MineTasks() {
    }

    public static Node mineTree() {
        Node ensureTarget = new Selector("EnsureTarget", false,
                new Condition("HasTarget", bb -> bb.get(AIKeys.TARGET) != null),
                new Task("FindBlock", MineTasks::findBlock));
        Node approach = new Selector("Approach", false,
                new Condition("InReach", MineTasks::inReach),
                NavTasks.gotoTree());
        Node mineOne = new Sequence("MineOne", false,
                ensureTarget,
                approach,
                new Task("Break", MineTasks::breakBlock));
        return new Selector("Mine", false,
                new Sequence("Collect", false,
                        new Condition("Collecting", bb -> bb.getOrDefault(AIKeys.COLLECT, 0) > 0),
                        new Task("Pickup", MineTasks::collect)),
                new Sequence("Done", false,
                        new Condition("EnoughMined", bb -> bb.getOrDefault(AIKeys.MINED, 0) >= bb.getOrDefault(AIKeys.MINE_COUNT, 1)),
                        new Task("Finish", bb -> Status.SUCCESS)),
                mineOne);
    }

    static boolean matches(String id, String want) {
        return id.equals(want) || id.endsWith("_" + want) || id.startsWith(want + "_");
    }

    private static String idOf(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    /** A safe standing cell next to the block from which it is within reach, or null. */
    private static BlockPos standCell(WorldView view, BlockPos t) {
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

    static Status findBlock(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        String want = bb.get(AIKeys.MINE_BLOCK);
        WorldView view = new WorldView(level);
        BlockPos origin = p.blockPosition();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        BlockPos bestStand = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SCAN_XZ; dx <= SCAN_XZ; dx++) {
            for (int dz = -SCAN_XZ; dz <= SCAN_XZ; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -SCAN_Y; dy <= SCAN_Y; dy++) {
                    mp.set(x, origin.getY() + dy, z);
                    double d = mp.distSqr(origin);
                    if (d >= bestD) {
                        continue;
                    }
                    BlockState s = level.getBlockState(mp);
                    if (s.isAir() || !matches(idOf(s), want)) {
                        continue;
                    }
                    BlockPos stand = standCell(view, mp);
                    if (stand == null) {
                        continue;
                    }
                    best = mp.immutable();
                    bestStand = stand;
                    bestD = d;
                }
            }
        }
        if (best == null) {
            bb.set(AIKeys.FAIL_REASON, "no reachable " + want + " within " + SCAN_XZ + " blocks");
            return Status.FAILURE;
        }
        bb.set(AIKeys.TARGET, best);
        bb.set(AIKeys.GOAL, bestStand);
        bb.set(AIKeys.PATH, null);
        bb.set(AIKeys.SEARCH, null);
        bb.set(AIKeys.FOLLOWER, null);
        bb.set(AIKeys.REPLANS, 0);
        return Status.SUCCESS;
    }

    static boolean inReach(Blackboard bb) {
        BlockPos t = bb.get(AIKeys.TARGET);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        return t != null && p.getEyePosition().distanceTo(Vec3.atCenterOf(t)) <= REACH;
    }

    private static void selectBestTool(LocalPlayer p, BlockState s) {
        Inventory inv = p.getInventory();
        int bestSlot = inv.getSelectedSlot();
        float bestSpeed = inv.getItem(bestSlot).getDestroySpeed(s);
        for (int i = 0; i < 9; i++) {
            float sp = inv.getItem(i).getDestroySpeed(s);
            if (sp > bestSpeed + 1e-3f) {
                bestSpeed = sp;
                bestSlot = i;
            }
        }
        if (bestSlot != inv.getSelectedSlot()) {
            inv.setSelectedSlot(bestSlot);
        }
    }

    static Status breakBlock(Blackboard bb) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        BlockPos t = bb.get(AIKeys.TARGET);
        if (mc.gameMode == null) {
            bb.set(AIKeys.FAIL_REASON, "no game mode available");
            return Status.FAILURE;
        }
        BlockState s = level.getBlockState(t);
        boolean breaking = bb.getOrDefault(AIKeys.BREAKING, false);
        if (s.isAir() || !matches(idOf(s), bb.get(AIKeys.MINE_BLOCK))) {
            if (breaking) {
                bb.set(AIKeys.MINED, bb.getOrDefault(AIKeys.MINED, 0) + 1);
                bb.set(AIKeys.LAST_BROKEN, t);
                bb.set(AIKeys.COLLECT, COLLECT_TICKS);
            }
            bb.set(AIKeys.BREAKING, null);
            bb.set(AIKeys.TARGET, null);
            return Status.RUNNING;
        }
        if (!inReach(bb)) {
            mc.gameMode.stopDestroyBlock();
            bb.set(AIKeys.BREAKING, null);
            bb.set(AIKeys.TARGET, null);
            return Status.RUNNING;
        }
        Vec3 c = Vec3.atCenterOf(t);
        double dx = c.x - p.getX();
        double dy = c.y - p.getEyeY();
        double dz = c.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, pitch);
        if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) > 12.0f || Math.abs(pitch - p.getXRot()) > 12.0f) {
            return Status.RUNNING;
        }
        selectBestTool(p, s);
        Direction face = Direction.getApproximateNearest(p.getX() - c.x, p.getEyeY() - c.y, p.getZ() - c.z);
        if (!breaking) {
            mc.gameMode.startDestroyBlock(t, face);
            bb.set(AIKeys.BREAKING, true);
        } else {
            mc.gameMode.continueDestroyBlock(t, face);
        }
        p.swing(InteractionHand.MAIN_HAND);
        return Status.RUNNING;
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