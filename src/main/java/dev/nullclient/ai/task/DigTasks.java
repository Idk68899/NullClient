package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.nav.MoveType;
import dev.nullclient.ai.nav.Path;
import dev.nullclient.ai.nav.PathFollower;
import dev.nullclient.ai.nav.WorldView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy tunnelling toward a buried target, one move at a time, with no search: a staircase down while the
 * target is deeper than the player, a staircase up while it is higher, otherwise a straight tunnel.
 * Cells touching lava, water or fire are never opened, and the target block itself is never dug as part of the tunnel.
 */
public final class DigTasks {
    private static final int MAX_STEPS = 200;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private DigTasks() {
    }

    static Status digToward(Blackboard bb) {
        bb.set(AIKeys.TUNNEL, true);
        BlockPos target = bb.get(AIKeys.TARGET);
        if (target == null) {
            return Status.RUNNING;
        }
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        PathFollower follower = bb.get(AIKeys.FOLLOWER);
        if (follower == null) {
            int steps = bb.getOrDefault(AIKeys.DIG_STEPS, 0) + 1;
            if (steps > MAX_STEPS) {
                bb.set(AIKeys.FAIL_REASON, "tunnel to " + target.toShortString() + " took too many steps");
                return Status.FAILURE;
            }
            bb.set(AIKeys.DIG_STEPS, steps);
            Path next = chooseStep(new WorldView(level), p.blockPosition(), target);
            if (next == null) {
                bb.set(AIKeys.FAIL_REASON, "cannot tunnel toward " + target.toShortString());
                return Status.FAILURE;
            }
            follower = new PathFollower(next);
            bb.set(AIKeys.PATH, next);
            bb.set(AIKeys.FOLLOWER, follower);
        }
        PathFollower.Result r = follower.tick(p, level, bb);
        if (r != PathFollower.Result.RUNNING) {
            bb.set(AIKeys.PATH, null);
            bb.set(AIKeys.FOLLOWER, null);
        }
        return Status.RUNNING;
    }

    private static Path chooseStep(WorldView v, BlockPos c, BlockPos t) {
        int x = c.getX();
        int y = c.getY();
        int z = c.getZ();
        int ddx = t.getX() - x;
        int ddz = t.getZ() - z;
        int ddy = t.getY() - y;

        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(
                -(DIRS[a][0] * ddx + DIRS[a][1] * ddz),
                -(DIRS[b][0] * ddx + DIRS[b][1] * ddz)));

        List<MoveType> prefs = new ArrayList<>(2);
        if (ddy <= -2) {
            prefs.add(MoveType.DIG_DOWN);
            prefs.add(MoveType.DIG_WALK);
        } else if (ddy >= 2) {
            prefs.add(MoveType.DIG_UP);
            prefs.add(MoveType.DIG_WALK);
        } else {
            prefs.add(MoveType.DIG_WALK);
        }
        for (MoveType m : prefs) {
            for (int i : order) {
                int nx = x + DIRS[i][0];
                int nz = z + DIRS[i][1];
                if (valid(v, m, x, y, z, nx, nz, t)) {
                    int ny = m == MoveType.DIG_DOWN ? y - 1 : m == MoveType.DIG_UP ? y + 1 : y;
                    return Path.singleStep(x, y, z, m, nx, ny, nz);
                }
            }
        }
        return null;
    }

    private static boolean ok(WorldView v, BlockPos t, int x, int y, int z) {
        return !(x == t.getX() && y == t.getY() && z == t.getZ()) && v.digCost(x, y, z) >= 0;
    }

    private static boolean valid(WorldView v, MoveType m, int x, int y, int z, int nx, int nz, BlockPos t) {
        return switch (m) {
            case DIG_WALK -> ok(v, t, nx, y, nz) && ok(v, t, nx, y + 1, nz) && v.isSolidTop(nx, y - 1, nz);
            case DIG_DOWN -> ok(v, t, nx, y + 1, nz) && ok(v, t, nx, y, nz) && ok(v, t, nx, y - 1, nz)
                    && v.isSolidTop(nx, y - 2, nz);
            case DIG_UP -> ok(v, t, x, y + 2, z) && ok(v, t, nx, y + 2, nz) && ok(v, t, nx, y + 1, nz)
                    && v.isSolidTop(nx, y, nz);
            default -> false;
        };
    }
}