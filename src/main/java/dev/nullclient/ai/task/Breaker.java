package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
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

/** Breaks one block per call sequence: aims, picks the fastest hotbar tool, and keeps mining until it is gone. */
public final class Breaker {
    public enum Result { BUSY, DONE, FAILED }

    private static final double REACH = 4.5;

    private Breaker() {
    }

    public static Result step(Blackboard bb, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        if (mc.gameMode == null) {
            bb.set(AIKeys.FAIL_REASON, "no game mode available");
            return Result.FAILED;
        }
        BlockState s = level.getBlockState(pos);
        if (s.getCollisionShape(level, pos).isEmpty()) {
            return Result.DONE;
        }
        if (s.getDestroySpeed(level, pos) < 0.0f) {
            bb.set(AIKeys.FAIL_REASON, "unbreakable block at " + pos.toShortString());
            return Result.FAILED;
        }
        Vec3 c = Vec3.atCenterOf(pos);
        if (p.getEyePosition().distanceTo(c) > REACH) {
            bb.set(AIKeys.FAIL_REASON, "block out of reach at " + pos.toShortString());
            return Result.FAILED;
        }
        double dx = c.x - p.getX();
        double dy = c.y - p.getEyeY();
        double dz = c.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, pitch);
        if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) > 12.0f || Math.abs(pitch - p.getXRot()) > 12.0f) {
            return Result.BUSY;
        }
        selectBestTool(p, s);
        Direction face = Direction.getApproximateNearest(p.getX() - c.x, p.getEyeY() - c.y, p.getZ() - c.z);
        if (!pos.equals(bb.get(AIKeys.CUR_BREAK))) {
            mc.gameMode.startDestroyBlock(pos, face);
            bb.set(AIKeys.CUR_BREAK, pos);
        } else {
            mc.gameMode.continueDestroyBlock(pos, face);
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