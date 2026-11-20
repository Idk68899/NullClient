package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.ai.task.Modes;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Everything the client knows about the server and world you are in: software, plugins, anticheat, players, time, weather. */
final class ServerPage implements Page {
    private record Row(String name, int ping, String mode, int dist, boolean mine) {
    }

    private static final String[] TIME_METHODS = {"getDayTime", "getDefaultClockTime", "getTimeOfDay", "dayTime"};

    private List<Row> rows = List.of();
    private PluginProbe.Report rep;
    private List<String> anticheats = List.of();
    private List<String> report = List.of();
    private String note = "";

    @Override
    public String title() {
        return "Server Info";
    }

    @Override
    public int width() {
        return 340;
    }

    @Override
    public int height() {
        return 420;
    }

    private static Object call(Object o, String name) {
        if (o == null) {
            return null;
        }
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Time of day in ticks. Looked up by name because 26.2 moved it; falls back to the game tick counter. */
    private static long dayTime(ClientLevel level) {
        Object[] sources = {level, call(level, "getLevelData")};
        for (Object src : sources) {
            for (String name : TIME_METHODS) {
                Object r = call(src, name);
                if (r instanceof Long l) {
                    return l;
                }
            }
        }
        return level.getGameTime();
    }

    private static String dimension(ClientLevel level) {
        String d = level.dimension().toString();
        int i = d.indexOf("/ ");
        if (i >= 0) {
            d = d.substring(i + 2);
        }
        return d.replace("]", "").replace("minecraft:", "");
    }

    private static List<Row> tabList(Minecraft mc, Set<String> mine) {
        List<Row> out = new ArrayList<>();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.level == null || mc.player == null) {
            return out;
        }
        Object infos = call(conn, "getOnlinePlayers");
        if (!(infos instanceof Collection<?>)) {
            infos = call(conn, "getListedOnlinePlayers");
        }
        if (!(infos instanceof Collection<?> col)) {
            return out;
        }
        for (Object info : col) {
            Object prof = call(info, "getProfile");
            Object n = call(prof, "name");
            if (n == null) {
                n = call(prof, "getName");
            }
            if (n == null) {
                continue;
            }
            String name = String.valueOf(n);
            Object lat = call(info, "getLatency");
            Object gm = call(info, "getGameMode");
            int dist = -1;
            for (AbstractClientPlayer pl : mc.level.players()) {
                if (pl.getName().getString().equals(name)) {
                    dist = (int) Math.round(pl.distanceTo(mc.player));
                    break;
                }
            }
            out.add(new Row(name, lat instanceof Integer i ? i : -1, gm == null ? "" : String.valueOf(gm).toLowerCase(Locale.ROOT),
                    dist, mine.contains(name.toLowerCase(Locale.ROOT))));
        }
        out.sort(Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String clock(long dayTime) {
        long dt = ((dayTime % 24000L) + 24000L) % 24000L;
        int h = (int) ((dt / 1000L + 6L) % 24L);
        int m = (int) ((dt % 1000L) * 60L / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d (day %d)", h, m, dayTime / 24000L + 1L);
    }

    private void refresh(Minecraft mc) {
        Set<String> mine = new HashSet<>();
        NullClient nc = Ui.nc();
        if (nc != null && !nc.store().isLocked()) {
            for (Profile pr : nc.profiles().all()) {
                mine.add(pr.name.toLowerCase(Locale.ROOT));
            }
        }
        rows = tabList(mc, mine);
        rep = PluginProbe.scan();
        anticheats = PluginProbe.anticheats(rep);
        report = buildReport(mc);
    }

    private List<String> buildReport(Minecraft mc) {
        List<String> out = new ArrayList<>();
        ServerData sd = mc.getCurrentServer();
        ClientLevel level = mc.level;
        out.add("server: " + (sd == null ? "singleplayer / LAN" : sd.name + " (" + sd.ip + ")"));
        out.add("software: " + (rep == null || rep.brand.isEmpty() ? "unknown" : rep.brand) + ", version " + (sd == null || sd.version == null ? "?" : sd.version.getString()));
        if (level != null) {
            out.add("world: " + dimension(level) + ", " + clock(dayTime(level)) + ", "
                    + (level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear") + ", difficulty " + level.getDifficulty());
        }
        out.add("anticheat: " + (anticheats.isEmpty() ? "none detected from visible commands" : String.join("; ", anticheats)));
        if (rep != null) {
            out.add("plugins with commands: " + (rep.namespaces.isEmpty() ? "none found" : String.join(", ", rep.namespaces)));
            out.add("likely plugins: " + (rep.hints.isEmpty() ? "none" : String.join("; ", rep.hints)));
        }
        List<String> listed = PluginProbe.plugins();
        if (!listed.isEmpty()) {
            out.add("/plugins answered: " + String.join(", ", listed));
        }
        StringBuilder sb = new StringBuilder("players (" + rows.size() + "): ");
        for (Row r : rows) {
            sb.append(r.name()).append(", ");
        }
        out.add(sb.toString());
        return out;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        ui.label("Playing as: " + mc.getUser().getName(), x, y, w, Theme.accent());
        y += 14;
        if (mc.level == null || mc.player == null) {
            ui.text("Not in a world or server.", x, y, Theme.MUTED);
            return;
        }
        if (rep == null || ui.frame % 40 == 0) {
            refresh(mc);
        }
        ServerData sd = mc.getCurrentServer();
        ClientLevel level = mc.level;
        int third = (w - 8) / 3;
        ui.button("Refresh", x, y, third, 18, () -> {
            refresh(mc);
            note = "refreshed";
        });
        ui.button("Run /plugins", x + third + 4, y, third, 18, () -> {
            PluginProbe.runPluginsCommand();
            note = "sent /plugins - the answer shows under Plugins";
        });
        ui.button("Copy report", x + 2 * (third + 4), y, third, 18, () -> {
            Ui.copy(String.join("\n", report));
            note = "report copied";
        });
        y += 22;
        ui.label(note.isEmpty() ? PluginProbe.reply() : note, x, y, w, Theme.MUTED);
        y += 16;

        ui.text("Server", x, y, Theme.accent());
        y += 12;
        if (sd == null) {
            ui.label("Singleplayer or LAN (no saved server entry).", x, y, w, Theme.TEXT);
            y += 12;
        } else {
            ui.label("Name: " + sd.name, x, y, w, Theme.TEXT);
            y += 11;
            ui.label("Address: " + sd.ip, x, y, w - 60, Theme.TEXT);
            ui.button("Copy IP", x + w - 56, y - 3, 56, 14, () -> {
                Ui.copy(sd.ip);
                note = "address copied";
            });
            y += 12;
            ui.label("Version: " + (sd.version == null ? "?" : sd.version.getString()), x, y, w, Theme.TEXT);
            y += 11;
            ui.label("MOTD: " + (sd.motd == null ? "-" : sd.motd.getString().replace('\n', ' ')), x, y, w, Theme.MUTED);
            y += 11;
            Object pl = sd.players;
            Object max = call(pl, "max");
            Object online = call(pl, "online");
            if (online != null) {
                ui.label("Players (server count): " + online + (max == null ? "" : " / " + max), x, y, w, Theme.TEXT);
                y += 11;
            }
        }
        ui.label("Software: " + (rep.brand.isEmpty() ? "unknown" : rep.brand), x, y, w, Theme.TEXT);
        y += 11;
        int myPing = -1;
        String me = mc.getUser().getName();
        for (Row r : rows) {
            if (r.name().equalsIgnoreCase(me)) {
                myPing = r.ping();
            }
        }
        if (myPing < 0 && sd != null) {
            myPing = (int) sd.ping;
        }
        ui.label("Your ping: " + (myPing < 0 ? "?" : myPing + " ms") + "    Tab list: " + rows.size() + " players", x, y, w, Theme.TEXT);
        y += 16;

        ui.text("World", x, y, Theme.accent());
        y += 12;
        ui.label("Dimension: " + dimension(level) + "    Game mode: " + Modes.type(), x, y, w, Theme.TEXT);
        y += 11;
        ui.label("Time: " + clock(dayTime(level)) + "    Weather: " + (level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear"), x, y, w, Theme.TEXT);
        y += 11;
        ui.label("Difficulty: " + level.getDifficulty() + "    Players loaded near you: " + Math.max(0, level.players().size() - 1), x, y, w, Theme.TEXT);
        y += 16;

        ui.text("Anticheat", x, y, Theme.accent());
        y += 12;
        if (anticheats.isEmpty()) {
            ui.label("none detected from the commands the server shows you", x, y, w, Theme.MUTED);
            y += 11;
            ui.label("(servers often hide this - it does not mean there is none)", x, y, w, Theme.MUTED);
            y += 11;
        }
        for (String a : anticheats) {
            ui.label(a, x + 4, y, w - 4, Theme.WARN);
            y += 11;
        }
        y += 6;

        ui.text("Plugins", x, y, Theme.accent());
        y += 12;
        if (rep.problem.isEmpty()) {
            ui.label("with their own commands: " + (rep.namespaces.isEmpty() ? "none found" : String.join(", ", rep.namespaces)), x, y, w, Theme.TEXT);
            y += 11;
            for (String hint : rep.hints) {
                ui.label(hint, x + 4, y, w - 4, Theme.TEXT);
                y += 11;
            }
            List<String> listed = PluginProbe.plugins();
            if (!listed.isEmpty()) {
                ui.label("/plugins: " + String.join(", ", listed), x, y, w, Theme.GOOD);
                y += 11;
            }
        } else {
            ui.label(rep.problem, x, y, w, Theme.WARN);
            y += 11;
        }
        y += 8;

        ui.text("Players (" + rows.size() + ")", x, y, Theme.accent());
        y += 12;
        if (rows.isEmpty()) {
            ui.label("the server did not send a tab list", x, y, w, Theme.MUTED);
            y += 11;
        }
        for (Row r : rows) {
            boolean self = r.name().equalsIgnoreCase(me);
            String line = r.name() + (r.mine() ? "  [your account]" : "")
                    + "   " + (r.ping() < 0 ? "" : r.ping() + "ms") + (r.mode().isEmpty() ? "" : "  " + r.mode())
                    + (r.dist() >= 0 ? "  " + r.dist() + "m" : "");
            ui.label(line, x + 4, y, w - 4, self ? Theme.accent() : r.mine() ? Theme.GOOD : Theme.TEXT);
            y += 11;
        }
    }
}