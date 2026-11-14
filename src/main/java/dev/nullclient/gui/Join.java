package dev.nullclient.gui;

import dev.nullclient.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;

/**
 * Switches this game's account to an offline username and joins a server. Works on servers in offline mode.
 * One window plays as one account at a time.
 */
public final class Join {
    private static String pendingIp;
    private static int wait;

    private Join() {
    }

    public static void switchAccount(String name) {
        User user = new User(name, UUIDUtil.createOfflinePlayerUUID(name), "0", Optional.empty(), Optional.empty());
        ((MinecraftAccessor) Minecraft.getInstance()).nullclient$setUser(user);
    }

    public static void joinAs(String name, String ip) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("select an account first");
        }
        if (ip == null || ip.isBlank() || !ServerAddress.isValidAddress(ip)) {
            throw new IllegalArgumentException("enter a valid server ip first");
        }
        switchAccount(name);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            pendingIp = ip;
            wait = 10;
            mc.disconnectWithSavingScreen();
        } else {
            connect(ip);
        }
    }

    /** Call every tick: finishes a join once the previous world or server has been left. */
    public static void tick(Minecraft mc) {
        if (pendingIp == null || mc.level != null || mc.gui.screen() instanceof ConnectScreen) {
            return;
        }
        if (--wait > 0) {
            return;
        }
        String ip = pendingIp;
        pendingIp = null;
        connect(ip);
    }

    private static void connect(String ip) {
        Minecraft mc = Minecraft.getInstance();
        ServerData data = new ServerData("NullClient", ip, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(ip), data, false, null);
    }
}