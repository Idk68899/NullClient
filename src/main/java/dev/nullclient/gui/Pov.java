package dev.nullclient.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Looks through another player's eyes (their entity becomes the camera). Only works on players loaded in your world. */
public final class Pov {
    private Pov() {
    }

    public static void view(Entity e) {
        Minecraft.getInstance().setCameraEntity(e);
    }

    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
    }

    public static boolean viewingOther() {
        Minecraft mc = Minecraft.getInstance();
        Entity cam = mc.getCameraEntity();
        return cam != null && mc.player != null && cam != mc.player;
    }

    /** Call every tick: snaps back to yourself if the watched player left or you disconnected. */
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Entity cam = mc.getCameraEntity();
        if (cam != null && cam != mc.player && cam.isRemoved()) {
            mc.setCameraEntity(mc.player);
        }
    }
}