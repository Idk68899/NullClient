package dev.nullclient.ai;

import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.ai.nav.AStarPathfinder;
import dev.nullclient.ai.nav.Path;
import dev.nullclient.ai.nav.PathFollower;
import dev.nullclient.ai.nav.PathNode;
import dev.nullclient.ai.world.WorldScanner;
import dev.nullclient.core.input.InputOverride;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * A behavior tree bound to one local profile. Drives the local player while that profile is active.
 * The camera eases toward its target angle, in water the agent surfaces for air in time, and spots it gave up on
 * and areas it already explored are remembered (see AIMemory) so it does not repeat itself.
 */
public final class AIAgent {
    public enum State { RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELLED }

    public static final String OWNER = "ai";
    private static final int SCAN_RADIUS = 96;
    private static final int ROUTE_REPEAT_TICKS = 200;
    private static final int MEMORY_EVERY = 20;
    private static final float YAW_EASE = 0.35f;
    private static final float YAW_MAX = 22.0f;
    private static final float PITCH_EASE = 0.35f;
    private static final float PITCH_MAX = 12.0f;

    private final Profile profile;
    private final String label;
    private final Node root;
    private final Blackboard bb;
    private State state = State.RUNNING;
    private String message = "";
    private long ticks;
    private boolean surfacing;
    private boolean wasExploring;

    private String seenSpec;
    private BlockPos seenTarget;
    private Path seenPath;
    private boolean seenExploring;
    private String lastRouteText = "";
    private long lastRouteTick = -1000;

    public AIAgent(Profile profile, String label, Node root, Blackboard bb) {
        this.profile = profile;
        this.label = label;
        this.root = root;
        this.bb = bb;
        // Spots given up on in earlier steps are restored automatically whenever the per-step set is reset.
        bb.setDefault(AIKeys.BAD_TARGETS, () -> {
            ClientLevel lv = Minecraft.getInstance().level;
            return lv == null ? new AIKeys.BadSet() : AIMemory.INSTANCE.liveBad(lv);
        });
    }

    public Profile profile() {
        return profile;
    }

    public String label() {
        return label;
    }

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }

    public long ticks() {
        return ticks;
    }

    public boolean isLive() {
        return state == State.RUNNING || state == State.SUSPENDED;
    }

    public String activeNodes() {
        StringBuilder sb = new StringBuilder();
        root.describeActive(sb);
        return sb.toString();
    }

    void setState(State s, String msg) {
        this.state = s;
        this.message = msg == null ? "" : msg;
    }

    /** One-line description of what the agent is doing right now, for the action bar. */
    public String thought() {
        if (surfacing) {
            return "surfacing for air";
        }
        String spec = bb.get(AIKeys.MINE_BLOCK);
        String clean = spec == null ? "" : spec.replace("=", "");
        BlockPos target = bb.get(AIKeys.TARGET);
        Path path = bb.get(AIKeys.PATH);
        AStarPathfinder.Search search = bb.get(AIKeys.SEARCH);
        if (bb.getOrDefault(AIKeys.EXPLORING, false)) {
            return "exploring - nothing found nearby";
        }
        if (search != null) {
            return "planning a route (" + search.expansions() + " positions checked)";
        }
        if (path != null) {
            PathFollower f = bb.get(AIKeys.FOLLOWER);
            int idx = f == null ? 0 : f.index();
            PathNode n = idx < path.size() ? path.get(idx) : path.last();
            return "walking: step " + idx + "/" + (path.size() - 1) + " (" + n.move.name().toLowerCase() + ")";
        }
        if (target != null) {
            return "mining " + clean + " at " + target.toShortString();
        }
        if (spec != null) {
            return WorldScanner.INSTANCE.settled() ? "looking for " + clean : "scanning the area for " + clean;
        }
        return label;
    }

    /** Chat narration (only when ".ai talk" is on): new target, new route, exploring. */
    private void narrate(LocalPlayer p, ClientLevel level) {
        String spec = bb.get(AIKeys.MINE_BLOCK);
        if (spec == null) {
            seenSpec = null;
        } else if (!spec.equals(seenSpec)) {
            seenSpec = spec;
            String clean = spec.replace("=", "");
            if (WorldScanner.INSTANCE.covers(spec)) {
                List<BlockPos> found = WorldScanner.INSTANCE.find(spec, p.blockPosition(), SCAN_RADIUS, 5000);
                if (found.isEmpty()) {
                    Say.talk("scanning for " + clean + ": none indexed within " + SCAN_RADIUS + " blocks");
                } else {
                    double d = Math.sqrt(found.get(0).distSqr(p.blockPosition()));
                    Say.talk("scanning for " + clean + ": " + found.size() + " found within " + SCAN_RADIUS
                            + " blocks, nearest " + Math.round(d) + " away");
                }
            } else {
                Say.talk("scanning for " + clean + ": checking nearby blocks directly");
            }
        }

        BlockPos target = bb.get(AIKeys.TARGET);
        if (target == null) {
            seenTarget = null;
        } else if (!target.equals(seenTarget)) {
            seenTarget = target;
            String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).getPath();
            double d = Math.sqrt(target.distSqr(p.blockPosition()));
            Say.talk("target: " + id + " at " + target.toShortString() + " (" + Math.round(d) + " blocks away)");
        }

        Path path = bb.get(AIKeys.PATH);
        if (path == null) {
            seenPath = null;
        } else if (path != seenPath) {
            seenPath = path;
            if (path.size() > 1) {
                int dig = 0;
                for (int i = 1; i < path.size(); i++) {
                    if (path.get(i).move.isDig()) {
                        dig++;
                    }
                }
                String text = "route: " + (path.size() - 1) + " steps"
                        + (dig > 0 ? ", digging through rock for " + dig + " of them" : "")
                        + (path.partial() ? ", partial (will replan on arrival)" : "");
                if (!text.equals(lastRouteText) || ticks - lastRouteTick > ROUTE_REPEAT_TICKS) {
                    Say.talk(text);
                    lastRouteText = text;
                    lastRouteTick = ticks;
                }
            }
        }

        boolean exploring = bb.getOrDefault(AIKeys.EXPLORING, false);
        if (exploring && !seenExploring) {
            Say.talk("nothing indexed nearby - exploring to load new chunks");
        }
        seenExploring = exploring;
    }

    /** Number of water blocks between the player's head and the surface (0 if the head is not under water). */
    private static int waterAbove(ClientLevel level, LocalPlayer p) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(p.getBlockX(), p.getBlockY() + 1, p.getBlockZ());
        int n = 0;
        while (n < 48 && level.getFluidState(mp).is(FluidTags.WATER)) {
            mp.move(0, 1, 0);
            n++;
        }
        return n;
    }

    /** Swims up for air before it is too late, and stays up until the air bar has refilled. */
    private void keepAlive(LocalPlayer p, ClientLevel level) {
        if (p.isUnderWater()) {
            int needed = 50 + waterAbove(level, p) * 8;
            if (p.getAirSupply() < needed) {
                surfacing = true;
            }
        }
        if (!surfacing) {
            return;
        }
        if (!p.isUnderWater() && p.getAirSupply() >= p.getMaxAirSupply() - 20) {
            surfacing = false;
            return;
        }
        int a = (bb.getOrDefault(AIKeys.ACTIONS, 0) | InputOverride.JUMP) & ~InputOverride.SNEAK;
        bb.set(AIKeys.ACTIONS, a);
    }

    /** When exploring starts: avoid areas already searched, and remember this one. */
    private void rememberExploring(LocalPlayer p, ClientLevel level) {
        boolean exploring = bb.getOrDefault(AIKeys.EXPLORING, false);
        if (exploring && !wasExploring) {
            BlockPos g = bb.get(AIKeys.GOAL);
            if (g != null && AIMemory.INSTANCE.nearExplored(level, g)) {
                BlockPos better = AIMemory.INSTANCE.pickExploreGoal(level, p);
                if (better != null) {
                    bb.set(AIKeys.GOAL, better);
                    bb.set(AIKeys.PATH, null);
                    bb.set(AIKeys.SEARCH, null);
                    bb.set(AIKeys.FOLLOWER, null);
                    g = better;
                    RunLog.add("explore: picked a new area instead of one already searched, " + better.toShortString());
                }
            }
            AIMemory.INSTANCE.markExplored(level, p.blockPosition());
            if (g != null) {
                AIMemory.INSTANCE.markExplored(level, g);
            }
        }
        wasExploring = exploring;
    }

    /** Eases an angle toward a target: proportional step, capped, snapping when very close. */
    private static float ease(float current, float diff, float factor, float max) {
        if (Math.abs(diff) < 1.0f) {
            return current + diff;
        }
        return current + Mth.clamp(diff * factor, -max, max);
    }

    /** Runs one tick. Returns the terminal state if the tree finished, otherwise null. */
    State tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            return null;
        }
        ticks++;
        bb.set(AIKeys.PLAYER, p);
        bb.set(AIKeys.LEVEL, level);
        bb.set(AIKeys.ACTIONS, 0);
        bb.set(AIKeys.YAW, null);
        bb.set(AIKeys.PITCH, null);
        InputOverride.clearDirection();

        Status s;
        try {
            s = root.tick(bb);
        } catch (RuntimeException e) {
            message = "internal error: " + e;
            RunLog.add("internal error: " + e);
            return State.FAILED;
        }

        rememberExploring(p, level);
        if (ticks % MEMORY_EVERY == 0) {
            AIKeys.BadSet cur = bb.get(AIKeys.BAD_TARGETS);
            if (cur != null && !cur.isEmpty()) {
                AIMemory.INSTANCE.rememberBad(level, cur);
            }
        }

        keepAlive(p, level);

        if (!InputOverride.set(OWNER, bb.getOrDefault(AIKeys.ACTIONS, 0))) {
            message = "input channel busy";
            return State.FAILED;
        }
        Float yaw = bb.get(AIKeys.YAW);
        if (yaw != null) {
            float cur = p.getYRot();
            p.setYRot(ease(cur, Mth.wrapDegrees(yaw - cur), YAW_EASE, YAW_MAX));
        }
        Float pitch = bb.get(AIKeys.PITCH);
        if (pitch != null) {
            float cur = p.getXRot();
            p.setXRot(Mth.clamp(ease(cur, pitch - cur, PITCH_EASE, PITCH_MAX), -90.0f, 90.0f));
        }

        if (Say.verbose) {
            narrate(p, level);
        }
        if (ticks % 6 == 0) {
            String aim = yaw == null ? "" : " | yaw " + Math.round(p.getYRot()) + ">" + Math.round(yaw)
                    + " pitch " + Math.round(p.getXRot()) + ">" + (pitch == null ? "-" : Long.toString(Math.round(pitch)));
            String mode = InputOverride.velocityMode() ? " [direct]" : "";
            p.sendOverlayMessage(Component.literal("[AI] " + label + " | " + thought() + aim + mode));
        }

        return switch (s) {
            case RUNNING -> null;
            case SUCCESS -> {
                message = "completed";
                yield State.SUCCEEDED;
            }
            case FAILURE -> {
                String reason = bb.get(AIKeys.FAIL_REASON);
                message = reason == null ? "task failed" : reason;
                yield State.FAILED;
            }
        };
    }
}