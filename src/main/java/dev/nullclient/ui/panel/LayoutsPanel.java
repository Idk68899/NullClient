package dev.nullclient.ui.panel;

import dev.nullclient.NullClient;
import dev.nullclient.ui.GlassTheme;
import dev.nullclient.ui.Panel;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.List;

public final class LayoutsPanel extends Panel {
    private final NullClient nc;
    private final ImString name = new ImString(32);
    private String info = "";

    public LayoutsPanel(NullClient nc) {
        super("Layouts", false, 620f, 700f, 420f, 330f);
        this.nc = nc;
    }

    @Override
    protected void draw() {
        ImGui.textWrapped("Saves window positions, sizes and which panels are open. The last layout is restored automatically.");
        ImGui.setNextItemWidth(200f);
        ImGui.inputTextWithHint("##layoutname", "layout name", name);
        ImGui.sameLine();
        if (ImGui.button("Save current")) {
            info = nc.layouts().save(name.get()) ? "Saved." : "Enter a valid name.";
        }
        ImGui.separator();
        List<String> names = nc.layouts().list();
        if (names.isEmpty()) {
            ImGui.textDisabled("No saved layouts.");
        }
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            ImGui.pushID(i);
            ImGui.text(n);
            ImGui.sameLine(240f);
            if (ImGui.button("Load")) {
                info = nc.layouts().load(n) ? "Loaded " + n + "." : "Could not load " + n + ".";
            }
            ImGui.sameLine();
            if (ImGui.button("Delete")) {
                nc.layouts().delete(n);
            }
            ImGui.popID();
        }
        if (!info.isEmpty()) {
            GlassTheme.colored(GlassTheme.GREEN, info);
        }
    }
}