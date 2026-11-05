package dev.nullclient.ui.panel;

import dev.nullclient.NullClient;
import dev.nullclient.ai.AIAgent;
import dev.nullclient.core.event.Events;
import dev.nullclient.core.event.Subscribe;
import dev.nullclient.core.session.Profile;
import dev.nullclient.ui.GlassTheme;
import dev.nullclient.ui.Panel;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class AIPanel extends Panel {
    private static final int LOG_LIMIT = 60;

    private final NullClient nc;
    private final ImInt profileIdx = new ImInt(0);
    private final ImString task = new ImString("goto 0 64 0", 128);
    private final Deque<String> log = new ArrayDeque<>();
    private String info = "";

    public AIPanel(NullClient nc) {
        super("AI Control", true, 1240f, 60f, 520f, 600f);
        this.nc = nc;
        nc.bus().register(this);
    }

    @Subscribe
    public void onTaskState(Events.AITaskState e) {
        log.addFirst(e.kind() + "  " + e.agent().profile().name + "  " + e.agent().label()
                + (e.message().isEmpty() ? "" : "  - " + e.message()));
        while (log.size() > LOG_LIMIT) {
            log.removeLast();
        }
    }

    @Override
    protected void draw() {
        if (nc.store().isLocked()) {
            GlassTheme.colored(GlassTheme.AMBER, "Unlock the profile store first (Profiles panel).");
            return;
        }
        List<Profile> profiles = new ArrayList<>(nc.profiles().all());
        if (profiles.isEmpty()) {
            ImGui.textDisabled("Create a profile first.");
            return;
        }
        String[] names = profiles.stream().map(p -> p.name).toArray(String[]::new);
        profileIdx.set(Math.min(profileIdx.get(), names.length - 1));
        ImGui.setNextItemWidth(220f);
        ImGui.combo("Profile", profileIdx, names);
        ImGui.setNextItemWidth(320f);
        ImGui.inputTextWithHint("Task", "goto x y z | wander [r] | follow name", task);
        Profile p = profiles.get(profileIdx.get());
        if (ImGui.button("Start")) {
            try {
                String[] tokens = task.get().trim().split("\\s+");
                nc.ai().assign(p, tokens);
                info = "";
            } catch (RuntimeException ex) {
                info = ex.getMessage();
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Stop")) {
            nc.ai().cancel(p);
        }
        ImGui.sameLine();
        if (ImGui.button("Stop all")) {
            nc.ai().cancelAll();
        }
        if (!info.isEmpty()) {
            GlassTheme.colored(GlassTheme.RED, info);
        }
        ImGui.textDisabled("Chat: .ai " + p.name + " <task>");
        ImGui.separator();

        if (ImGui.beginTable("agents", 4, ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp)) {
            ImGui.tableSetupColumn("Profile");
            ImGui.tableSetupColumn("Task");
            ImGui.tableSetupColumn("State");
            ImGui.tableSetupColumn("Ticks");
            ImGui.tableHeadersRow();
            for (AIAgent a : nc.ai().agents()) {
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImGui.text(a.profile().name);
                ImGui.tableNextColumn();
                ImGui.text(a.label());
                ImGui.tableNextColumn();
                float[] c = switch (a.state()) {
                    case RUNNING -> GlassTheme.GREEN;
                    case SUSPENDED -> GlassTheme.AMBER;
                    case FAILED -> GlassTheme.RED;
                    default -> GlassTheme.MUTED;
                };
                GlassTheme.colored(c, a.state().name());
                ImGui.tableNextColumn();
                ImGui.text(Long.toString(a.ticks()));
            }
            ImGui.endTable();
        }
        AIAgent active = nc.ai().activeAgent();
        if (active != null) {
            GlassTheme.colored(GlassTheme.CYAN, "Tree: " + active.activeNodes());
        }
        ImGui.separator();
        GlassTheme.colored(GlassTheme.CYAN, "Event log");
        ImGui.beginChild("ailog", 0f, 0f, true);
        for (String line : log) {
            ImGui.textWrapped(line);
        }
        ImGui.endChild();
    }
}