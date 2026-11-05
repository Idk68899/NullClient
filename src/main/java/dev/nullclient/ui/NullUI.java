package dev.nullclient.ui;

import dev.nullclient.NullClient;
import dev.nullclient.core.event.Events;
import dev.nullclient.ui.panel.AIPanel;
import dev.nullclient.ui.panel.HudOverlay;
import dev.nullclient.ui.panel.LayoutsPanel;
import dev.nullclient.ui.panel.MacrosPanel;
import dev.nullclient.ui.panel.ProfilesPanel;
import dev.nullclient.ui.panel.RoutesPanel;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;

/** ImGui context owner. Frames are driven by WindowMixin on the render thread. */
public final class NullUI {
    private final NullClient nc;
    private final List<Panel> panels;
    private final ImGuiImplGlfw glfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 gl3 = new ImGuiImplGl3();
    private final Events.UiUpdate updateEvent = new Events.UiUpdate();

    private boolean ready;
    private boolean failed;
    private boolean toggleDown;
    private boolean panicDown;
    private long frame;

    public NullUI(NullClient nc) {
        this.nc = nc;
        this.panels = List.of(
                new ProfilesPanel(nc),
                new RoutesPanel(nc),
                new MacrosPanel(nc),
                new AIPanel(nc),
                new LayoutsPanel(nc));
    }

    public List<Panel> panels() {
        return panels;
    }

    public boolean isMenuOpen() {
        return Minecraft.getInstance().screen instanceof NullScreen;
    }

    private boolean isTyping() {
        return ready && isMenuOpen() && ImGui.getIO().getWantTextInput();
    }

    /** RIGHT SHIFT toggles the menu; END is the panic key that stops every agent and macro. */
    public void pollHotkeys(Minecraft mc) {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            return;
        }
        boolean toggle = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (toggle && !toggleDown && !isTyping()) {
            if (mc.screen instanceof NullScreen) {
                mc.setScreen(null);
            } else if (mc.screen == null) {
                mc.setScreen(new NullScreen());
            }
        }
        toggleDown = toggle;

        boolean panic = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_END) == GLFW.GLFW_PRESS;
        if (panic && !panicDown) {
            nc.ai().cancelAll();
            nc.macros().stopPlayback();
            nc.macros().stopRecording(false);
        }
        panicDown = panic;
    }

    public void onMenuClosed() {
        if (ready) {
            nc.layouts().save(LayoutManager.LAST);
        }
    }

    private boolean ensureInit() {
        if (ready) {
            return true;
        }
        if (failed) {
            return false;
        }
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            return false;
        }
        try {
            ImGui.createContext();
            ImGuiIO io = ImGui.getIO();
            io.setIniFilename(null);
            io.setLogFilename(null);
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
            GlassTheme.apply();
            glfw.init(window, true);
            gl3.init("#version 150");
            ready = true;
            nc.layouts().load(LayoutManager.LAST);
            NullClient.LOG.info("ImGui initialised");
        } catch (Throwable t) {
            failed = true;
            NullClient.LOG.error("ImGui initialisation failed; UI disabled", t);
        }
        return ready;
    }

    /** Builds and renders one ImGui frame into the default framebuffer. */
    public void renderFrame() {
        if (!ensureInit()) {
            return;
        }
        boolean menu = isMenuOpen();
        gl3.newFrame();
        glfw.newFrame();
        ImGui.newFrame();

        updateEvent.menuOpen = menu;
        updateEvent.deltaTime = ImGui.getIO().getDeltaTime();
        updateEvent.frame = ++frame;
        nc.bus().post(updateEvent);

        if (menu) {
            drawMenuBar();
            for (Panel p : panels) {
                p.render();
            }
        }
        HudOverlay.render(nc);

        ImGui.render();
        int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        gl3.renderDrawData(ImGui.getDrawData());
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
    }

    private void drawMenuBar() {
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("NullClient")) {
                for (Panel p : panels) {
                    ImGui.menuItem(p.title, "", p.open);
                }
                ImGui.endMenu();
            }
            var active = nc.profiles().active();
            GlassTheme.colored(GlassTheme.CYAN, "  profile: " + (active == null ? "none" : active.name));
            if (nc.store().isProtected()) {
                ImGui.sameLine();
                GlassTheme.colored(nc.store().isLocked() ? GlassTheme.RED : GlassTheme.GREEN,
                        nc.store().isLocked() ? "  [locked]" : "  [encrypted]");
            }
            ImGui.sameLine();
            GlassTheme.colored(GlassTheme.MUTED, "  RSHIFT: menu   END: stop all");
            ImGui.endMainMenuBar();
        }
    }

    public void shutdown() {
        if (!ready) {
            return;
        }
        ready = false;
        gl3.dispose();
        glfw.dispose();
        ImGui.destroyContext();
    }
}