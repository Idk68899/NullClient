package dev.nullclient.ai.nav;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Set;

/**
 * Cached, classified view of the client level for pathfinding. Create a new instance per search.
 * Cells in the optional {@code blocked} set (positions where earlier routes failed) are treated as solid walls
 * that cannot be stood in, passed or dug.
 */
public final class WorldView {
    private static final int PASSABLE = 1;
    private static final int SOLID_TOP = 2;
    private static final int HAZARD = 4;
    private static final int WATER = 8;
    private static final int FLUID = 16;
    private static final int SOLID = 32;
    private static final int PROTECTED = 64;

    private static final Set<Block> HAZARD_BLOCKS = Set.of(
            Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.WITHER_ROSE,
            Blocks.LAVA, Blocks.LAVA_CAULDRON, Blocks.POINTED_DRIPSTONE);

    /** Never dug through, regardless of hardness. */
    private static final Set<Block> PROTECTED_BLOCKS = Set.of(
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CRAFTING_TABLE, Blocks.CHEST,
            Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST, Blocks.SPAWNER, Blocks.BEACON,
            Blocks.END_PORTAL_FRAME);

    private final ClientLevel level;
    private final Set<Long> blocked;
    private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
    private final Long2IntOpenHashMap cache = new Long2IntOpenHashMap();

    public WorldView(ClientLevel level) {
        this(level, null);
    }

    public WorldView(ClientLevel level, Set<Long> blocked) {
        this.level = level;
        this.blocked = blocked;
        this.cache.defaultReturnValue(-1);
    }

    private int flags(int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        int v = cache.get(k);
        if (v == -1) {
            v = blocked != null && blocked.contains(k) ? 0 : compute(x, y, z);
            cache.put(k, v);
        }
        return v;
    }

    private int compute(int x, int y, int z) {
        if (y < level.getMinY() || y > level.getMaxY() || !level.hasChunk(x >> 4, z >> 4)) {
            return 0;
        }
        mp.set(x, y, z);
        BlockState s = level.getBlockState(mp);
        FluidState fluid = s.getFluidState();
        boolean lava = fluid.is(FluidTags.LAVA);
        boolean water = fluid.is(FluidTags.WATER);
        boolean hazard = lava || HAZARD_BLOCKS.contains(s.getBlock());
        VoxelShape shape = s.getCollisionShape(level, mp);
        boolean empty = shape.isEmpty();
        int f = 0;
        if (empty && !hazard) {
            f |= PASSABLE;
        }
        if (hazard) {
            f |= HAZARD;
        }
        if (water) {
            f |= WATER;
        }
        if (water || lava) {
            f |= FLUID;
        }
        if (!empty) {
            f |= SOLID;
            float hardness = s.getDestroySpeed(level, mp);
            if (hardness < 0.0f || hardness > 20.0f || PROTECTED_BLOCKS.contains(s.getBlock())) {
                f |= PROTECTED;
            }
            if (!hazard) {
                double top = shape.max(Direction.Axis.Y);
                if (top >= 0.999 && top <= 1.001) {
                    f |= SOLID_TOP;
                }
            }
        }
        return f;
    }

    /** The body (one block cell) can occupy this cell: no collision, no hazard, chunk loaded. */
    public boolean bodyFree(int x, int y, int z) {
        return (flags(x, y, z) & PASSABLE) != 0;
    }

    public boolean isWater(int x, int y, int z) {
        return (flags(x, y, z) & WATER) != 0;
    }

    public boolean isSolidTop(int x, int y, int z) {
        return (flags(x, y, z) & SOLID_TOP) != 0;
    }

    /** A two-block-tall player can stand (or float, in water) with feet in this cell. */
    public boolean canStand(int x, int y, int z) {
        if (!bodyFree(x, y, z) || !bodyFree(x, y + 1, z)) {
            return false;
        }
        return isSolidTop(x, y - 1, z) || isWater(x, y, z);
    }

    /** Hazard (lava, fire, cactus, ...) adjacent at feet or floor level. */
    public boolean nearHazard(int x, int y, int z) {
        for (int i = 0; i < 4; i++) {
            int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0);
            int nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
            if ((flags(nx, y, nz) & HAZARD) != 0 || (flags(nx, y - 1, nz) & HAZARD) != 0) {
                return true;
            }
        }
        return false;
    }

    /** True if the cell itself or any of its six neighbours is lava, fire or another hazard. Water does not count. */
    public boolean touchesHazard(int x, int y, int z) {
        return (flags(x, y, z) & HAZARD) != 0
                || (flags(x + 1, y, z) & HAZARD) != 0 || (flags(x - 1, y, z) & HAZARD) != 0
                || (flags(x, y + 1, z) & HAZARD) != 0 || (flags(x, y - 1, z) & HAZARD) != 0
                || (flags(x, y, z + 1) & HAZARD) != 0 || (flags(x, y, z - 1) & HAZARD) != 0;
    }

    /** No fluid or hazard touches any face of this cell, so opening it cannot flood or burn the tunnel. */
    private boolean sealed(int x, int y, int z) {
        int mask = FLUID | HAZARD;
        return (flags(x + 1, y, z) & mask) == 0 && (flags(x - 1, y, z) & mask) == 0
                && (flags(x, y + 1, z) & mask) == 0 && (flags(x, y - 1, z) & mask) == 0
                && (flags(x, y, z + 1) & mask) == 0 && (flags(x, y, z - 1) & mask) == 0;
    }

    /** 0 if the cell is already free, 1 if it must be dug (and is safe to), -1 if it cannot be cleared. */
    public int digCost(int x, int y, int z) {
        int f = flags(x, y, z);
        if ((f & PASSABLE) != 0) {
            return 0;
        }
        if ((f & SOLID) != 0 && (f & (HAZARD | PROTECTED | FLUID)) == 0 && sealed(x, y, z)) {
            return 1;
        }
        return -1;
    }

    public boolean clearable(int x, int y, int z) {
        return digCost(x, y, z) >= 0;
    }
}