package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.nav.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks one block per call sequence: aims, picks the fastest hotbar tool, and keeps mining until it is gone.
 * It never mines through another block: if something solid lies between the eyes and the requested block, that
 * blocker is mined first, which produces a straight tunnel toward the target.
 */
public final class Breaker {
    public enum Result { BUSY, DONE, FAILED }

    private static final double REACH = 4.5;
    private static final int AIM_PATIENCE = 12;

    private static BlockPos aimTarget;
    private static int aimWait;

    private Breaker() {
    }

    /** First solid cell strictly between {@code from} and the centre of {@code target}, or null if the way is clear. */
    public static BlockPos firstBlocker(ClientLevel level, Vec3 from, BlockPos target) {
        Vec3 to = Vec3.atCenterOf(target);
        Vec3 d = to.subtract(from);
        int steps = Math.max(2, (int) Math.ceil(d.length() / 0.1));
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i = 1; i < steps; i++) {
            double f = i / (double) steps;
            mp.set(Mth.floor(from.x + d.x * f), Mth.floor(from.y + d.y * f), Mth.floor(from.z + d.z * f));
            if (mp.equals(target)) {
                return null;
            }
            if (!level.getBlockState(mp).getCollisionShape(level, mp).isEmpty()) {
                return mp.immutable();
            }
        }
        return null;
    }

    public static Result step(Blackboard bb, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        if (mc.gameMode == null) {
            bb.set(AIKeys.FAIL_REASON, "no game mode available");
            return Result.FAILED;
        }
        if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return Result.DONE;
        }
        Vec3 eye = p.getEyePosition();
        BlockPos dig = pos;
        BlockPos blocker = firstBlocker(level, eye, pos);
        if (blocker != null) {
            if (!new WorldView(level).clearable(blocker.getX(), blocker.getY(), blocker.getZ())) {
                bb.set(AIKeys.FAIL_REASON, "something I will not dig is in the way at " + blocker.toShortString());
                return Result.FAILED;
            }
            dig = blocker;
        }
        BlockState s = level.getBlockState(dig);
        if (s.getDestroySpeed(level, dig) < 0.0f) {
            bb.set(AIKeys.FAIL_REASON, "unbreakable block at " + dig.toShortString());
            return Result.FAILED;
        }
        Vec3 c = Vec3.atCenterOf(dig);
        if (eye.distanceTo(c) > REACH) {
            bb.set(AIKeys.FAIL_REASON, "block out of reach at " + dig.toShortString());
            return Result.FAILED;
        }
        double dx = c.x - p.getX();
        double dy = c.y - p.getEyeY();
        double dz = c.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, pitch);

        if (!dig.equals(aimTarget)) {
            aimTarget = dig;
            aimWait = 0;
        }
        boolean aimed = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) <= 12.0f && Math.abs(pitch - p.getXRot()) <= 12.0f;
        if (!aimed && aimWait++ < AIM_PATIENCE) {
            return Result.BUSY;
        }

        selectBestTool(p, s);
        Direction face = Direction.getApproximateNearest(p.getX() - c.x, p.getEyeY() - c.y, p.getZ() - c.z);
        if (!dig.equals(bb.get(AIKeys.CUR_BREAK))) {
            mc.gameMode.startDestroyBlock(dig, face);
            bb.set(AIKeys.CUR_BREAK, dig);
        } else {
            mc.gameMode.continueDestroyBlock(dig, face);
        }
        p.swing(InteractionHand.MAIN_HAND);
        return Result.BUSY;
    }

    public static void abort(Blackboard bb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && bb.get(AIKeys.CUR_BREAK) != null) {
            mc.gameMode.stopDestroyBlock();
        }
        bb.set(AIKeys.CUR_BREAK, null);
        aimTarget = null;
        aimWait = 0;
    }

    public static void selectBestTool(LocalPlayer p, BlockState s) {
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
}