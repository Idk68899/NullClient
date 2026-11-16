package dev.nullclient.ai.task;

import dev.nullclient.NullClient;
import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.bt.Node;
import dev.nullclient.ai.bt.Status;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

/**
 * Wraps a task with safety: game-mode check, optional "leave when a player comes near", survival reflexes
 * (water clutch), mob combat, and eating. Anything it handles takes priority over the task for that tick. When it
 * hands control back, the task's old route is thrown away so it plans a fresh one from wherever the player ended up
 * (the interruption never counts as a failure).
 */
public final class Guarded implements Node {
    /** When on, disconnects if a player who is not one of your accounts comes within {@value #NEAR} blocks. */
    public static volatile boolean leaveOnPlayer;

    private static final double NEAR = 48.0;

    private final Node child;
    private final Combat combat = new Combat();
    private final Reflexes reflexes = new Reflexes();
    private long tick;
    private boolean wasBusy;

    public Guarded(Node child) {
        this.child = child;
    }

    private static boolean otherPlayerNear(LocalPlayer p, ClientLevel level) {
        NullClient nc = NullClient.get();
        for (AbstractClientPlayer o : level.players()) {
            if (o == p || p.distanceTo(o) > NEAR) {
                continue;
            }
            boolean mine = nc != null && !nc.store().isLocked() && nc.profiles().byName(o.getName().getString()).isPresent();
            if (!mine) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Status tick(Blackboard bb) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        Minecraft mc = Minecraft.getInstance();
        if (p == null || level == null) {
            return child.tick(bb);
        }
        tick++;
        String blocked = Modes.blocker();
        if (blocked != null) {
            bb.set(AIKeys.FAIL_REASON, blocked);
            return Status.FAILURE;
        }
        if (leaveOnPlayer && tick % 10 == 0 && otherPlayerNear(p, level)) {
            RunLog.add("a player came near - leaving the game");
            bb.set(AIKeys.FAIL_REASON, "left the game: a player came near");
            mc.execute(mc::disconnectWithProgressScreen);
            return Status.FAILURE;
        }

        boolean busy = false;
        if (reflexes.clutch(bb, mc, p, level)) {
            busy = true;
        } else if (combat.tick(bb, null)) {
            reflexes.release(mc);
            busy = true;
        } else if (reflexes.eat(mc, p)) {
            busy = true;
        }
        if (busy) {
            if (!wasBusy) {
                Breaker.abort(bb);
                wasBusy = true;
            }
            return Status.RUNNING;
        }
        if (wasBusy) {
            wasBusy = false;
            NavTasks.resetNav(bb);
            bb.set(AIKeys.FAIL_REASON, null);
        }
        return child.tick(bb);
    }

    @Override
    public void reset(Blackboard bb) {
        child.reset(bb);
        combat.clear();
        reflexes.release(Minecraft.getInstance());
        wasBusy = false;
    }

    @Override
    public String name() {
        return "Guard";
    }

    @Override
    public void describeActive(StringBuilder sb) {
        child.describeActive(sb);
    }
}