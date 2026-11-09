package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Climbs one block by pillaring: put a block in hand, look down, jump, place a block into the cell just left,
 * and stand on it. Each sub-step has a patience limit and a specific failure message.
 * One instance handles consecutive pillar steps; it resets itself when the column changes.
 */
public final class Pillar {
    public enum Result { BUSY, DONE, FAILED }

    private static final int LOOK_PATIENCE = 14;
    private static final int EQUIP_PATIENCE = 40;
    private static final int GROUND_PATIENCE = 16;
    private static final int MAX_MISSES = 4;

    private long column = Long.MIN_VALUE;
    private int lookWait;
    private int equipWait;
    private int groundTicks;
    private int misses;
    private boolean wasAir;
    private String failure = "";

    public String failure() {
        return failure;
    }

    private Result fail(String why) {
        failure = why;
        return Result.FAILED;
    }

    /** One tick of climbing from standing in cell (x, baseY, z) to standing one block higher. */
    public Result step(Blackboard bb, LocalPlayer p, ClientLevel level, int x, int baseY, int z) {
        long key = BlockPos.asLong(x, baseY, z);
        if (key != column) {
            column = key;
            lookWait = 0;
            equipWait = 0;
            groundTicks = 0;
            misses = 0;
            wasAir = false;
        }
        BlockPos cell = new BlockPos(x, baseY, z);
        boolean solid = !level.getBlockState(cell).getCollisionShape(level, cell).isEmpty();
        Vec3 pos = p.position();

        if (solid && p.onGround() && pos.y >= baseY + 1 - 0.05) {
            return Result.DONE;
        }

        bb.set(AIKeys.PITCH, 90.0f);
        InputOverride.clearDirection();

        if (!Placer.hasBlocks(p)) {
            return fail("no blocks to place for climbing");
        }
        if (!Placer.inHand(p)) {
            Placer.equip(p);
            if (++equipWait > EQUIP_PATIENCE) {
                return fail("could not put a building block in hand");
            }
            return Result.BUSY;
        }
        if (p.getXRot() < 70.0f && lookWait++ < LOOK_PATIENCE) {
            return Result.BUSY;
        }

        if (p.onGround()) {
            if (wasAir && !solid) {
                if (++misses >= MAX_MISSES) {
                    return fail("placing a block under me did not work " + MAX_MISSES + " times");
                }
            }
            wasAir = false;
            if (solid) {
                return Result.BUSY;
            }
            if (++groundTicks > GROUND_PATIENCE) {
                return fail("jumping did not lift me off the ground");
            }
            AIKeys.addActions(bb, InputOverride.JUMP);
            InputOverride.forceJump();
            return Result.BUSY;
        }

        groundTicks = 0;
        wasAir = true;
        if (!solid && pos.y >= baseY + 1.0 + 0.02) {
            Placer.placeInto(bb, cell);
        }
        return Result.BUSY;
    }
}