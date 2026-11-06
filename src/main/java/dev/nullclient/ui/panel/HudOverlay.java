package dev.nullclient.ui.panel;

import dev.nullclient.NullClient;
import dev.nullclient.ai.AIAgent;
import dev.nullclient.core.macro.MacroManager;
import dev.nullclient.ui.GlassTheme;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;

/** Small status plate in the corner. Always visible, so you can tell the ImGui overlay is drawing. */
public final class HudOverlay {
    private static final int FLAGS = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.AlwaysAutoResize
            | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoFocusOnAppearing
            | ImGuiWindowFlags.NoNav | ImGuiWindowFlags.NoInputs | ImGuiWindowFlags.NoMove;

    private HudOverlay() {
    }

    public static void render(NullClient nc) {
        AIAgent agent = nc.ai().activeAgent();
        MacroManager macros = nc.macros();
        ImGui.setNextWindowPos(12f, 34f, ImGuiCond.Always);
        ImGui.setNextWindowBgAlpha(0.45f);
        if (ImGui.begin("##nullhud", FLAGS)) {
            GlassTheme.colored(GlassTheme.CYAN, "NullClient");
            ImGui.sameLine();
            GlassTheme.colored(GlassTheme.MUTED, ".ai menu");
            if (agent != null) {
                GlassTheme.colored(GlassTheme.CYAN, "AI  " + agent.profile().name + "  " + agent.label());
                GlassTheme.colored(GlassTheme.MUTED, agent.activeNodes());
            }
            if (macros.isRecording()) {
                GlassTheme.colored(GlassTheme.RED, "REC  " + macros.recordingName() + "  " + macros.recordedTicks() + "t");
            }
            if (macros.isPlaying()) {
                GlassTheme.colored(GlassTheme.GREEN, "PLAY  " + macros.playingName());
            }
        }
        ImGui.end();
    }
}