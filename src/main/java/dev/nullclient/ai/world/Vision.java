package dev.nullclient.ai.world;

import dev.nullclient.ai.task.Combat;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Legit" vision: instead of reading every loaded block and mob, the AI only knows what it has seen. The camera view
 * (see {@link Perception}) marks the blocks and mobs in front of it; a sweep rotates around 360 degrees like glancing
 * around, including steep downward angles; mobs within 8 blocks with a clear line are noticed too. Seen blocks are
 * remembered for two minutes and seen mobs for four seconds. When disabled everything is known.
 */
public final class Vision {
    private static final double RANGE = 32.0;
    private static final long EVERY_TICKS = 4;
    private static final long REMEMBER_TICKS = 20L * 120L;
    private static final long MOB_MEMORY_TICKS = 80L;
    private static final double NEAR_MOB = 8.0;
    private static final double SWEEP_STEP = 25.0;
    private static final int SWEEP_ROWS = 16;
    private static final double SWEEP_PITCH_MIN = -30.0;
    private static final double SWEEP_PITCH_MAX = 89.0;
    private static final int MAX_SEEN = 40_000;

    private static final Long2LongOpenHashMap SEEN = new Long2LongOpenHashMap();
    private static final Int2LongOpenHashMap MOBS = new Int2LongOpenHashMap();
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
        MOBS.clear();
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

    /** True if vision is off, or this mob has been seen in the last few seconds. */
    public static boolean knowsMob(LivingEntity e) {
        if (!enabled) {
            return true;
        }
        int id = e.getId();
        return MOBS.containsKey(id) && lastNow - MOBS.get(id) <= MOB_MEMORY_TICKS;
    }

    static void markSeen(long key, long now) {
        if (enabled) {
            SEEN.put(key, now);
            if (now > lastNow) {
                lastNow = now;
            }
        }
    }

    static void noteMob(int id, long now) {
        if (enabled) {
            MOBS.put(id, now);
        }
    }

    static Vec3 dir(double yawDeg, double pitchDeg) {
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
        Perception.update(mc);
        if (now >= lastCast && now - lastCast < EVERY_TICKS) {
            return;
        }
        lastCast = now;
        Vec3 eye = p.getEyePosition();
        for (int r = 0; r < SWEEP_ROWS; r++) {
            double sp = SWEEP_PITCH_MIN + (r + 0.5) / SWEEP_ROWS * (SWEEP_PITCH_MAX - SWEEP_PITCH_MIN);
            cast(level, p, eye, sweepYaw, sp, now);
            cast(level, p, eye, sweepYaw + 180.0, sp, now);
        }
        sweepYaw = (sweepYaw + SWEEP_STEP) % 360.0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(NEAR_MOB))) {
            if (e != p && e.isAlive() && Combat.sees(level, p, e)) {
                MOBS.put(e.getId(), now);
            }
        }
        if (SEEN.size() > MAX_SEEN || now % 400 == 0) {
            SEEN.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > REMEMBER_TICKS);
            MOBS.int2LongEntrySet().removeIf(e -> now - e.getLongValue() > MOB_MEMORY_TICKS * 3);
        }
    }
}