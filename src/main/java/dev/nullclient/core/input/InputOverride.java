package dev.nullclient.core.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Single arbitrated channel through which the AI engine and the macro player drive the local player.
 * Movement is applied by pressing the real key bindings each tick, so it works regardless of which input
 * class the game or launcher uses.
 */
public final class InputOverride {
    public static final int FORWARD = 1;
    public static final int BACK = 1 << 1;
    public static final int LEFT = 1 << 2;
    public static final int RIGHT = 1 << 3;
    public static final int JUMP = 1 << 4;
    public static final int SNEAK = 1 << 5;
    public static final int SPRINT = 1 << 6;

    /** Set by the mixins the first time they run; used by ".ai debug". */
    public static volatile boolean inputHookRan;
    public static volatile boolean uiHookRan;

    private static volatile String owner;
    private static volatile int actions;
    private static boolean driving;

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

    public static boolean driving() {
        return driving;
    }

    /** Call once per client tick, after the AI and macros have set their actions. */
    public static void drive(Minecraft mc) {
        Options o = mc.options;
        if (o == null) {
            return;
        }
        if (mc.player == null || !active()) {
            if (driving) {
                press(o, 0);
                driving = false;
            }
            return;
        }
        press(o, actions);
        driving = true;
    }

    private static void press(Options o, int bits) {
        set(o.keyUp, bits, FORWARD);
        set(o.keyDown, bits, BACK);
        set(o.keyLeft, bits, LEFT);
        set(o.keyRight, bits, RIGHT);
        set(o.keyJump, bits, JUMP);
        set(o.keyShift, bits, SNEAK);
        set(o.keySprint, bits, SPRINT);
    }

    private static void set(KeyMapping key, int bits, int flag) {
        key.setDown(has(bits, flag));
    }
}