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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generic block-gathering tree. The target is a block spec (see BlockSpec); it ends when either MINED reaches
 * MINE_COUNT (the "mine" command) or the inventory holds GOAL_COUNT of GOAL_ITEM (planner gather steps).
 */
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
                        new Condition("Enough", MineTasks::enough),
                        new Task("Finish", bb -> Status.SUCCESS)),
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

    /** A safe standing cell next to the block from which it is within reach, or null. */
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
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        String spec = bb.get(AIKeys.MINE_BLOCK);
        BlockPos origin = p.blockPosition();
        WorldView view = new WorldView(level);
        List<BlockPos> candidates = WorldScanner.INSTANCE.covers(spec)
                ? WorldScanner.INSTANCE.find(spec, origin, 64, 48)
                : boxScan(level, origin, spec);
        boolean seen = false;
        for (BlockPos c : candidates) {
            BlockState s = level.getBlockState(c);
            if (s.isAir() || !BlockSpec.test(idOf(s), spec)) {
                continue;
            }
            seen = true;
            BlockPos stand = standCell(view, c);
            if (stand == null) {
                continue;
            }
            bb.set(AIKeys.TARGET, c);
            bb.set(AIKeys.GOAL, stand);
            NavTasks.resetNav(bb);
            return Status.SUCCESS;
        }
        bb.set(AIKeys.FAIL_REASON, seen
                ? "found " + spec.replace("=", "") + " but it is buried (digging is not implemented yet)"
                : "no " + spec.replace("=", "") + " nearby");
        return Status.FAILURE;
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
        String spec = bb.get(AIKeys.MINE_BLOCK);
        if (mc.gameMode == null) {
            bb.set(AIKeys.FAIL_REASON, "no game mode available");
            return Status.FAILURE;
        }
        BlockState s = level.getBlockState(t);
        boolean breaking = bb.getOrDefault(AIKeys.BREAKING, false);
        if (s.isAir() || !BlockSpec.test(idOf(s), spec)) {
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