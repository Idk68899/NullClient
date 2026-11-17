package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Low-health shelter: at 3.5 hearts or less with hostile mobs close by, walls the player in (4 blocks at foot
 * level, then 4 at head level), waits inside (the caller lets it eat), then breaks one block to get out once healed.
 * Needs at least 8 placeable blocks. Never used in creative.
 */
public final class Shelter {
    private enum S { IDLE, BUILD, WAIT, OUT }

    private static final float LOW_HEALTH = 7.0f;
    private static final float HEALED = 16.0f;

    private S s = S.IDLE;
    private final List<BlockPos> cells = new ArrayList<>();
    private int idx;
    private int cellTicks;
    private int waited;
    private int cooldown;
    private BlockPos exit;

    public boolean active() {
        return s != S.IDLE;
    }

    /** True while sitting inside the finished walls (the right time to eat). */
    public boolean waiting() {
        return s == S.WAIT;
    }

    public void clear() {
        s = S.IDLE;
        cells.clear();
        cooldown = 0;
    }

    private static boolean solid(ClientLevel level, BlockPos c) {
        return !level.getBlockState(c).getCollisionShape(level, c).isEmpty();
    }

    private static boolean hostileNear(ClientLevel level, LocalPlayer p, double r) {
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r))) {
            if (e != p && !(e instanceof Player) && e.isAlive() && e.getType().getCategory() == MobCategory.MONSTER) {
                return true;
            }
        }
        return false;
    }

    /** Returns true while it is in control of the player this tick. */
    public boolean tick(Blackboard bb, LocalPlayer p, ClientLevel level) {
        if (cooldown > 0) {
            cooldown--;
        }
        switch (s) {
            case IDLE -> {
                if (p.getHealth() <= LOW_HEALTH && cooldown == 0 && p.onGround() && !Modes.creative()
                        && Placer.count(p) >= 8 && hostileNear(level, p, 8.0)) {
                    BlockPos o = p.blockPosition();
                    cells.clear();
                    int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] v : d) {
                        cells.add(o.offset(v[0], 0, v[1]));
                    }
                    for (int[] v : d) {
                        cells.add(o.offset(v[0], 1, v[1]));
                    }
                    idx = 0;
                    cellTicks = 0;
                    s = S.BUILD;
                    RunLog.add("low health (" + Math.round(p.getHealth()) + ") with mobs near - walling myself in at " + o.toShortString());
                }
                return false;
            }
            case BUILD -> {
                while (idx < cells.size() && solid(level, cells.get(idx))) {
                    idx++;
                }
                if (idx >= cells.size()) {
                    s = S.WAIT;
                    waited = 0;
                    return true;
                }
                BlockPos c = cells.get(idx);
                BlockPos below = c.below();
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) || ++cellTicks > 16) {
                    idx++;
                    cellTicks = 0;
                    return true;
                }
                Vec3 face = Vec3.atCenterOf(below).add(0.0, 0.5, 0.0);
                double dx = face.x - p.getX();
                double dy = face.y - p.getEyeY();
                double dz = face.z - p.getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                bb.set(AIKeys.YAW, yaw);
                bb.set(AIKeys.PITCH, pitch);
                boolean aimed = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 20.0f && Math.abs(pitch - p.getXRot()) < 20.0f;
                if (aimed && cellTicks % 2 == 0) {
                    Placer.placeInto(bb, c);
                }
                return true;
            }
            case WAIT -> {
                waited++;
                boolean healed = p.getHealth() >= HEALED && waited > 40;
                boolean calm = p.getHealth() >= 10.0f && waited > 100 && !hostileNear(level, p, 12.0);
                if (healed || calm || waited > 1400) {
                    exit = cells.isEmpty() ? null : cells.get(0);
                    s = S.OUT;
                    RunLog.add("healed to " + Math.round(p.getHealth()) + " - breaking out");
                }
                return true;
            }
            case OUT -> {
                if (exit == null || !solid(level, exit)) {
                    s = S.IDLE;
                    cooldown = 400;
                    return false;
                }
                if (Breaker.step(bb, exit) == Breaker.Result.FAILED) {
                    s = S.IDLE;
                    cooldown = 200;
                    return false;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}