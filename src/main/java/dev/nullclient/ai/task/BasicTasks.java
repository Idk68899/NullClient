package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Sequence;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.bt.Task;
import dev.nullclient.ai.nav.WorldView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** "Get down" (reach the nearest lower ground) and "get out" (reach the nearest spot that sees the sky). */
public final class BasicTasks {
    private static final int RADIUS = 14;
    private static final int OUT_RADIUS = 20;

    private BasicTasks() {
    }

    public static Node downTree() {
        return new Sequence("Down", true, new Task("FindGround", BasicTasks::findGround), NavTasks.gotoTree());
    }

    public static Node upTree() {
        return new Sequence("Up", true, new Task("FindSurface", BasicTasks::findSurface), NavTasks.gotoTree());
    }

    static Status findGround(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        WorldView view = new WorldView(level);
        BlockPos o = p.blockPosition();
        int minY = Math.max(level.getMinY(), o.getY() - 90);
        List<BlockPos> cands = new ArrayList<>();
        int lowest = Integer.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = o.getX() + dx;
                int z = o.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = o.getY() - 2; y >= minY; y--) {
                    if (view.canStand(x, y, z) && !view.nearHazard(x, y, z)) {
                        cands.add(new BlockPos(x, y, z));
                        lowest = Math.min(lowest, y);
                        break;
                    }
                }
            }
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos c : cands) {
            if (c.getY() > lowest + 2) {
                continue;
            }
            double d = Math.pow(c.getX() - o.getX(), 2) + Math.pow(c.getZ() - o.getZ(), 2);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        if (best == null) {
            bb.set(AIKeys.FAIL_REASON, "no lower ground found within " + RADIUS + " blocks");
            RunLog.add("get down: no lower ground within " + RADIUS + " blocks of " + o.toShortString());
            return Status.FAILURE;
        }
        RunLog.add("get down: heading for " + best.toShortString() + " (" + (o.getY() - best.getY()) + " blocks lower)");
        bb.set(AIKeys.GOAL, best);
        NavTasks.resetNav(bb);
        return Status.SUCCESS;
    }

    static Status findSurface(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        WorldView view = new WorldView(level);
        BlockPos o = p.blockPosition();
        if (level.canSeeSky(o)) {
            bb.set(AIKeys.GOAL, o);
            RunLog.add("get out: already under the open sky");
            return Status.SUCCESS;
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        int maxY = Math.min(level.getMaxY(), o.getY() + 48);
        for (int dx = -OUT_RADIUS; dx <= OUT_RADIUS; dx++) {
            for (int dz = -OUT_RADIUS; dz <= OUT_RADIUS; dz++) {
                int x = o.getX() + dx;
                int z = o.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = maxY; y >= o.getY() - 4; y--) {
                    if (view.canStand(x, y, z)) {
                        BlockPos c = new BlockPos(x, y, z);
                        if (level.canSeeSky(c) && !view.nearHazard(x, y, z)) {
                            double dy = (y - o.getY()) * 2.0;
                            double d = dx * dx + dz * dz + dy * dy;
                            if (d < bd) {
                                bd = d;
                                best = c;
                            }
                        }
                        break;
                    }
                }
            }
        }
        if (best == null) {
            bb.set(AIKeys.FAIL_REASON, "no open sky found within " + OUT_RADIUS + " blocks");
            RunLog.add("get out: no open sky within " + OUT_RADIUS + " blocks of " + o.toShortString());
            return Status.FAILURE;
        }
        RunLog.add("get out: heading for " + best.toShortString());
        bb.set(AIKeys.GOAL, best);
        NavTasks.resetNav(bb);
        return Status.SUCCESS;
    }
}