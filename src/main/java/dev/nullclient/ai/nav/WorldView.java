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

/** Cached, classified view of the client level for pathfinding. Create a new instance per search. */
public final class WorldView {
    private static final int PASSABLE = 1;
    private static final int SOLID_TOP = 2;
    private static final int HAZARD = 4;
    private static final int WATER = 8;

    private static final Set<Block> HAZARD_BLOCKS = Set.of(
            Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.WITHER_ROSE,
            Blocks.LAVA, Blocks.LAVA_CAULDRON, Blocks.POINTED_DRIPSTONE);

    private final ClientLevel level;
    private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
    private final Long2IntOpenHashMap cache = new Long2IntOpenHashMap();

    public WorldView(ClientLevel level) {
        this.level = level;
        this.cache.defaultReturnValue(-1);
    }

    private int flags(int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        int v = cache.get(k);
        if (v == -1) {
            v = compute(x, y, z);
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
        if (!empty && !hazard) {
            double top = shape.max(Direction.Axis.Y);
            if (top >= 0.999 && top <= 1.001) {
                f |= SOLID_TOP;
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

    /** A two-block-tall player can stand with feet in this cell. */
    public boolean canStand(int x, int y, int z) {
        if (!bodyFree(x, y, z) || !bodyFree(x, y + 1, z)) {
            return false;
        }
        return (flags(x, y - 1, z) & SOLID_TOP) != 0 || isWater(x, y, z);
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
}