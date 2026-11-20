package dev.nullclient.core.script;

import dev.nullclient.NullClient;
import dev.nullclient.ai.AIRuntime;
import dev.nullclient.core.event.Events;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Runs one script at a time, one step per tick at most. Movement steps drive the real key bindings through the shared
 * input channel (so they stop if the AI or a recorded macro owns it); click steps act on what is in front of you at
 * normal reach; client steps run the same commands you could type. Stop with {@link #stop()} or the END key.
 */
public final class ScriptRunner {
    private static final String OWNER = "script";
    private static final int GAP_ATTACK = 12;
    private static final int GAP_USE = 10;
    private static final long CHAT_GAP_MS = 800L;

    private static Script script;
    private static int index;
    private static int loops;
    private static int repeat = 1;
    private static boolean infinite;
    private static int elapsed;
    private static int counter;
    private static boolean begun;
    private static boolean sawAgent;
    private static boolean owning;
    private static int held;
    private static float targetYaw;
    private static float targetPitch;
    private static BlockPos mining;
    private static long lastChatMs;
    private static String status = "idle";

    private ScriptRunner() {
    }

    public static boolean running() {
        return script != null;
    }

    /** The step being run (0-based), or -1. */
    public static int index() {
        return script == null ? -1 : index;
    }

    public static String status() {
        if (script == null) {
            return status;
        }
        int n = script.steps.size();
        String step = index < n ? Script.describe(script.steps.get(index)) : "finishing";
        return "running '" + script.name + "' step " + Math.min(index + 1, n) + "/" + n + ": " + step
                + " (loop " + (loops + 1) + (infinite ? "" : "/" + repeat) + ")";
    }

    public static void start(Script s, int times) {
        stop();
        if (s == null || s.steps.isEmpty()) {
            status = "that script has no steps";
            return;
        }
        script = s.copy();
        index = 0;
        loops = 0;
        infinite = times <= 0;
        repeat = Math.max(1, times);
        elapsed = 0;
        counter = 0;
        begun = false;
        sawAgent = false;
        held = 0;
        status = "started '" + s.name + "'";
    }

    public static void stop() {
        if (script != null) {
            end("stopped");
        }
    }

    private static void end(String reason) {
        status = reason;
        script = null;
        release();
    }

    private static void release() {
        Minecraft mc = Minecraft.getInstance();
        if (owning) {
            InputOverride.release(OWNER);
            owning = false;
        }
        if (mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
        if (mining != null && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        mining = null;
    }

    private static void cmd(String line) {
        NullClient nc = NullClient.get();
        if (nc != null) {
            nc.bus().post(new Events.ChatSend(line));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BlockHitResult blockAhead(ClientLevel level, LocalPlayer p, double reach) {
        Vec3 eye = p.getEyePosition();
        Vec3 to = eye.add(p.getLookAngle().scale(reach));
        BlockHitResult h = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return h.getType() == HitResult.Type.BLOCK ? h : null;
    }

    private static void attackAhead(Minecraft mc, LocalPlayer p, ClientLevel level) {
        Vec3 eye = p.getEyePosition();
        Vec3 to = eye.add(p.getLookAngle().scale(3.0));
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(4.0))) {
            if (e == p || !e.isAlive()) {
                continue;
            }
            Optional<Vec3> hit = e.getBoundingBox().inflate(0.1).clip(eye, to);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bd) {
                    bd = d;
                    best = e;
                }
            }
        }
        if (best != null) {
            mc.gameMode.attack(p, best);
        }
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean hold(int bits, int ticks) {
        held = bits;
        return ++elapsed >= ticks;
    }

    // ------------------------------------------------------------------ main loop

    /** Call once per client tick. */
    public static void tick(Minecraft mc) {
        if (script == null) {
            return;
        }
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || mc.gameMode == null) {
            end("left the world");
            return;
        }
        if (index >= script.steps.size()) {
            loops++;
            if (!infinite && loops >= repeat) {
                end("finished");
                return;
            }
            index = 0;
            begun = false;
            elapsed = 0;
            counter = 0;
            return;
        }
        Script.Step s = script.steps.get(index);
        held = 0;
        boolean done;
        try {
            done = exec(mc, p, level, s);
        } catch (RuntimeException e) {
            end("error in step " + (index + 1) + ": " + e.getMessage());
            return;
        }
        if (script == null) {
            return;
        }
        if (held != 0) {
            if (!InputOverride.set(OWNER, held)) {
                end("the AI or a recorded macro is controlling movement - stop it first");
                return;
            }
            owning = true;
        } else if (owning) {
            InputOverride.release(OWNER);
            owning = false;
        }
        if (done) {
            if (mc.options != null) {
                mc.options.keyUse.setDown(false);
            }
            index++;
            begun = false;
            elapsed = 0;
            counter = 0;
        }
    }

    private static boolean exec(Minecraft mc, LocalPlayer p, ClientLevel level, Script.Step s) {
        int ticks = Math.max(1, (int) Math.round(s.value * 20.0));
        String text = s.text == null ? "" : s.text.trim();
        NullClient nc = NullClient.get();
        switch (s.kind) {
            case "walk":
                return hold(InputOverride.FORWARD, ticks);
            case "sprint":
                return hold(InputOverride.FORWARD | InputOverride.SPRINT, ticks);
            case "back":
                return hold(InputOverride.BACK, ticks);
            case "left":
                return hold(InputOverride.LEFT, ticks);
            case "right":
                return hold(InputOverride.RIGHT, ticks);
            case "jump":
                return hold(InputOverride.JUMP, ticks);
            case "sneak":
                return hold(InputOverride.SNEAK, ticks);
            case "wait":
                return ++elapsed >= ticks;
            case "turn": {
                if (!begun) {
                    begun = true;
                    targetYaw = p.getYRot() + (float) s.value;
                }
                float diff = Mth.wrapDegrees(targetYaw - p.getYRot());
                if (Math.abs(diff) < 1.0f || ++elapsed > 80) {
                    p.setYRot(p.getYRot() + diff);
                    return true;
                }
                p.setYRot(p.getYRot() + Mth.clamp(diff * 0.25f, -16.0f, 16.0f));
                return false;
            }
            case "pitch": {
                if (!begun) {
                    begun = true;
                    targetPitch = Mth.clamp((float) s.value, -90.0f, 90.0f);
                }
                float diff = targetPitch - p.getXRot();
                if (Math.abs(diff) < 1.0f || ++elapsed > 80) {
                    p.setXRot(targetPitch);
                    return true;
                }
                p.setXRot(p.getXRot() + Mth.clamp(diff * 0.25f, -12.0f, 12.0f));
                return false;
            }
            case "slot":
                p.getInventory().setSelectedSlot(Mth.clamp((int) Math.round(s.value) - 1, 0, 8));
                return true;
            case "attack": {
                int times = Math.max(1, (int) Math.round(s.value));
                if (!begun) {
                    begun = true;
                    counter = 0;
                    elapsed = GAP_ATTACK;
                }
                if (++elapsed >= GAP_ATTACK) {
                    elapsed = 0;
                    attackAhead(mc, p, level);
                    return ++counter >= times;
                }
                return false;
            }
            case "mine": {
                BlockHitResult h = blockAhead(level, p, 4.5);
                if (h != null) {
                    BlockPos bp = h.getBlockPos();
                    if (!bp.equals(mining)) {
                        mc.gameMode.startDestroyBlock(bp, h.getDirection());
                        mining = bp;
                    } else {
                        mc.gameMode.continueDestroyBlock(bp, h.getDirection());
                    }
                    p.swing(InteractionHand.MAIN_HAND);
                } else if (mining != null) {
                    mc.gameMode.stopDestroyBlock();
                    mining = null;
                }
                if (++elapsed >= ticks) {
                    if (mining != null) {
                        mc.gameMode.stopDestroyBlock();
                        mining = null;
                    }
                    return true;
                }
                return false;
            }
            case "use": {
                int times = Math.max(1, (int) Math.round(s.value));
                if (!begun) {
                    begun = true;
                    counter = 0;
                    elapsed = GAP_USE;
                }
                if (++elapsed >= GAP_USE) {
                    elapsed = 0;
                    BlockHitResult h = blockAhead(level, p, 4.5);
                    if (h != null) {
                        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, h);
                    } else {
                        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    }
                    p.swing(InteractionHand.MAIN_HAND);
                    return ++counter >= times;
                }
                return false;
            }
            case "usehold": {
                if (!begun) {
                    begun = true;
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                }
                mc.options.keyUse.setDown(true);
                return ++elapsed >= ticks;
            }
            case "ai":
            case "aitoggle":
                if (!text.isEmpty()) {
                    cmd(".ai " + text);
                }
                return true;
            case "aistart":
                if (!AIRuntime.started()) {
                    cmd(".ai start");
                }
                return true;
            case "aioff":
                cmd(".ai off");
                return true;
            case "stopall":
                cmd(".ai stopall");
                return true;
            case "aitask": {
                if (nc == null || text.isEmpty()) {
                    return true;
                }
                if (!begun) {
                    begun = true;
                    sawAgent = false;
                    elapsed = 0;
                    if (!AIRuntime.started()) {
                        cmd(".ai start");
                    }
                    cmd(".ai " + text);
                    return false;
                }
                boolean active = nc.ai().activeAgent() != null;
                elapsed++;
                if (active) {
                    sawAgent = true;
                }
                if (!sawAgent) {
                    if (elapsed > 10) {
                        p.sendSystemMessage(Component.literal("[Script] the AI did not start '" + text + "' - check .ai why"));
                        return true;
                    }
                    return false;
                }
                if (!active) {
                    return true;
                }
                if (elapsed > Math.max(20, ticks)) {
                    cmd(".ai stop");
                    p.sendSystemMessage(Component.literal("[Script] the AI task hit its time limit and was stopped"));
                    return true;
                }
                return false;
            }
            case "macro": {
                if (nc == null || text.isEmpty()) {
                    return true;
                }
                if (!begun) {
                    begun = true;
                    elapsed = 0;
                    if (!nc.macros().play(text, false)) {
                        p.sendSystemMessage(Component.literal("[Script] could not play the macro '" + text + "'"));
                        return true;
                    }
                    return false;
                }
                return ++elapsed > 5 && !nc.macros().isPlaying();
            }
            case "chat": {
                long now = System.currentTimeMillis();
                if (now - lastChatMs < CHAT_GAP_MS) {
                    return false;
                }
                ClientPacketListener conn = mc.getConnection();
                if (conn != null && !text.isEmpty()) {
                    if (text.startsWith("/")) {
                        conn.sendCommand(text.substring(1));
                    } else {
                        conn.sendChat(text);
                    }
                    lastChatMs = now;
                }
                return true;
            }
            case "say":
                p.sendSystemMessage(Component.literal("[Script] " + text));
                return true;
            case "leave":
                mc.execute(mc::disconnectWithProgressScreen);
                end("left the server");
                return true;
            default:
                throw new IllegalStateException("unknown action '" + s.kind + "'");
        }
    }
}