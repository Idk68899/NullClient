package dev.nullclient.ui;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

/** Glassmorphism look: translucent dark surfaces, cyan/magenta neon accents, soft gradient sheen. */
public final class GlassTheme {
    public static final float[] CYAN = {0.00f, 0.92f, 1.00f, 1.0f};
    public static final float[] MAGENTA = {1.00f, 0.22f, 0.80f, 1.0f};
    public static final float[] GREEN = {0.20f, 1.00f, 0.60f, 1.0f};
    public static final float[] RED = {1.00f, 0.30f, 0.38f, 1.0f};
    public static final float[] AMBER = {1.00f, 0.80f, 0.25f, 1.0f};
    public static final float[] MUTED = {0.62f, 0.68f, 0.78f, 1.0f};

    private GlassTheme() {
    }

    public static void apply() {
        ImGuiStyle s = ImGui.getStyle();
        s.setWindowRounding(12f);
        s.setChildRounding(8f);
        s.setFrameRounding(8f);
        s.setPopupRounding(8f);
        s.setGrabRounding(8f);
        s.setScrollbarRounding(10f);
        s.setTabRounding(8f);
        s.setWindowBorderSize(1f);
        s.setFrameBorderSize(1f);
        s.setWindowPadding(14f, 12f);
        s.setFramePadding(10f, 6f);
        s.setItemSpacing(10f, 8f);
        s.setScrollbarSize(12f);

        s.setColor(ImGuiCol.Text, 0.92f, 0.95f, 1.00f, 1.00f);
        s.setColor(ImGuiCol.TextDisabled, 0.50f, 0.56f, 0.66f, 1.00f);
        s.setColor(ImGuiCol.WindowBg, 0.04f, 0.05f, 0.09f, 0.72f);
        s.setColor(ImGuiCol.ChildBg, 1.00f, 1.00f, 1.00f, 0.03f);
        s.setColor(ImGuiCol.PopupBg, 0.05f, 0.06f, 0.11f, 0.94f);
        s.setColor(ImGuiCol.Border, 0.00f, 0.92f, 1.00f, 0.28f);
        s.setColor(ImGuiCol.BorderShadow, 0f, 0f, 0f, 0f);
        s.setColor(ImGuiCol.FrameBg, 1.00f, 1.00f, 1.00f, 0.06f);
        s.setColor(ImGuiCol.FrameBgHovered, 0.00f, 0.92f, 1.00f, 0.14f);
        s.setColor(ImGuiCol.FrameBgActive, 0.00f, 0.92f, 1.00f, 0.22f);
        s.setColor(ImGuiCol.TitleBg, 0.03f, 0.04f, 0.08f, 0.80f);
        s.setColor(ImGuiCol.TitleBgActive, 0.06f, 0.09f, 0.18f, 0.92f);
        s.setColor(ImGuiCol.TitleBgCollapsed, 0.03f, 0.04f, 0.08f, 0.60f);
        s.setColor(ImGuiCol.MenuBarBg, 0.03f, 0.04f, 0.08f, 0.85f);
        s.setColor(ImGuiCol.ScrollbarBg, 0f, 0f, 0f, 0.20f);
        s.setColor(ImGuiCol.ScrollbarGrab, 0.00f, 0.92f, 1.00f, 0.30f);
        s.setColor(ImGuiCol.ScrollbarGrabHovered, 0.00f, 0.92f, 1.00f, 0.50f);
        s.setColor(ImGuiCol.ScrollbarGrabActive, 0.00f, 0.92f, 1.00f, 0.70f);
        s.setColor(ImGuiCol.CheckMark, 0.20f, 1.00f, 0.60f, 1.00f);
        s.setColor(ImGuiCol.SliderGrab, 1.00f, 0.22f, 0.80f, 0.80f);
        s.setColor(ImGuiCol.SliderGrabActive, 1.00f, 0.22f, 0.80f, 1.00f);
        s.setColor(ImGuiCol.Button, 0.00f, 0.92f, 1.00f, 0.16f);
        s.setColor(ImGuiCol.ButtonHovered, 0.00f, 0.92f, 1.00f, 0.32f);
        s.setColor(ImGuiCol.ButtonActive, 0.00f, 0.92f, 1.00f, 0.50f);
        s.setColor(ImGuiCol.Header, 1.00f, 0.22f, 0.80f, 0.18f);
        s.setColor(ImGuiCol.HeaderHovered, 1.00f, 0.22f, 0.80f, 0.32f);
        s.setColor(ImGuiCol.HeaderActive, 1.00f, 0.22f, 0.80f, 0.50f);
        s.setColor(ImGuiCol.Separator, 0.00f, 0.92f, 1.00f, 0.25f);
        s.setColor(ImGuiCol.SeparatorHovered, 0.00f, 0.92f, 1.00f, 0.60f);
        s.setColor(ImGuiCol.SeparatorActive, 0.00f, 0.92f, 1.00f, 0.90f);
        s.setColor(ImGuiCol.ResizeGrip, 0.00f, 0.92f, 1.00f, 0.20f);
        s.setColor(ImGuiCol.ResizeGripHovered, 0.00f, 0.92f, 1.00f, 0.50f);
        s.setColor(ImGuiCol.ResizeGripActive, 0.00f, 0.92f, 1.00f, 0.80f);
        s.setColor(ImGuiCol.Tab, 1.00f, 1.00f, 1.00f, 0.05f);
        s.setColor(ImGuiCol.TabHovered, 0.00f, 0.92f, 1.00f, 0.35f);
        s.setColor(ImGuiCol.TabActive, 0.00f, 0.92f, 1.00f, 0.28f);
        s.setColor(ImGuiCol.TableHeaderBg, 0.00f, 0.92f, 1.00f, 0.10f);
        s.setColor(ImGuiCol.TableBorderStrong, 0.00f, 0.92f, 1.00f, 0.25f);
        s.setColor(ImGuiCol.TableBorderLight, 1.00f, 1.00f, 1.00f, 0.08f);
        s.setColor(ImGuiCol.TableRowBg, 0f, 0f, 0f, 0f);
        s.setColor(ImGuiCol.TableRowBgAlt, 1.00f, 1.00f, 1.00f, 0.03f);
        s.setColor(ImGuiCol.TextSelectedBg, 0.00f, 0.92f, 1.00f, 0.35f);
    }

    /** Draws the diagonal glass sheen and neon top rule inside the current window. Call right after begin(). */
    public static void glow() {
        ImDrawList dl = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float w = ImGui.getWindowWidth();
        float h = ImGui.getWindowHeight();
        int tl = ImGui.getColorU32(0.00f, 0.92f, 1.00f, 0.08f);
        int tr = ImGui.getColorU32(1.00f, 0.22f, 0.80f, 0.06f);
        int clear = ImGui.getColorU32(0f, 0f, 0f, 0f);
        dl.addRectFilledMultiColor(x, y, x + w, y + h, tl, tr, clear, clear);
        dl.addLine(x + 14f, y + 1f, x + w - 14f, y + 1f, ImGui.getColorU32(0.00f, 0.92f, 1.00f, 0.75f), 2f);
    }

    public static void colored(float[] c, String text) {
        ImGui.textColored(c[0], c[1], c[2], c[3], text);
    }
}