package dev.nullclient.core.input;

/**
 * Single arbitrated channel through which the AI engine and the macro player drive the local
 * player's movement input. Applied by KeyboardInputMixin after vanilla input is polled.
 */
public final class InputOverride {
    public static final int FORWARD = 1;
    public static final int BACK = 1 << 1;
    public static final int LEFT = 1 << 2;
    public static final int RIGHT = 1 << 3;
    public static final int JUMP = 1 << 4;
    public static final int SNEAK = 1 << 5;
    public static final int SPRINT = 1 << 6;

    /** Set by the mixins the first time they run; used by ".ai debug" to show which hooks are alive. */
    public static volatile boolean inputHookRan;
    public static volatile boolean uiHookRan;

    private static volatile String owner;
    private static volatile int actions;

    private InputOverride() {
    }

    /** Claims or updates the channel. Returns false if another owner holds it. */
    public static synchronized boolean set(String who, int bits) {
        if (owner != null && !owner.equals(who)) {
            return false;
        }
        owner = who;
        actions = bits;
        return true;
    }

    public static synchronized void release(String who) {
        if (who.equals(owner)) {
            owner = null;
            actions = 0;
        }
    }

    public static synchronized boolean heldBy(String who) {
        return who.equals(owner);
    }

    public static boolean active() {
        return owner != null;
    }

    public static int actions() {
        return actions;
    }

    public static boolean has(int bits, int flag) {
        return (bits & flag) != 0;
    }
}