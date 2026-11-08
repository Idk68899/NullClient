package dev.nullclient.ai.nav;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.task.Breaker;
import dev.nullclient.ai.task.Placer;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a {@link Path} by emitting movement actions and a target view angle into the blackboard.
 * Handles sprinting, step-ups, descents, swimming, parkour take-off, tunnelling (breaking blocks in the way,
 * always the first block along the line of sight) and pillaring (jump, then place a block underneath).
 * It waits briefly to turn toward the next node but never forever. Reports failure when stuck, off-route,
 * or when upcoming nodes are no longer safe.
 */
public final class PathFollower {
    public enum Result { RUNNING, DONE, FAILED }

    private static final int STUCK_LIMIT = 60;
    private static final int VALIDATE_EVERY = 8;
    private static final int TURN_PATIENCE = 15;

    private final Path path;
    private int index = 1;
    private int consumed;
    private int stuck;
    private int turnWait;
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

    private static boolean passable(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Cells that must be empty before the player can make this dig move. */
    private static List<BlockPos> cellsToClear(PathNode prev, PathNode t) {
        List<BlockPos> out = new ArrayList<>(3);
        switch (t.move) {
            case DIG_WALK -> {
                out.add(new BlockPos(t.x, t.y + 1, t.z));
                out.add(new BlockPos(t.x, t.y, t.z));
            }
            case DIG_DOWN -> {
                out.add(new BlockPos(t.x, t.y + 2, t.z));
                out.add(new BlockPos(t.x, t.y + 1, t.z));
                out.add(new BlockPos(t.x, t.y, t.z));
            }
            case DIG_UP -> {
                out.add(new BlockPos(prev.x, prev.y + 2, prev.z));
                out.add(new BlockPos(t.x, t.y + 1, t.z));
                out.add(new BlockPos(t.x, t.y, t.z));
            }
            default -> {
            }
        }
        return out;
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
                boolean ok;
                if (n.move == MoveType.PILLAR) {
                    ok = view.bodyFree(n.x, n.y, n.z) && view.bodyFree(n.x, n.y + 1, n.z);
                } else if (n.move.isDig()) {
                    ok = view.clearable(n.x, n.y, n.z) && view.clearable(n.x, n.y + 1, n.z);
                } else {
                    ok = view.canStand(n.x, n.y, n.z);
                }
                if (!ok) {
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

        if (t.move.isDig()) {
            for (BlockPos cell : cellsToClear(prev, t)) {
                if (passable(level, cell)) {
                    continue;
                }
                dev.nullclient.ai.task.Breaker.Result r = Breaker.step(bb, cell);
                if (r == Breaker.Result.FAILED) {
                    return Result.FAILED;
                }
                stuck = 0;
                if (r == Breaker.Result.BUSY) {
                    return Result.RUNNING;
                }
            }
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
        if (hd > 0.05) {
            InputOverride.setDirection(dx / hd, dz / hd);
        }

        int actions = InputOverride.FORWARD;
        switch (t.move) {
            case WALK, DIAGONAL, DIG_WALK -> {
                if (hd > 1.0 && !p.isInWater()) {
                    actions |= InputOverride.SPRINT;
                }
            }
            case STEP_UP, DIG_UP -> {
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
            case SWIM -> {
                if (hd < 0.3) {
                    actions = 0;
                }
            }
            case PILLAR -> {
                // stand still, look down, jump, and place a block into the cell we just left once we are above it
                actions = 0;
                InputOverride.clearDirection();
                bb.set(AIKeys.PITCH, 90.0f);
                if (p.onGround() && pos.y < t.y - 0.05) {
                    actions |= InputOverride.JUMP;
                }
                if (!p.onGround() && pos.y >= prev.y + 1.02) {
                    if (Placer.count(p) == 0) {
                        bb.set(AIKeys.FAIL_REASON, "no blocks to place for climbing");
                        return Result.FAILED;
                    }
                    Placer.placeInto(bb, new BlockPos(prev.x, prev.y, prev.z));
                }
            }
            default -> {
            }
        }

        // Water: rise or sink toward the next node's height instead of always floating up.
        if (p.isInLava()) {
            actions |= InputOverride.JUMP;
        } else if (p.isInWater() && t.move != MoveType.PILLAR) {
            double dyTarget = t.y - pos.y;
            if (dyTarget > 0.3) {
                actions |= InputOverride.JUMP;
            } else if (dyTarget < -0.3) {
                actions = (actions & ~InputOverride.JUMP) | InputOverride.SNEAK;
            }
        }

        // Wait a moment to turn toward the next node, but never forever.
        float diff = Mth.wrapDegrees(yaw - p.getYRot());
        if (grounded && Math.abs(diff) > 50.0f && !InputOverride.velocityMode() && t.move != MoveType.PILLAR) {
            if (turnWait < TURN_PATIENCE) {
                turnWait++;
                actions = 0;
            }
        } else {
            turnWait = 0;
        }
        AIKeys.addActions(bb, actions);
        return Result.RUNNING;
    }
}