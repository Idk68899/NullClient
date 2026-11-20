package dev.nullclient.gui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Finds out what a server runs using only what it has already told your client: its brand, and the command list it sent
 * when you joined (commands like "essentials:home" reveal the plugin that registered them). Optionally runs /plugins
 * and reads the answer. It never guesses or brute-forces command names.
 */
final class PluginProbe {
    static final class Report {
        String brand = "";
        String version = "";
        int roots;
        String problem = "";
        final List<String> software = new ArrayList<>();
        final List<String> namespaces = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
    }

    private static final Set<String> SOFTWARE = Set.of("bukkit", "spigot", "paper", "purpur", "folia", "velocity", "bungeecord", "waterfall");
    private static final Map<String, String> HINTS = new LinkedHashMap<>();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.\\-]{2,32}");

    private static boolean listening;
    private static long until;
    private static final LinkedHashSet<String> FOUND = new LinkedHashSet<>();
    private static volatile String reply = "";

    static {
        for (String c : new String[]{"login", "register", "changepassword", "unregister", "authme"}) {
            HINTS.put(c, "AuthMe or a similar login plugin");
        }
        for (String c : new String[]{"sethome", "tpa", "tpaccept", "kit", "nick", "balance", "bal", "pay", "back"}) {
            HINTS.put(c, "EssentialsX / CMI style essentials");
        }
        HINTS.put("lp", "LuckPerms");
        HINTS.put("luckperms", "LuckPerms");
        HINTS.put("co", "CoreProtect");
        HINTS.put("coreprotect", "CoreProtect");
        HINTS.put("worldedit", "WorldEdit");
        HINTS.put("we", "WorldEdit");
        HINTS.put("dynmap", "Dynmap");
        HINTS.put("bluemap", "BlueMap");
        HINTS.put("papi", "PlaceholderAPI");
        HINTS.put("viaversion", "ViaVersion");
        HINTS.put("viaver", "ViaVersion");
        HINTS.put("geyser", "Geyser (Bedrock support)");
        HINTS.put("floodgate", "Floodgate (Bedrock support)");
        HINTS.put("mv", "Multiverse");
        HINTS.put("multiverse", "Multiverse");
        HINTS.put("spark", "spark (profiler)");
        HINTS.put("plan", "Plan (analytics)");
        HINTS.put("citizens", "Citizens (NPCs)");
        HINTS.put("npc", "Citizens (NPCs)");
        HINTS.put("mcmmo", "mcMMO");
        HINTS.put("ah", "an auction house plugin");
        HINTS.put("discord", "DiscordSRV or similar");
        HINTS.put("claim", "a land-claim plugin");
        HINTS.put("res", "Residence");
        HINTS.put("gp", "GriefPrevention");
    }

    private PluginProbe() {
    }

    private static CommandDispatcher<?> dispatcher(Object conn) {
        for (Method m : conn.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && CommandDispatcher.class.isAssignableFrom(m.getReturnType())) {
                try {
                    return (CommandDispatcher<?>) m.invoke(conn);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // try the next candidate
                }
            }
        }
        return null;
    }

    static Report scan() {
        Report r = new Report();
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.level == null) {
            r.problem = "not connected to a server";
            return r;
        }
        String brand = conn.serverBrand();
        r.brand = brand == null ? "" : brand;
        ServerData sd = mc.getCurrentServer();
        r.version = sd != null && sd.version != null ? sd.version.getString() : "";
        CommandDispatcher<?> d = dispatcher(conn);
        if (d == null) {
            r.problem = "could not read the server's command list on this version";
            return r;
        }
        Set<String> names = new TreeSet<>();
        for (CommandNode<?> n : d.getRoot().getChildren()) {
            names.add(n.getName());
        }
        r.roots = names.size();
        Set<String> ns = new TreeSet<>();
        for (String n : names) {
            int i = n.indexOf(':');
            if (i > 0) {
                ns.add(n.substring(0, i).toLowerCase(Locale.ROOT));
            }
        }
        for (String s : ns) {
            if (s.equals("minecraft")) {
                continue;
            }
            if (SOFTWARE.contains(s)) {
                r.software.add(s);
            } else {
                r.namespaces.add(s);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String n : names) {
            String base = n.contains(":") ? n.substring(n.indexOf(':') + 1) : n;
            String hint = HINTS.get(base.toLowerCase(Locale.ROOT));
            if (hint != null && seen.add(hint)) {
                r.hints.add(hint + "  (via /" + base + ")");
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ /plugins

    static String reply() {
        return reply;
    }

    static synchronized List<String> plugins() {
        return new ArrayList<>(FOUND);
    }

    private static void ensureListener() {
        if (listening) {
            return;
        }
        listening = true;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onMessage(message.getString());
            }
        });
    }

    /** Sends the normal /plugins command and reads the answer for a few seconds, if the server allows it. */
    static synchronized void runPluginsCommand() {
        ensureListener();
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) {
            reply = "not connected to a server";
            return;
        }
        FOUND.clear();
        reply = "waiting for the server's answer...";
        until = System.currentTimeMillis() + 5000L;
        conn.sendCommand("plugins");
    }

    private static synchronized void onMessage(String text) {
        if (System.currentTimeMillis() > until) {
            return;
        }
        String low = text.toLowerCase(Locale.ROOT);
        String trimmed = text.trim();
        if (low.contains("unknown or incomplete command") || low.contains("unknown command") || low.contains("permission")) {
            reply = "the server did not allow /plugins";
            until = 0L;
            return;
        }
        if (!(low.contains("plugin") || trimmed.startsWith("-") || trimmed.startsWith("\u2022"))) {
            return;
        }
        String body = low.contains("plugin") && text.indexOf(':') >= 0 ? text.substring(text.indexOf(':') + 1) : text;
        for (String tok : body.replaceAll("\u00a7.", "").split("[,\\n]")) {
            String t = tok.trim().replaceAll("^[-\u2022\\s]+", "");
            if (TOKEN.matcher(t).matches() && !t.toLowerCase(Locale.ROOT).contains("plugins")) {
                FOUND.add(t);
            }
        }
        reply = FOUND.isEmpty() ? "no plugin names found in the answer" : "the server listed " + FOUND.size() + " plugin(s)";
    }
}