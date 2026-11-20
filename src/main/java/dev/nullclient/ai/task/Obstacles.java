package dev.nullclient.ai.task;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds what is in the way on the straight line between two cells: solid blocks at body height, and gaps in the floor. */
public final class Obstacles {
    private Obstacles() {
    }

    public static List<String> between(ClientLevel level, BlockPos a, BlockPos b) {
        List<String> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(2, (int) Math.ceil(len / 0.5));
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int gapRun = 0;
        int gx = 0;
        int gy = 0;
        int gz = 0;
        for (int i = 1; i < steps; i++) {
            double f = i / (double) steps;
            int x = Mth.floor(a.getX() + 0.5 + dx * f);
            int z = Mth.floor(a.getZ() + 0.5 + dz * f);
            int y = Mth.floor(a.getY() + dy * f);
            if ((x == a.getX() && z == a.getZ()) || (x == b.getX() && z == b.getZ())) {
                continue;
            }
            for (int h = 0; h < 2; h++) {
                mp.set(x, y + h, z);
                BlockState s = level.getBlockState(mp);
                if (!s.getCollisionShape(level, mp).isEmpty() && seen.add(mp.asLong()) && out.size() < 6) {
                    out.add(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath() + " at " + x + " " + (y + h) + " " + z);
                }
            }
            boolean floor = false;
            for (int d = 1; d <= 2 && !floor; d++) {
                mp.set(x, y - d, z);
                floor = !level.getBlockState(mp).getCollisionShape(level, mp).isEmpty();
            }
            if (!floor) {
                if (gapRun == 0) {
                    gx = x;
                    gy = y;
                    gz = z;
                }
                gapRun++;
            } else if (gapRun > 0) {
                addGap(out, gapRun, gx, gy, gz);
                gapRun = 0;
            }
        }
        if (gapRun > 0) {
            addGap(out, gapRun, gx, gy, gz);
        }
        return out;
    }

    private static void addGap(List<String> out, int run, int x, int y, int z) {
        int blocks = Math.max(1, Math.round(run * 0.5f));
        out.add("gap of about " + blocks + " block(s) from " + x + " " + y + " " + z + (blocks > 4 ? " (too long to jump)" : ""));
    }
}