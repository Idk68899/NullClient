package dev.nullclient.ai;

import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Status;
import dev.nullclient.core.input.InputOverride;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** A behavior tree bound to one local profile. Drives the local player while that profile is active. */
public final class AIAgent {
    public enum State { RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELLED }

    public static final String OWNER = "ai";

    private final Profile profile;
    private final String label;
    private final Node root;
    private final Blackboard bb;
    private State state = State.RUNNING;
    private String message = "";
    private long ticks;

    public AIAgent(Profile profile, String label, Node root, Blackboard bb) {
        this.profile = profile;
        this.label = label;
        this.root = root;
        this.bb = bb;
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

        Status s;
        try {
            s = root.tick(bb);
        } catch (RuntimeException e) {
            message = "internal error: " + e;
            return State.FAILED;
        }

        if (!InputOverride.set(OWNER, bb.getOrDefault(AIKeys.ACTIONS, 0))) {
            message = "input channel busy";
            return State.FAILED;
        }
        Float yaw = bb.get(AIKeys.YAW);
        if (yaw != null) {
            float cur = p.getYRot();
            p.setYRot(cur + Mth.clamp(Mth.wrapDegrees(yaw - cur), -40.0f, 40.0f));
        }
        Float pitch = bb.get(AIKeys.PITCH);
        if (pitch != null) {
            float cur = p.getXRot();
            p.setXRot(Mth.clamp(cur + Mth.clamp(pitch - cur, -20.0f, 20.0f), -90.0f, 90.0f));
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