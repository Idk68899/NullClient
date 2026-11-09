package dev.nullclient.core.input;

import dev.nullclient.ai.Say;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Single arbitrated channel through which the AI engine and the macro player drive the local player.
 * First it presses the real key bindings each tick. If that visibly fails to move the player, it falls back
 * to setting the player's velocity directly at vanilla walking and sprinting speeds, heading in a world-space
 * direction so it does not depend on where the camera is pointing. A jump can also be forced directly
 * ({@link #forceJump()}), which works even when the launcher ignores the jump key.
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

    private static final double WALK_SPEED = 0.2158;
    private static final double SPRINT_SPEED = 0.2806;
    private static final double SNEAK_SPEED = 0.0647;
    private static final double JUMP_VELOCITY = 0.42;
    private static final int STALL_WINDOW = 25;

    private static volatile String owner;
    private static volatile int actions;
    private static volatile boolean hasDir;
    private static volatile double dirX;
    private static volatile double dirZ;
    private static volatile boolean jumpAssist;
    private static boolean driving;
    private static boolean velocityMode;
    private static boolean haveLast;
    private static double lastX;
    private static double lastZ;
    private static double moved;
    private static int window;

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
            hasDir = false;
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

    public static boolean velocityMode() {
        return velocityMode;
    }

    /** World-space unit direction to move in when in direct-movement mode (set by the path follower each tick). */
    public static void setDirection(double x, double z) {
        dirX = x;
        dirZ = z;
        hasDir = true;
    }

    public static void clearDirection() {
        hasDir = false;
    }

    /** Requests one direct jump on the next tick if the player is on the ground (independent of key presses). */
    public static void forceJump() {
        jumpAssist = true;
    }

    /** Call once per client tick, after the AI and macros have set their actions. */
    public static void drive(Minecraft mc) {
        Options o = mc.options;
        LocalPlayer p = mc.player;
        if (o == null) {
            return;
        }
        if (p == null || !active()) {
            if (driving) {
                press(o, 0);
                driving = false;
            }
            haveLast = false;
            window = 0;
            moved = 0;
            jumpAssist = false;
            return;
        }
        int a = actions;
        if (velocityMode) {
            press(o, 0);
            applyVelocity(p, a);
        } else {
            press(o, a);
            watchForStall(p, a);
        }
        if (jumpAssist) {
            jumpAssist = false;
            if (p.onGround()) {
                Vec3 v = p.getDeltaMovement();
                p.setDeltaMovement(v.x, JUMP_VELOCITY, v.z);
            }
        }
        driving = true;
    }

    private static void watchForStall(LocalPlayer p, int a) {
        double x = p.getX();
        double z = p.getZ();
        if (!haveLast) {
            lastX = x;
            lastZ = z;
            haveLast = true;
            return;
        }
        double d = Math.hypot(x - lastX, z - lastZ);
        lastX = x;
        lastZ = z;
        if (has(a, FORWARD) && p.onGround()) {
            moved += d;
            window++;
        } else {
            moved = 0;
            window = 0;
        }
        if (window >= STALL_WINDOW) {
            if (moved < 0.4) {
                velocityMode = true;
                Say.line("pressing keys did not move you - switching to direct movement");
            }
            moved = 0;
            window = 0;
        }
    }

    private static void applyVelocity(LocalPlayer p, int a) {
        double forward = (has(a, FORWARD) ? 1.0 : 0.0) - (has(a, BACK) ? 1.0 : 0.0);
        double strafe = (has(a, LEFT) ? 1.0 : 0.0) - (has(a, RIGHT) ? 1.0 : 0.0);
        double dx;
        double dz;
        if (hasDir && has(a, FORWARD)) {
            dx = dirX;
            dz = dirZ;
        } else {
            double yaw = Math.toRadians(p.getYRot());
            dx = -Math.sin(yaw) * forward + Math.cos(yaw) * strafe;
            dz = Math.cos(yaw) * forward + Math.sin(yaw) * strafe;
        }
        double len = Math.hypot(dx, dz);
        Vec3 v = p.getDeltaMovement();
        double vy = v.y;
        if (len > 1.0e-6) {
            double speed = has(a, SNEAK) ? SNEAK_SPEED : (has(a, SPRINT) && forward > 0 ? SPRINT_SPEED : WALK_SPEED);
            if (p.isInWater()) {
                speed *= 0.5;
            }
            dx = dx / len * speed;
            dz = dz / len * speed;
        } else {
            dx = 0.0;
            dz = 0.0;
        }
        if (has(a, JUMP)) {
            if (p.onGround()) {
                vy = JUMP_VELOCITY;
            } else if (p.isInWater() || p.isInLava()) {
                vy = 0.1;
            }
        } else if (has(a, SNEAK) && p.isInWater()) {
            vy = -0.12;
        }
        p.setDeltaMovement(dx, vy, dz);
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