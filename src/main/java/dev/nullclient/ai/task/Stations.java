package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.nav.WorldView;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.ai.world.WorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Crafting table and furnace handling: find one nearby, place one from the inventory, walk to it, open it.
 *
 * <p>Placing picks a spot at least a block away from the player (a player standing in the cell blocks placement),
 * looks at it, clicks, and tries the next spot every {@value #CELL_PATIENCE} ticks if nothing appeared.
 */
public final class Stations {
    private static final double REACH = 4.0;
    private static final int CELL_PATIENCE = 14;
    private static final int PLACE_TIMEOUT = 140;
    private static final int OPEN_TIMEOUT = 120;

    private static String lastResult = "none";

    private Stations() {
    }

    public static Status initJob(Blackboard bb) {
        bb.set(AIKeys.CLICKS, null);
        bb.set(AIKeys.STEP_TICKS, 0);
        bb.set(AIKeys.STATION, null);
        bb.set(AIKeys.PLACING, null);
        bb.set(AIKeys.EXPECT, null);
        return Status.SUCCESS;
    }

    private static Status fail(Blackboard bb, String reason) {
        bb.set(AIKeys.FAIL_REASON, reason);
        RunLog.add("station: " + reason);
        return Status.FAILURE;
    }

    /** Heads for the furnace loaded earlier; falls back to any furnace nearby or one from the inventory. */
    public static Status rememberedFurnace(Blackboard bb) {
        ClientLevel level = bb.get(AIKeys.LEVEL);
        BlockPos f = bb.get(AIKeys.FURNACE_POS);
        if (f == null || !MineTasks.idOf(level.getBlockState(f)).equals("furnace")) {
            return findStation(bb, "furnace");
        }
        BlockPos stand = MineTasks.reachCell(new WorldView(level), f);
        if (stand == null) {
            return fail(bb, "cannot reach the furnace at " + f.toShortString());
        }
        bb.set(AIKeys.STATION, f);
        bb.set(AIKeys.GOAL, stand);
        bb.set(AIKeys.PLACING, null);
        bb.set(AIKeys.STEP_TICKS, 0);
        NavTasks.resetNav(bb);
        return Status.SUCCESS;
    }

    /** The skip-th best free cell to put a station in: 1-3 blocks from the player, on solid ground, not in the player. */
    private static BlockPos placementCell(ClientLevel level, LocalPlayer p, int skip) {
        BlockPos o = p.blockPosition();
        List<BlockPos> valid = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos c = o.offset(dx, dy, dz);
                    double hd = Math.hypot(c.getX() + 0.5 - p.getX(), c.getZ() + 0.5 - p.getZ());
                    if (hd < 1.1 || hd > 2.9) {
                        continue;
                    }
                    BlockState cs = level.getBlockState(c);
                    if (!cs.getCollisionShape(level, c).isEmpty() || !cs.getFluidState().isEmpty()) {
                        continue;
                    }
                    BlockPos below = c.below();
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        continue;
                    }
                    valid.add(c);
                }
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        valid.sort(Comparator.comparingDouble(c -> c.distSqr(o)));
        return valid.get(skip % valid.size());
    }

    /** Picks an existing station to walk to, or a spot to place one from the inventory. */
    public static Status findStation(Blackboard bb, String block) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        BlockPos origin = p.blockPosition();
        WorldView view = new WorldView(level);
        bb.set(AIKeys.PLACING, null);
        bb.set(AIKeys.STEP_TICKS, 0);
        for (BlockPos pos : WorldScanner.INSTANCE.find("=" + block, origin, 32, 8)) {
            if (!MineTasks.idOf(level.getBlockState(pos)).equals(block)) {
                continue;
            }
            BlockPos stand = MineTasks.reachCell(view, pos);
            if (stand != null) {
                bb.set(AIKeys.STATION, pos);
                bb.set(AIKeys.GOAL, stand);
                NavTasks.resetNav(bb);
                return Status.SUCCESS;
            }
        }
        if (Inv.count(p, block) == 0) {
            return fail(bb, "no " + block + " nearby and none in inventory");
        }
        BlockPos cell = placementCell(level, p, 0);
        if (cell == null) {
            return fail(bb, "no free space to place a " + block + " near " + origin.toShortString());
        }
        bb.set(AIKeys.STATION, cell);
        bb.set(AIKeys.PLACING, true);
        bb.set(AIKeys.GOAL, origin);
        NavTasks.resetNav(bb);
        return Status.SUCCESS;
    }

    public static boolean nearStation(Blackboard bb) {
        BlockPos st = bb.get(AIKeys.STATION);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        return st != null && p.getEyePosition().distanceTo(Vec3.atCenterOf(st)) <= REACH;
    }

    public static Status placeStation(Blackboard bb, String block) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        BlockPos st = bb.get(AIKeys.STATION);
        if (st == null) {
            return fail(bb, "no spot chosen for the " + block);
        }
        if (MineTasks.idOf(level.getBlockState(st)).equals(block)) {
            bb.set(AIKeys.PLACING, null);
            bb.set(AIKeys.STEP_TICKS, 0);
            return Status.SUCCESS;
        }
        if (!bb.getOrDefault(AIKeys.PLACING, false)) {
            return fail(bb, block + " disappeared");
        }
        int t = bb.getOrDefault(AIKeys.STEP_TICKS, 0) + 1;
        bb.set(AIKeys.STEP_TICKS, t);
        if (t > PLACE_TIMEOUT) {
            return fail(bb, "could not place " + block + " after " + (t / CELL_PATIENCE) + " spots (last click result: " + lastResult + ")");
        }
        if (mc.gameMode == null) {
            return fail(bb, "no game mode available");
        }

        int window = t / CELL_PATIENCE;
        if (window > 0 && t % CELL_PATIENCE == 1) {
            BlockPos next = placementCell(level, p, window);
            if (next != null) {
                bb.set(AIKeys.STATION, next);
                st = next;
            }
        }
        if (!selectItem(p, block)) {
            return Status.RUNNING;
        }

        BlockPos below = st.below();
        Vec3 face = Vec3.atCenterOf(below).add(0.0, 0.5, 0.0);
        double dx = face.x - p.getX();
        double dy = face.y - p.getEyeY();
        double dz = face.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, pitch);
        boolean aimed = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 20.0f && Math.abs(pitch - p.getXRot()) < 20.0f;
        if (!aimed && t % CELL_PATIENCE < 8) {
            return Status.RUNNING;
        }
        if (t % 3 == 0) {
            var result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(face, Direction.UP, below, false));
            lastResult = String.valueOf(result);
            p.swing(InteractionHand.MAIN_HAND);
        }
        return Status.RUNNING;
    }

    public static Status openStation(Blackboard bb, Predicate<AbstractContainerMenu> expected) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        BlockPos st = bb.get(AIKeys.STATION);
        if (expected.test(p.containerMenu)) {
            bb.set(AIKeys.STEP_TICKS, 0);
            return Status.SUCCESS;
        }
        if (st == null) {
            return fail(bb, "no station to open");
        }
        int t = bb.getOrDefault(AIKeys.STEP_TICKS, 0) + 1;
        bb.set(AIKeys.STEP_TICKS, t);
        if (t > OPEN_TIMEOUT || mc.gameMode == null) {
            return fail(bb, "could not open the station at " + st.toShortString() + " (last click result: " + lastResult + ")");
        }
        Vec3 c = Vec3.atCenterOf(st);
        double dx = c.x - p.getX();
        double dy = c.y - p.getEyeY();
        double dz = c.z - p.getZ();
        bb.set(AIKeys.YAW, (float) Math.toDegrees(Math.atan2(-dx, dz)));
        bb.set(AIKeys.PITCH, (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        if (t % 8 == 0) {
            Direction face = Direction.getApproximateNearest(p.getX() - c.x, p.getEyeY() - c.y, p.getZ() - c.z);
            Vec3 hit = new Vec3(c.x + face.getStepX() * 0.5, c.y + face.getStepY() * 0.5, c.z + face.getStepZ() * 0.5);
            var result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, st, false));
            lastResult = String.valueOf(result);
        }
        return Status.RUNNING;
    }

    public static Status closeMenu(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        if (p.containerMenu != p.inventoryMenu) {
            p.closeContainer();
        }
        bb.set(AIKeys.CLICKS, null);
        return Status.SUCCESS;
    }

    /** Puts the item in the selected hotbar slot. Returns true once it is in hand. */
    private static boolean selectItem(LocalPlayer p, String itemId) {
        Minecraft mc = Minecraft.getInstance();
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (Inv.idOf(inv.getItem(i)).equals(itemId)) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (Slot sl : p.inventoryMenu.slots) {
            if (sl.container == inv && sl.getContainerSlot() >= 9 && sl.getContainerSlot() < 36
                    && sl.hasItem() && Inv.idOf(sl.getItem()).equals(itemId)) {
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, sl.index, inv.getSelectedSlot(), ContainerInput.SWAP, p);
                return false;
            }
        }
        return false;
    }
}