package dev.nullclient.ai;

import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Blackboard.Key;
import dev.nullclient.ai.nav.AStarPathfinder;
import dev.nullclient.ai.nav.Path;
import dev.nullclient.ai.nav.PathFollower;
import dev.nullclient.ai.task.StepTasks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.HashSet;

public final class AIKeys {
    private AIKeys() {
    }

    /** Positions (packed longs) that proved unreachable for the current gather step. */
    public static final class BadSet extends HashSet<Long> {
    }

    public static final Key<LocalPlayer> PLAYER = Key.of("player", LocalPlayer.class);
    public static final Key<ClientLevel> LEVEL = Key.of("level", ClientLevel.class);
    public static final Key<BlockPos> GOAL = Key.of("goal", BlockPos.class);
    public static final Key<BlockPos> ORIGIN = Key.of("origin", BlockPos.class);
    public static final Key<Path> PATH = Key.of("path", Path.class);
    public static final Key<AStarPathfinder.Search> SEARCH = Key.of("search", AStarPathfinder.Search.class);
    public static final Key<PathFollower> FOLLOWER = Key.of("follower", PathFollower.class);
    public static final Key<Integer> REPLANS = Key.of("replans", Integer.class);
    public static final Key<Integer> WANDER_RADIUS = Key.of("wanderRadius", Integer.class);
    public static final Key<Integer> WAIT_LEFT = Key.of("waitLeft", Integer.class);
    public static final Key<String> TARGET_NAME = Key.of("targetName", String.class);
    public static final Key<String> FAIL_REASON = Key.of("failReason", String.class);
    public static final Key<Integer> ACTIONS = Key.of("actions", Integer.class);
    public static final Key<Float> YAW = Key.of("yaw", Float.class);
    public static final Key<Float> PITCH = Key.of("pitch", Float.class);

    // navigation
    public static final Key<Boolean> ALLOW_DIG = Key.of("allowDig", Boolean.class);

    // mining
    public static final Key<BlockPos> TARGET = Key.of("target", BlockPos.class);
    public static final Key<BlockPos> LAST_BROKEN = Key.of("lastBroken", BlockPos.class);
    public static final Key<BlockPos> CUR_BREAK = Key.of("curBreak", BlockPos.class);
    public static final Key<String> MINE_BLOCK = Key.of("mineBlock", String.class);
    public static final Key<Integer> MINE_COUNT = Key.of("mineCount", Integer.class);
    public static final Key<Integer> MINED = Key.of("mined", Integer.class);
    public static final Key<Integer> COLLECT = Key.of("collect", Integer.class);
    public static final Key<Boolean> BREAKING = Key.of("breaking", Boolean.class);
    public static final Key<BadSet> BAD_TARGETS = Key.of("badTargets", BadSet.class);
    public static final Key<Boolean> EXPLORING = Key.of("exploring", Boolean.class);
    public static final Key<Integer> EXPLORE_TRIES = Key.of("exploreTries", Integer.class);

    // planner / stations / inventory clicks
    public static final Key<String> GOAL_ITEM = Key.of("goalItem", String.class);
    public static final Key<Integer> GOAL_COUNT = Key.of("goalCount", Integer.class);
    public static final Key<BlockPos> STATION = Key.of("station", BlockPos.class);
    public static final Key<BlockPos> FURNACE_POS = Key.of("furnacePos", BlockPos.class);
    public static final Key<Boolean> PLACING = Key.of("placing", Boolean.class);
    public static final Key<Integer> STEP_TICKS = Key.of("stepTicks", Integer.class);
    public static final Key<Integer> EXPECT = Key.of("expect", Integer.class);
    public static final Key<StepTasks.ClickQueue> CLICKS = Key.of("clicks", StepTasks.ClickQueue.class);

    public static void addActions(Blackboard bb, int bits) {
        bb.set(ACTIONS, bb.getOrDefault(ACTIONS, 0) | bits);
    }
}