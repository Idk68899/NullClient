package dev.nullclient.ai.nav;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a {@link Path} by emitting movement actions and a target view angle into the blackboard.
 * Handles sprinting, step-ups, descents, swimming and take-off timing for parkour jumps; reports failure when
 * the player is stuck, off-route, or the upcoming nodes are no longer safe.
 */
public final class PathFollower {
    public enum Result { RUNNING, DONE, FAILED }

    private static final int STUCK_LIMIT = 60;
    private static final int VALIDATE_EVERY = 8;

    private final Path path;
    private int index = 1;
    private int consumed;
    private int stuck;
    private double bestDistance = Double.MAX_VALUE;
    private int age;

    public PathFollower(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public int consumed() {
        return consumed;
    }

    public int index() {
        return index;
    }

    public Result tick(LocalPlayer p, ClientLevel level, Blackboard bb) {
        age++;
        if (path.size() < 2) {
            return Result.DONE;
        }
        Vec3 pos = p.position();
        boolean grounded = p.onGround() || p.isInWater();

        while (index < path.size()) {
            PathNode t = path.get(index);
            double hd = Math.hypot(t.x + 0.5 - pos.x, t.z + 0.5 - pos.z);
            boolean yOk = Math.abs(pos.y - t.y) < 0.9;
            boolean reached = hd < 0.4 && yOk && grounded;
            if (!reached && hd < 0.7 && yOk && index + 1 < path.size() && t.move.isFlat() && path.get(index + 1).move.isFlat()) {
                reached = true;
            }
            if (!reached) {
                break;
            }
            index++;
            consumed++;
            stuck = 0;
            bestDistance = Double.MAX_VALUE;
        }
        if (index >= path.size()) {
            return Result.DONE;
        }

        if (age % VALIDATE_EVERY == 0) {
            WorldView view = new WorldView(level);
            for (int i = index; i < Math.min(path.size(), index + 3); i++) {
                PathNode n = path.get(i);
                if (!view.canStand(n.x, n.y, n.z)) {
                    return Result.FAILED;
                }
            }
        }

        PathNode t = path.get(index);
        PathNode prev = path.get(index - 1);
        double dx = t.x + 0.5 - pos.x;
        double dz = t.z + 0.5 - pos.z;
        double hd = Math.hypot(dx, dz);

        if (hd > 6.0 || pos.y < t.y - 4.5) {
            return Result.FAILED;
        }
        double metric = hd + Math.abs(pos.y - t.y) * 0.5;
        if (metric < bestDistance - 0.02) {
            bestDistance = metric;
            stuck = 0;
        } else if (++stuck > STUCK_LIMIT) {
            return Result.FAILED;
        }

        float yaw = hd > 0.05 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : p.getYRot();
        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, 12.0f);

        int actions = InputOverride.FORWARD;
        switch (t.move) {
            case WALK, DIAGONAL -> {
                if (hd > 1.0 && !p.isInWater()) {
                    actions |= InputOverride.SPRINT;
                }
            }
            case STEP_UP -> {
                if (grounded && hd < 1.25 && pos.y < t.y - 0.05) {
                    actions |= InputOverride.JUMP;
                }
            }
            case PARKOUR -> {
                actions |= InputOverride.SPRINT;
                double sx = Integer.signum(t.x - prev.x);
                double sz = Integer.signum(t.z - prev.z);
                double progress = (pos.x - (prev.x + 0.5)) * sx + (pos.z - (prev.z + 0.5)) * sz;
                if (p.onGround() && progress >= 0.35) {
                    actions |= InputOverride.JUMP;
                }
            }
            default -> {
            }
        }
        if (p.isInWater() || p.isInLava()) {
            actions |= InputOverride.JUMP;
        }
        float diff = Mth.wrapDegrees(yaw - p.getYRot());
        if (grounded && Math.abs(diff) > 50.0f) {
            actions = 0;
        }
        AIKeys.addActions(bb, actions);
        return Result.RUNNING;
    }
}