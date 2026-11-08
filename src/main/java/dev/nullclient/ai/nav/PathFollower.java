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
 *
 * <p>Smooth steering: on straight stretches it aims midway between the current and next node instead of at the
 * current node's centre, so the heading changes gradually; sprint has hysteresis so it does not flicker. It also
 * handles step-ups, descents, swimming, parkour take-off, tunnelling (breaking blocks in the way) and pillaring.
 * On failure it records why, so the planner can route around it and ".ai why" can show it.
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
    private boolean sprinting;
    private String failure = "";

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

    /** Why the last tick returned FAILED. */
    public String failure() {
        return failure;
    }

    /** The node the follower is currently heading for. */
    public PathNode currentNode() {
        return index < path.size() ? path.get(index) : path.last();
    }

    private Result fail(String why) {
        failure = why;
        return Result.FAILED;
    }

    private static String where(PathNode n) {
        return n.move.name().toLowerCase() + " at " + n.x + "," + n.y + "," + n.z;
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
                    return fail("the way changed: " + where(n) + " is no longer valid");
                }
            }
        }

        PathNode t = path.get(index);
        PathNode prev = path.get(index - 1);
        double dx = t.x + 0.5 - pos.x;
        double dz = t.z + 0.5 - pos.z;
        double hd = Math.hypot(dx, dz);

        if (hd > 6.0 || pos.y < t.y - 4.5) {
            return fail("drifted off the route near " + where(t));
        }

        if (t.move.isDig()) {
            for (BlockPos cell : cellsToClear(prev, t)) {
                if (passable(level, cell)) {
                    continue;
                }
                Breaker.Result r = Breaker.step(bb, cell);
                if (r == Breaker.Result.FAILED) {
                    String reason = bb.get(AIKeys.FAIL_REASON);
                    bb.set(AIKeys.FAIL_REASON, null);
                    return fail("could not dig (" + (reason == null ? "unknown" : reason) + ") for " + where(t));
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
            return fail("stuck for " + STUCK_LIMIT + " ticks heading for " + where(t));
        }

        // Smooth steering: aim midway toward the next node on straight stretches.
        boolean nextFlat = index + 1 < path.size() && path.get(index + 1).move.isFlat();
        double ax = t.x + 0.5;
        double az = t.z + 0.5;
        if (t.move.isFlat() && nextFlat) {
            PathNode nx = path.get(index + 1);
            ax = (ax + nx.x + 0.5) * 0.5;
            az = (az + nx.z + 0.5) * 0.5;
        }
        double adx = ax - pos.x;
        double adz = az - pos.z;
        double ahd = Math.hypot(adx, adz);
        float yaw = ahd > 0.05 ? (float) Math.toDegrees(Math.atan2(-adx, adz)) : p.getYRot();
        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, 12.0f);
        if (ahd > 0.05) {
            InputOverride.setDirection(adx / ahd, adz / ahd);
        }

        // Sprint with hysteresis: start on straight flat stretches, stop near the end or when the move changes.
        boolean flatChain = t.move.isFlat() && (index + 1 >= path.size() || path.get(index + 1).move.isFlat());
        if (flatChain && !p.isInWater() && hd > 1.2 && path.size() - index > 1) {
            sprinting = true;
        } else if (!flatChain || hd < 0.6 || p.isInWater()) {
            sprinting = false;
        }

        int actions = InputOverride.FORWARD;
        switch (t.move) {
            case WALK, DIAGONAL, DIG_WALK -> {
                if (sprinting) {
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
                        return fail("no blocks to place for climbing");
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

        // Pause to turn only when almost standing still; never stop a run for a turn.
        float diff = Mth.wrapDegrees(yaw - p.getYRot());
        double speed = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z);
        if (grounded && Math.abs(diff) > 70.0f && speed < 0.1 && !InputOverride.velocityMode() && t.move != MoveType.PILLAR) {
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