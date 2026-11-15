package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches server (system) messages for login/register prompts and answers them. It understands /login, /log, /l,
 * /register and /reg, reads how many password arguments the message shows (for example "/register <password>
 * <password>"), and types the command after a short delay when the account has a saved password and Auto is on.
 * If the server wants something it cannot type (an email, a captcha, a code) or no password is saved, it opens a
 * popup instead. Only server messages are considered, never other players' chat.
 */
public final class AutoLogin {
    private static final Pattern CMD = Pattern.compile("/(login|log|l|register|reg)\\b((?:\\s*(?:<[^>]*>|\\[[^\\]]*\\]))*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIRED = Pattern.compile("<([^>]*)>");
    private static final long MIN_GAP_MS = 4000L;
    private static final int DELAY_TICKS = 20;

    private static boolean registered;
    private static Object lastConn;
    private static String pendingKind;
    private static String pendingPassword = "";
    private static int pendingRepeat = 1;
    private static int delay;
    private static String promptKind;
    private static int lastRepeat;
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
        if (s.contains("wrong password") || s.contains("incorrect password") || s.contains("invalid password")
                || s.contains("wrong pass") || s.contains("password is incorrect") || s.contains("password is wrong")) {
            failed = true;
            pendingKind = null;
            return;
        }
        if (s.contains("successfully logged") || s.contains("successful login") || s.contains("logged in successfully")
                || s.contains("successfully registered") || s.contains("successful register") || s.contains("you have been logged")
                || s.contains("has been registered")) {
            failed = false;
            return;
        }
        Matcher m = CMD.matcher(text);
        if (!m.find() || pendingKind != null || promptKind != null) {
            return;
        }
        String cmd = m.group(1).toLowerCase(Locale.ROOT);
        String kind = cmd.startsWith("reg") ? "register" : "login";
        String rest = m.group(2) == null ? "" : m.group(2);
        int count = 0;
        boolean odd = s.contains("captcha");
        Matcher req = REQUIRED.matcher(rest);
        while (req.find()) {
            count++;
            String a = req.group(1).toLowerCase(Locale.ROOT);
            if (a.contains("mail") || a.contains("captcha") || a.contains("code") || a.contains("2fa")) {
                odd = true;
            }
        }
        boolean supported = !odd && (kind.equals("login") ? count <= 1 : count <= 2);
        lastRepeat = count == 0 ? (kind.equals("register") ? 2 : 1) : count;
        if (System.currentTimeMillis() - lastSentMs < MIN_GAP_MS) {
            return;
        }
        NullClient nc = NullClient.get();
        if (nc == null || nc.store().isLocked()) {
            return;
        }
        Profile p = current();
        if (supported && p != null && p.autoLogin && !p.password.isEmpty() && !failed) {
            pendingKind = kind;
            pendingPassword = p.password;
            pendingRepeat = lastRepeat;
            delay = DELAY_TICKS;
        } else {
            promptKind = kind;
        }
    }

    /** Sends the command the way the prompt's popup would: "/login pw" or "/register pw pw". */
    static void send(String kind, String password) {
        send(kind, password, lastRepeat > 0 ? lastRepeat : (kind.equals("register") ? 2 : 1));
    }

    static void send(String kind, String password, int repeat) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null || password.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(kind);
        for (int i = 0; i < Math.max(1, repeat); i++) {
            sb.append(' ').append(password);
        }
        conn.sendCommand(sb.toString());
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
            int repeat = pendingRepeat;
            pendingKind = null;
            pendingPassword = "";
            if (mc.player != null) {
                send(kind, pw, repeat);
            }
        }
        if (promptKind != null && mc.player != null && mc.gui.screen() == null) {
            String kind = promptKind;
            promptKind = null;
            mc.gui.setScreen(new PromptScreen(kind, current()));
        }
    }
}