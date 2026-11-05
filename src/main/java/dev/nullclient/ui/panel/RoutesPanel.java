package dev.nullclient.ui.panel;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import dev.nullclient.core.session.Route;
import dev.nullclient.ui.GlassTheme;
import dev.nullclient.ui.Panel;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

public final class RoutesPanel extends Panel {
    private static final String[] TYPES = {"SOCKS5", "HTTP"};

    private final NullClient nc;
    private final ImString name = new ImString(32);
    private final ImString host = new ImString(128);
    private final ImString user = new ImString(64);
    private final ImString pass = new ImString(64);
    private final ImInt port = new ImInt(1080);
    private final ImInt type = new ImInt(0);
    private final ImInt profileIdx = new ImInt(0);
    private final ImInt routeIdx = new ImInt(0);
    private String info = "";
    private boolean infoIsError;

    public RoutesPanel(NullClient nc) {
        super("Routes", true, 620f, 60f, 600f, 600f);
        this.nc = nc;
    }

    private void say(String text, boolean error) {
        info = text;
        infoIsError = error;
    }

    @Override
    protected void draw() {
        if (nc.store().isLocked()) {
            GlassTheme.colored(GlassTheme.AMBER, "Unlock the profile store first (Profiles panel).");
            return;
        }
        drawAdd();
        ImGui.separator();
        drawTable();
        ImGui.separator();
        drawAssign();
        if (!info.isEmpty()) {
            GlassTheme.colored(infoIsError ? GlassTheme.RED : GlassTheme.GREEN, info);
        }
    }

    private void drawAdd() {
        GlassTheme.colored(GlassTheme.CYAN, "Add route");
        ImGui.setNextItemWidth(160f);
        ImGui.inputTextWithHint("##rname", "label", name);
        ImGui.sameLine();
        ImGui.setNextItemWidth(110f);
        ImGui.combo("##rtype", type, TYPES);
        ImGui.setNextItemWidth(240f);
        ImGui.inputTextWithHint("##rhost", "host or ip", host);
        ImGui.sameLine();
        ImGui.setNextItemWidth(110f);
        ImGui.inputInt("##rport", port, 0, 0);
        ImGui.setNextItemWidth(160f);
        ImGui.inputTextWithHint("##ruser", "username (optional)", user);
        ImGui.sameLine();
        ImGui.setNextItemWidth(160f);
        ImGui.inputTextWithHint("##rpass", "password (optional)", pass, ImGuiInputTextFlags.Password);
        if (ImGui.button("Add route")) {
            try {
                Route r = nc.routes().add(name.get(), type.get() == 0 ? Route.Type.SOCKS5 : Route.Type.HTTP,
                        host.get(), port.get(), user.get(), pass.get());
                name.set("");
                host.set("");
                user.set("");
                pass.set("");
                say("Added '" + r.name + "'.", false);
            } catch (RuntimeException ex) {
                say(ex.getMessage(), true);
            }
        }
    }

    private void drawTable() {
        List<Route> routes = new ArrayList<>(nc.routes().all());
        if (ImGui.beginTable("routes", 4, ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp)) {
            ImGui.tableSetupColumn("Name");
            ImGui.tableSetupColumn("Endpoint");
            ImGui.tableSetupColumn("Status");
            ImGui.tableSetupColumn("Actions");
            ImGui.tableHeadersRow();
            for (int i = 0; i < routes.size(); i++) {
                Route r = routes.get(i);
                ImGui.pushID(i);
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImGui.text(r.name);
                ImGui.tableNextColumn();
                ImGui.text(r.endpoint() + (r.hasAuth() ? "  (auth)" : ""));
                ImGui.tableNextColumn();
                float[] c = r.latencyMs >= 0 ? GlassTheme.GREEN : r.testing ? GlassTheme.AMBER : GlassTheme.MUTED;
                GlassTheme.colored(c, r.status);
                ImGui.tableNextColumn();
                if (ImGui.button(r.testing ? "..." : "Test") && !r.testing) {
                    nc.routes().test(r);
                }
                ImGui.sameLine();
                if (ImGui.button("Remove")) {
                    nc.routes().remove(r);
                    say("Removed '" + r.name + "'.", false);
                }
                ImGui.popID();
            }
            ImGui.endTable();
        }
    }

    private void drawAssign() {
        GlassTheme.colored(GlassTheme.CYAN, "Assign route to profile");
        List<Profile> profiles = new ArrayList<>(nc.profiles().all());
        List<Route> routes = new ArrayList<>(nc.routes().all());
        if (profiles.isEmpty() || routes.isEmpty()) {
            ImGui.textDisabled("Needs at least one profile and one route.");
            return;
        }
        String[] pl = profiles.stream().map(p -> p.name).toArray(String[]::new);
        String[] rl = routes.stream().map(r -> r.name + "  [" + r.endpoint() + "]").toArray(String[]::new);
        profileIdx.set(Math.min(profileIdx.get(), pl.length - 1));
        routeIdx.set(Math.min(routeIdx.get(), rl.length - 1));
        ImGui.setNextItemWidth(200f);
        ImGui.combo("Profile", profileIdx, pl);
        ImGui.setNextItemWidth(320f);
        ImGui.combo("Route", routeIdx, rl);
        Profile p = profiles.get(profileIdx.get());
        if (ImGui.button("Assign")) {
            nc.routes().assign(p, routes.get(routeIdx.get()));
            say(p.name + " -> " + routes.get(routeIdx.get()).name, false);
        }
        ImGui.sameLine();
        if (ImGui.button("Clear assignment")) {
            nc.routes().assign(p, null);
            say("Cleared route for " + p.name, false);
        }
        Route current = nc.profiles().routeOf(p);
        ImGui.textDisabled(p.name + " currently uses: " + (current == null ? "no route" : current.name));
    }
}