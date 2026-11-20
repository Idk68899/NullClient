package dev.nullclient.gui;

import dev.nullclient.ai.world.Perception;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** A top-down map of the highest block in each column near a centre, shaded by height, used to place parkour checkpoints. */
final class CourseMap {
    static final int COLS = 60;
    static final int ROWS = 44;
    static final int NONE = Integer.MIN_VALUE;
    private static final int UNLOADED = 0xFF0A0D16;

    int centerX;
    int centerZ;
    int refY;
    int scale = 1;
    boolean dirty = true;
    long computedAt;
    final int[] colors = new int[COLS * ROWS];
    final int[] tops = new int[COLS * ROWS];

    void recenter(BlockPos p) {
        centerX = p.getX();
        centerZ = p.getZ();
        refY = p.getY();
        dirty = true;
    }

    int worldX(int col) {
        return centerX + (col - COLS / 2) * scale;
    }

    int worldZ(int row) {
        return centerZ + (row - ROWS / 2) * scale;
    }

    int colOf(int x) {
        return Math.floorDiv(x - centerX, scale) + COLS / 2;
    }

    int rowOf(int z) {
        return Math.floorDiv(z - centerZ, scale) + ROWS / 2;
    }

    int top(int col, int row) {
        return tops[row * COLS + col];
    }

    void compute(ClientLevel level, long frame) {
        computedAt = frame;
        dirty = false;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int hi = Math.min(level.getMaxY(), refY + 26);
        int lo = Math.max(level.getMinY(), refY - 34);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int i = r * COLS + c;
                int x = worldX(c);
                int z = worldZ(r);
                colors[i] = UNLOADED;
                tops[i] = NONE;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = hi; y >= lo; y--) {
                    mp.set(x, y, z);
                    BlockState s = level.getBlockState(mp);
                    if (s.isAir()) {
                        continue;
                    }
                    double f = Math.max(0.45, Math.min(1.35, 1.0 + (y - refY) * 0.025));
                    int base = Perception.colorOf(s);
                    int cr = Math.min(255, (int) (((base >> 16) & 0xFF) * f));
                    int cg = Math.min(255, (int) (((base >> 8) & 0xFF) * f));
                    int cb = Math.min(255, (int) ((base & 0xFF) * f));
                    colors[i] = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
                    tops[i] = y;
                    break;
                }
            }
        }
    }
}