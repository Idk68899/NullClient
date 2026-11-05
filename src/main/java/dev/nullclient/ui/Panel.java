package dev.nullclient.ui;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/** A floating, closable ImGui window. */
public abstract class Panel {
    public final String title;
    public final ImBoolean open;
    private final float x;
    private final float y;
    private final float w;
    private final float h;

    protected Panel(String title, boolean open, float x, float y, float w, float h) {
        this.title = title;
        this.open = new ImBoolean(open);
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public final void render() {
        if (!open.get()) {
            return;
        }
        ImGui.setNextWindowPos(x, y, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(w, h, ImGuiCond.FirstUseEver);
        if (ImGui.begin(title, open, ImGuiWindowFlags.NoCollapse)) {
            GlassTheme.glow();
            draw();
        }
        ImGui.end();
    }

    protected abstract void draw();
}