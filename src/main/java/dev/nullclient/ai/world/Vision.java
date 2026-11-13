package dev.nullclient.ai.world;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Legit" vision: instead of reading every loaded block, the AI only knows blocks that rays cast from its eyes
 * have hit. A dense cone follows the camera, and a thin sweep rotates around 360 degrees like glancing around.
 * Seen blocks are remembered for two minutes. When disabled, {@link #allowed} lets everything through.
 */
public final class Vision {
    private static final int COLS = 26;
    private static final int ROWS = 14;
    private static final double FOV_X = 110.0;
    private static final double FOV_Y = 70.0;
    private static final double RANGE = 32.0;
    private static final long EVERY_TICKS = 4;
    private static final long REMEMBER_TICKS = 20L * 120L;
    private static final double SWEEP_STEP = 25.0;
    private static final int MAX_SEEN = 40_000;

    private static final Long2LongOpenHashMap SEEN = new Long2LongOpenHashMap();
    private static boolean enabled;
    private static long lastCast = Long.MIN_VALUE;
    private static long lastNow;
    private static double sweepYaw;

    private Vision() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        SEEN.clear();
        lastCast = Long.MIN_VALUE;
    }

    public static int size() {
        return SEEN.size();
    }

    /** True if vision is off, or the block has been seen recently. */
    public static boolean allowed(BlockPos pos) {
        if (!enabled) {
            return true;
        }
        long key = pos.asLong();
        return SEEN.containsKey(key) && lastNow - SEEN.get(key) <= REMEMBER_TICKS;
    }

    private static Vec3 dir(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }

    private static void cast(ClientLevel level, LocalPlayer p, Vec3 eye, double yaw, double pitch, long now) {
        Vec3 to = eye.add(dir(yaw, pitch).scale(RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.BLOCK) {
            SEEN.put(hit.getBlockPos().asLong(), now);
        }
    }

    /** Call once per tick while the AI is running. */
    public static void update(Minecraft mc) {
        if (!enabled) {
            return;
        }
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            return;
        }
        long now = level.getGameTime();
        lastNow = now;
        if (now - lastCast < EVERY_TICKS) {
            return;
        }
        lastCast = now;
        Vec3 eye = p.getEyePosition();
        double yaw = p.getYRot();
        double pitch = p.getXRot();
        for (int r = 0; r < ROWS; r++) {
            double py = pitch + ((r + 0.5) / ROWS - 0.5) * FOV_Y;
            for (int c = 0; c < COLS; c++) {
                double yy = yaw + ((c + 0.5) / COLS - 0.5) * FOV_X;
                cast(level, p, eye, yy, py, now);
            }
            cast(level, p, eye, sweepYaw, ((r + 0.5) / ROWS - 0.5) * 140.0, now);
        }
        sweepYaw = (sweepYaw + SWEEP_STEP) % 360.0;
        if (SEEN.size() > MAX_SEEN || now % 400 == 0) {
            SEEN.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REMEMBER_TICKS);
        }
    }
}