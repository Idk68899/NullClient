package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Condition;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Selector;
import dev.nullclient.ai.bt.Sequence;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.bt.Task;
import dev.nullclient.ai.nav.WorldView;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.ai.plan.Knowledge;
import dev.nullclient.ai.plan.Step;
import dev.nullclient.ai.world.WorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Turns planner steps into behavior-tree nodes: gather, craft (inventory grid or crafting table), smelt. */
public final class StepTasks {
    private static final double REACH = 4.0;

    public record Click(int slot, int button, ClickType type) {
    }

    public static final class ClickQueue {
        final Deque<Click> q = new ArrayDeque<>();
        boolean built;
        boolean collecting;
        int settle;
        int waited;
    }

    private StepTasks() {
    }

    public static Node node(Step s) {
        return switch (s.kind()) {
            case GATHER -> gather(s);
            case CRAFT -> craft(s);
            case SMELT -> smelt(s);
        };
    }

    // ------------------------------------------------------------------ gather

    private static Node gather(Step s) {
        Knowledge k = Knowledge.get();
        StringBuilder spec = new StringBuilder();
        for (String b : s.gather().blocks) {
            for (String m : k.members(b)) {
                if (spec.length() > 0) {
                    spec.append(',');
                }
                spec.append('=').append(m);
            }
        }
        String specStr = spec.toString();
        return new Sequence("Gather " + s.item(), true,
                new Task("InitGather", bb -> {
                    bb.set(AIKeys.MINE_BLOCK, specStr);
                    bb.set(AIKeys.GOAL_ITEM, s.item());
                    bb.set(AIKeys.GOAL_COUNT, s.count());
                    bb.set(AIKeys.TARGET, null);
                    bb.set(AIKeys.BREAKING, null);
                    bb.set(AIKeys.COLLECT, null);
                    NavTasks.resetNav(bb);
                    return Status.SUCCESS;
                }),
                MineTasks.mineTree(),
                new Task("EndGather", bb -> {
                    bb.set(AIKeys.GOAL_ITEM, null);
                    bb.set(AIKeys.MINE_BLOCK, null);
                    return Status.SUCCESS;
                }));
    }

    // ------------------------------------------------------------------ craft

    private static Node craft(Step s) {
        Knowledge.Recipe r = s.recipe();
        Node clicks = new Task("Craft " + s.item(), bb -> doCraft(bb, s));
        if (r.grid() < 3) {
            return new Sequence("Craft " + s.item(), true, new Task("InitCraft", StepTasks::initJob), clicks);
        }
        return new Sequence("Craft " + s.item(), true,
                new Task("InitCraft", StepTasks::initJob),
                new Task("FindTable", bb -> findStation(bb, "crafting_table")),
                new Selector("ApproachTable", false, new Condition("NearStation", StepTasks::nearStation), NavTasks.gotoTree()),
                new Task("PlaceTable", bb -> placeStation(bb, "crafting_table")),
                new Task("OpenTable", bb -> openStation(bb, m -> m instanceof CraftingMenu)),
                clicks,
                new Task("CloseTable", StepTasks::closeMenu));
    }

    private static Status doCraft(Blackboard bb, Step s) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        AbstractContainerMenu menu = p.containerMenu;
        Knowledge.Recipe r = s.recipe();
        if (mc.gameMode == null) {
            return fail(bb, "no game mode");
        }
        ClickQueue cq = bb.get(AIKeys.CLICKS);
        if (cq == null) {
            cq = new ClickQueue();
            bb.set(AIKeys.CLICKS, cq);
        }
        if (!cq.built) {
            int w = menu instanceof CraftingMenu ? 3 : 2;
            if (r.grid() > w) {
                return fail(bb, "recipe for " + r.out + " needs a crafting table");
            }
            bb.set(AIKeys.EXPECT, Inv.count(p, r.out) + s.count() * r.count);
            if (!buildCraftClicks(menu, p, r, s.count(), w, cq.q)) {
                return fail(bb, "missing ingredients for " + r.out);
            }
            cq.built = true;
            return Status.RUNNING;
        }
        if (!cq.q.isEmpty()) {
            Click c = cq.q.poll();
            mc.gameMode.handleInventoryMouseClick(menu.containerId, c.slot(), c.button(), c.type(), p);
            return Status.RUNNING;
        }
        if (++cq.settle < 6) {
            return Status.RUNNING;
        }
        boolean ok = Inv.count(p, r.out) >= bb.getOrDefault(AIKeys.EXPECT, 0);
        bb.set(AIKeys.CLICKS, null);
        return ok ? Status.SUCCESS : fail(bb, "crafting " + r.out + " did not produce the expected items");
    }

    private record Cell(int slot, String token) {
    }

    private static boolean buildCraftClicks(AbstractContainerMenu menu, LocalPlayer p, Knowledge.Recipe r, int runs, int w, Deque<Click> out) {
        Knowledge k = Knowledge.get();
        for (int i = 1; i <= w * w; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) {
                out.add(new Click(i, 0, ClickType.QUICK_MOVE));
            }
        }
        Map<Integer, Integer> sim = new HashMap<>();
        for (Slot sl : menu.slots) {
            if (sl.container == p.getInventory() && sl.hasItem()) {
                sim.put(sl.index, sl.getItem().getCount());
            }
        }
        List<Cell> cells = new ArrayList<>();
        if (!r.shapeless.isEmpty()) {
            for (int i = 0; i < r.shapeless.size(); i++) {
                cells.add(new Cell(1 + i, r.shapeless.get(i)));
            }
        } else {
            for (int row = 0; row < r.pattern.size(); row++) {
                String line = r.pattern.get(row);
                for (int col = 0; col < line.length(); col++) {
                    char ch = line.charAt(col);
                    if (ch != ' ') {
                        cells.add(new Cell(1 + row * w + col, r.key.get(String.valueOf(ch))));
                    }
                }
            }
        }
        for (Cell c : cells) {
            int remaining = runs;
            while (remaining > 0) {
                int src = -1;
                int best = 0;
                for (Map.Entry<Integer, Integer> e : sim.entrySet()) {
                    if (e.getValue() > best && k.members(c.token()).contains(Inv.idOf(menu.getSlot(e.getKey()).getItem()))) {
                        src = e.getKey();
                        best = e.getValue();
                    }
                }
                if (src < 0) {
                    return false;
                }
                int take = Math.min(remaining, best);
                out.add(new Click(src, 0, ClickType.PICKUP));
                for (int t = 0; t < take; t++) {
                    out.add(new Click(c.slot(), 1, ClickType.PICKUP));
                }
                out.add(new Click(src, 0, ClickType.PICKUP));
                sim.put(src, best - take);
                remaining -= take;
            }
        }
        out.add(new Click(0, 0, ClickType.QUICK_MOVE));
        return true;
    }

    // ------------------------------------------------------------------ smelt

    private static Node smelt(Step s) {
        return new Sequence("Smelt " + s.item(), true,
                new Task("InitSmelt", StepTasks::initJob),
                new Task("FindFurnace", bb -> findStation(bb, "furnace")),
                new Selector("ApproachFurnace", false, new Condition("NearStation", StepTasks::nearStation), NavTasks.gotoTree()),
                new Task("PlaceFurnace", bb -> placeStation(bb, "furnace")),
                new Task("OpenFurnace", bb -> openStation(bb, m -> m instanceof FurnaceMenu)),
                new Task("Cook", bb -> doSmelt(bb, s)),
                new Task("CloseFurnace", StepTasks::closeMenu));
    }

    private static Status doSmelt(Blackboard bb, Step s) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        AbstractContainerMenu menu = p.containerMenu;
        Knowledge k = Knowledge.get();
        if (mc.gameMode == null) {
            return fail(bb, "no game mode");
        }
        ClickQueue cq = bb.get(AIKeys.CLICKS);
        if (cq == null) {
            cq = new ClickQueue();
            bb.set(AIKeys.CLICKS, cq);
        }
        if (!cq.built) {
            Slot input = playerSlotWith(menu, p, s.smelt().in);
            if (input == null) {
                return fail(bb, "nothing to smelt: " + s.smelt().in);
            }
            Slot fuel = null;
            for (Knowledge.Fuel f : k.fuel()) {
                fuel = playerSlotWith(menu, p, f.item);
                if (fuel != null) {
                    break;
                }
            }
            if (fuel == null) {
                return fail(bb, "no fuel for the furnace");
            }
            cq.q.add(new Click(input.index, 0, ClickType.QUICK_MOVE));
            cq.q.add(new Click(fuel.index, 0, ClickType.QUICK_MOVE));
            bb.set(AIKeys.EXPECT, Inv.count(p, s.item()) + s.count());
            cq.built = true;
            return Status.RUNNING;
        }
        if (!cq.q.isEmpty()) {
            Click c = cq.q.poll();
            mc.gameMode.handleInventoryMouseClick(menu.containerId, c.slot(), c.button(), c.type(), p);
            return Status.RUNNING;
        }
        if (!cq.collecting) {
            cq.waited++;
            if (menu.getSlot(2).getItem().getCount() >= s.count() || cq.waited > s.count() * 220 + 400) {
                cq.q.add(new Click(2, 0, ClickType.QUICK_MOVE));
                cq.q.add(new Click(0, 0, ClickType.QUICK_MOVE));
                cq.q.add(new Click(1, 0, ClickType.QUICK_MOVE));
                cq.collecting = true;
            }
            return Status.RUNNING;
        }
        if (++cq.settle < 6) {
            return Status.RUNNING;
        }
        boolean ok = Inv.count(p, s.item()) >= bb.getOrDefault(AIKeys.EXPECT, 0);
        bb.set(AIKeys.CLICKS, null);
        return ok ? Status.SUCCESS : fail(bb, "smelting " + s.item() + " did not finish");
    }

    private static Slot playerSlotWith(AbstractContainerMenu menu, LocalPlayer p, String token) {
        Knowledge k = Knowledge.get();
        for (Slot sl : menu.slots) {
            if (sl.container == p.getInventory() && sl.hasItem() && k.members(token).contains(Inv.idOf(sl.getItem()))) {
                return sl;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ stations

    private static Status initJob(Blackboard bb) {
        bb.set(AIKeys.CLICKS, null);
        bb.set(AIKeys.STEP_TICKS, 0);
        bb.set(AIKeys.STATION, null);
        bb.set(AIKeys.PLACING, null);
        bb.set(AIKeys.EXPECT, null);
        return Status.SUCCESS;
    }

    private static Status fail(Blackboard bb, String reason) {
        bb.set(AIKeys.FAIL_REASON, reason);
        return Status.FAILURE;
    }

    /** Picks an existing station to walk to, or a spot to place one from the inventory. */
    private static Status findStation(Blackboard bb, String block) {
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
            BlockPos stand = MineTasks.standCell(view, pos);
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
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos c = origin.offset(d[0], 0, d[1]);
            BlockPos below = c.below();
            if (view.bodyFree(c.getX(), c.getY(), c.getZ()) && view.bodyFree(c.getX(), c.getY() + 1, c.getZ())
                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                bb.set(AIKeys.STATION, c);
                bb.set(AIKeys.PLACING, true);
                bb.set(AIKeys.GOAL, origin);
                NavTasks.resetNav(bb);
                return Status.SUCCESS;
            }
        }
        return fail(bb, "no free space to place a " + block);
    }

    private static boolean nearStation(Blackboard bb) {
        BlockPos st = bb.get(AIKeys.STATION);
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        return st != null && p.getEyePosition().distanceTo(Vec3.atCenterOf(st)) <= REACH;
    }

    private static Status placeStation(Blackboard bb, String block) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        BlockPos st = bb.get(AIKeys.STATION);
        if (MineTasks.idOf(level.getBlockState(st)).equals(block)) {
            bb.set(AIKeys.PLACING, null);
            bb.set(AIKeys.STEP_TICKS, 0);
            return Status.SUCCESS;
        }
        if (!bb.getOrDefault(AIKeys.PLACING, false)) {
            return fail(bb, block + " disappeared");
        }
        int t = bb.getOrDefault(AIKeys.STEP_TICKS, 0);
        bb.set(AIKeys.STEP_TICKS, t + 1);
        if (t > 80) {
            return fail(bb, "could not place " + block);
        }
        if (mc.gameMode == null || !selectItem(p, block)) {
            return Status.RUNNING;
        }
        if (t % 5 == 0) {
            BlockPos below = st.below();
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(below).add(0.0, 0.5, 0.0), Direction.UP, below, false));
        }
        return Status.RUNNING;
    }

    private static Status openStation(Blackboard bb, Predicate<AbstractContainerMenu> expected) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        BlockPos st = bb.get(AIKeys.STATION);
        if (expected.test(p.containerMenu)) {
            bb.set(AIKeys.STEP_TICKS, 0);
            return Status.SUCCESS;
        }
        int t = bb.getOrDefault(AIKeys.STEP_TICKS, 0);
        bb.set(AIKeys.STEP_TICKS, t + 1);
        if (t > 100 || mc.gameMode == null) {
            return fail(bb, "could not open the station");
        }
        if (t % 10 == 0) {
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(st), Direction.UP, st, false));
        }
        return Status.RUNNING;
    }

    private static Status closeMenu(Blackboard bb) {
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
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, sl.index, inv.getSelectedSlot(), ClickType.SWAP, p);
                return false;
            }
        }
        return false;
    }
}