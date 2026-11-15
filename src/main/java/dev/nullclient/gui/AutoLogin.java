package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Watches server (system) messages for "/login" and "/register" prompts, such as AuthMe's. If the account in use
 * has a saved password and Auto is on, it types the command after a short delay; otherwise it opens a popup.
 * Only server messages are considered, never other players' chat.
 */
public final class AutoLogin {
    private static final long MIN_GAP_MS = 4000L;
    private static final int DELAY_TICKS = 20;

    private static boolean registered;
    private static Object lastConn;
    private static String pendingKind;
    private static String pendingPassword = "";
    private static int delay;
    private static String promptKind;
    private static long lastSentMs;
    private static boolean failed;

    private AutoLogin() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onMessage(message.getString());
            }
        });
    }

    private static Profile current() {
        NullClient nc = NullClient.get();
        if (nc == null || nc.store().isLocked()) {
            return null;
        }
        return nc.profiles().byName(Minecraft.getInstance().getUser().getName()).orElse(null);
    }

    static void onMessage(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        if (s.contains("wrong password") || s.contains("incorrect password") || s.contains("invalid password") || s.contains("wrong pass")) {
            failed = true;
            pendingKind = null;
            return;
        }
        if (s.contains("successfully logged") || s.contains("successful login") || s.contains("logged in successfully")
                || s.contains("successfully registered") || s.contains("successful register")) {
            failed = false;
            return;
        }
        String kind = s.contains("/login") ? "login" : s.contains("/register") ? "register" : null;
        if (kind == null || pendingKind != null || promptKind != null) {
            return;
        }
        if (System.currentTimeMillis() - lastSentMs < MIN_GAP_MS) {
            return;
        }
        NullClient nc = NullClient.get();
        if (nc == null || nc.store().isLocked()) {
            return;
        }
        Profile p = current();
        if (p != null && p.autoLogin && !p.password.isEmpty() && !failed) {
            pendingKind = kind;
            pendingPassword = p.password;
            delay = DELAY_TICKS;
        } else {
            promptKind = kind;
        }
    }

    /** Sends "/login pw" or "/register pw pw" the same way typing the command would. */
    static void send(String kind, String password) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null || password.isEmpty()) {
            return;
        }
        conn.sendCommand(kind + " " + password + (kind.equals("register") ? " " + password : ""));
        lastSentMs = System.currentTimeMillis();
    }

    /** Call every tick. */
    public static void tick(Minecraft mc) {
        Object conn = mc.getConnection();
        if (conn != lastConn) {
            lastConn = conn;
            failed = false;
            pendingKind = null;
            promptKind = null;
            lastSentMs = 0L;
            delay = 0;
        }
        if (pendingKind != null && --delay <= 0) {
            String kind = pendingKind;
            String pw = pendingPassword;
            pendingKind = null;
            pendingPassword = "";
            if (mc.player != null) {
                send(kind, pw);
            }
        }
        if (promptKind != null && mc.player != null && mc.gui.screen() == null) {
            String kind = promptKind;
            promptKind = null;
            mc.gui.setScreen(new PromptScreen(kind, current()));
        }
    }
}