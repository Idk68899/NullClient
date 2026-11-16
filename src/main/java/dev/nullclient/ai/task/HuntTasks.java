package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.AIMemory;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Condition;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Selector;
import dev.nullclient.ai.bt.Sequence;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.bt.Task;
import dev.nullclient.ai.plan.Step;
import dev.nullclient.ai.world.Vision;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gathering an item that mobs drop: find the nearest matching mob, walk to it, fight it with {@link Combat}, then pick up
 * the drop. If none is in range it explores (avoiding places already searched) a few times before giving up.
 */
public final class HuntTasks {
    private static final double SEARCH = 48.0;
    private static final int MAX_EXPLORES = 5;
    private static final int LOOT_TICKS = 40;

    private static final class Hunt {
        LivingEntity target;
        final Set<Integer> ignored = new HashSet<>();
        final Combat combat = new Combat();
    }

    private HuntTasks() {
    }

    public static Node node(Step s) {
        List<String> mobs = s.gather().mobs;
        Hunt h = new Hunt();
        Node nav = NavTasks.gotoTree();
        return new Sequence("Hunt " + s.item(), true,
                new Task("InitHunt", bb -> {
                    bb.set(AIKeys.GOAL_ITEM, s.item());
                    bb.set(AIKeys.GOAL_COUNT, s.count());
                    bb.set(AIKeys.TARGET, null);
                    bb.set(AIKeys.COLLECT, null);
                    bb.set(AIKeys.EXPLORING, null);
                    bb.set(AIKeys.EXPLORE_TRIES, null);
                    NavTasks.resetNav(bb);
                    h.target = null;
                    h.ignored.clear();
                    h.combat.clear();
                    return Status.SUCCESS;
                }),
                new Selector("HuntLoop", false,
                        new Sequence("Done", false,
                                new Condition("Enough", MineTasks::enough),
                                new Task("Finish", bb -> Status.SUCCESS)),
                        new Sequence("Loot", false,
                                new Condition("Looting", bb -> bb.getOrDefault(AIKeys.COLLECT, 0) > 0),
                                new Task("Pickup", MineTasks::collect)),
                        new Task("Chase", bb -> chase(bb, mobs, h, nav))),
                new Task("EndHunt", bb -> {
                    bb.set(AIKeys.GOAL_ITEM, null);
                    return Status.SUCCESS;
                }));
    }

    private static LivingEntity find(LocalPlayer p, ClientLevel level, List<String> mobs, Set<Integer> ignored) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(SEARCH))) {
            if (e == p || !e.isAlive() || ignored.contains(e.getId()) || !mobs.contains(Combat.idOf(e))) {
                continue;
            }
            if (Vision.enabled() && !Combat.sees(level, p, e)) {
                continue;
            }
            double d = e.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static Status explore(Blackboard bb, Node nav, LocalPlayer p, ClientLevel level, String what) {
        if (!bb.getOrDefault(AIKeys.EXPLORING, false)) {
            int tries = bb.getOrDefault(AIKeys.EXPLORE_TRIES, 0);
            BlockPos g = tries >= MAX_EXPLORES ? null : AIMemory.INSTANCE.pickExploreGoal(level, p);
            if (g == null) {
                RunLog.add("no " + what + " found after exploring from " + p.blockPosition().toShortString());
                bb.set(AIKeys.FAIL_REASON, "no " + what + " found nearby");
                return Status.FAILURE;
            }
            bb.set(AIKeys.EXPLORE_TRIES, tries + 1);
            NavTasks.resetNav(bb);
            bb.set(AIKeys.GOAL, g);
            bb.set(AIKeys.EXPLORING, true);
            RunLog.add("no " + what + " in range - exploring to " + g.toShortString() + " (try " + (tries + 1) + ")");
        }
        Status s = nav.tick(bb);
        if (s != Status.RUNNING) {
            bb.set(AIKeys.EXPLORING, null);
            nav.reset(bb);
            NavTasks.resetNav(bb);
            bb.set(AIKeys.FAIL_REASON, null);
        }
        return Status.RUNNING;
    }

    private static Status chase(Blackboard bb, List<String> mobs, Hunt h, Node nav) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        if (h.target != null && (!h.target.isAlive() || h.target.isRemoved())) {
            RunLog.add("killed " + Combat.idOf(h.target) + " at " + h.target.blockPosition().toShortString());
            bb.set(AIKeys.LAST_BROKEN, h.target.blockPosition());
            bb.set(AIKeys.COLLECT, LOOT_TICKS);
            h.target = null;
            h.combat.clear();
            NavTasks.resetNav(bb);
            return Status.RUNNING;
        }
        if (h.target == null) {
            h.target = find(p, level, mobs, h.ignored);
            if (h.target == null) {
                return explore(bb, nav, p, level, String.join("/", mobs));
            }
            bb.set(AIKeys.EXPLORING, null);
            NavTasks.resetNav(bb);
            RunLog.add("hunting " + Combat.idOf(h.target) + " at " + h.target.blockPosition().toShortString());
        }
        if (p.distanceTo(h.target) <= 6.0f && Combat.sees(level, p, h.target)) {
            h.combat.tick(bb, h.target);
            return Status.RUNNING;
        }
        BlockPos tp = h.target.blockPosition();
        BlockPos cur = bb.get(AIKeys.GOAL);
        if (cur == null || cur.distSqr(tp) > 9.0) {
            bb.set(AIKeys.GOAL, tp);
            bb.set(AIKeys.PATH, null);
            bb.set(AIKeys.SEARCH, null);
            bb.set(AIKeys.FOLLOWER, null);
        }
        Status s = nav.tick(bb);
        if (s == Status.FAILURE) {
            RunLog.add("cannot reach " + Combat.idOf(h.target) + " at " + tp.toShortString() + " - trying another");
            h.ignored.add(h.target.getId());
            h.target = null;
            h.combat.clear();
            nav.reset(bb);
            NavTasks.resetNav(bb);
            bb.set(AIKeys.FAIL_REASON, null);
        } else if (s == Status.SUCCESS) {
            nav.reset(bb);
            NavTasks.resetNav(bb);
        }
        return Status.RUNNING;
    }
}