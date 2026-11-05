package dev.nullclient.ui.panel;

import dev.nullclient.NullClient;
import dev.nullclient.core.macro.Macro;
import dev.nullclient.core.macro.MacroManager;
import dev.nullclient.ui.GlassTheme;
import dev.nullclient.ui.Panel;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

public final class MacrosPanel extends Panel {
    private final NullClient nc;
    private final ImString name = new ImString(32);
    private final ImBoolean loop = new ImBoolean(false);
    private String info = "";

    public MacrosPanel(NullClient nc) {
        super("Macros", true, 40f, 700f, 560f, 330f);
        this.nc = nc;
    }

    @Override
    protected void draw() {
        MacroManager m = nc.macros();
        ImGui.textWrapped("Records movement keys and view angles while the menu is closed. Close the menu (RSHIFT) after pressing Record.");
        if (m.isRecording()) {
            GlassTheme.colored(GlassTheme.RED, "REC " + m.recordingName() + "  " + m.recordedTicks() + " ticks");
            if (ImGui.button("Stop & save")) {
                m.stopRecording(true);
                info = "Saved.";
            }
            ImGui.sameLine();
            if (ImGui.button("Discard")) {
                m.stopRecording(false);
                info = "Discarded.";
            }
        } else {
            ImGui.setNextItemWidth(220f);
            ImGui.inputTextWithHint("##macroname", "macro name", name);
            ImGui.sameLine();
            if (ImGui.button("Record")) {
                info = m.startRecording(name.get()) ? "" : "Cannot record (empty name, or busy).";
            }
        }
        ImGui.checkbox("Loop playback", loop);
        if (m.isPlaying()) {
            GlassTheme.colored(GlassTheme.GREEN, "Playing: " + m.playingName());
            ImGui.sameLine();
            if (ImGui.button("Stop")) {
                m.stopPlayback();
            }
        }
        List<Macro> list = new ArrayList<>(m.all());
        if (ImGui.beginTable("macros", 3, ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp)) {
            ImGui.tableSetupColumn("Name");
            ImGui.tableSetupColumn("Length");
            ImGui.tableSetupColumn("Actions");
            ImGui.tableHeadersRow();
            for (int i = 0; i < list.size(); i++) {
                Macro mac = list.get(i);
                ImGui.pushID(i);
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                ImGui.text(mac.name);
                ImGui.tableNextColumn();
                ImGui.text(String.format("%.1fs (%d frames)", mac.length / 20.0, mac.frames.size()));
                ImGui.tableNextColumn();
                if (ImGui.button("Play")) {
                    info = m.play(mac.name, loop.get()) ? "" : "Cannot play (recording in progress?).";
                }
                ImGui.sameLine();
                if (ImGui.button("Delete")) {
                    m.delete(mac.name);
                }
                ImGui.popID();
            }
            ImGui.endTable();
        }
        if (!info.isEmpty()) {
            GlassTheme.colored(GlassTheme.AMBER, info);
        }
    }
}