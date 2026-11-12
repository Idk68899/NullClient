package dev.nullclient.ui;

import dev.nullclient.NullClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Opens and closes the menu (a normal Minecraft screen, see {@link NullScreen}) and handles the hotkeys.
 * The old ImGui overlay is no longer used: nothing here touches ImGui or OpenGL.
 */
public final class NullUI {
    private final NullClient nc;
    private boolean toggleDown;
    private boolean panicDown;
    private volatile boolean pendingToggle;

    public NullUI(NullClient nc) {
        this.nc = nc;
    }

    /** Legacy: the ImGui panels are gone, so there is nothing to list. */
    public List<Panel> panels() {
        return List.of();
    }

    public String status() {
        return "vanilla screen (no ImGui)";
    }

    public boolean isMenuOpen() {
        return Minecraft.getInstance().gui.screen() instanceof NullScreen;
    }

    private boolean typing() {
        return Minecraft.getInstance().gui.screen() instanceof NullScreen s && s.typing();
    }

    /** Opens or closes the menu on the next tick (a chat screen must close first). */
    public void requestToggle() {
        pendingToggle = true;
    }

    private void toggleNow(Minecraft mc) {
        if (mc.gui.screen() instanceof NullScreen) {
            mc.gui.setScreen(null);
        } else if (mc.gui.screen() == null) {
            mc.gui.setScreen(new NullScreen());
        }
    }

    /** RIGHT SHIFT toggles the menu; END is the panic key that stops every agent and macro. */
    public void pollHotkeys(Minecraft mc) {
        if (pendingToggle && (mc.gui.screen() == null || mc.gui.screen() instanceof NullScreen)) {
            pendingToggle = false;
            toggleNow(mc);
        }
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            return;
        }
        boolean toggle = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (toggle && !toggleDown && !typing()) {
            toggleNow(mc);
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

    /** Kept so the mixins that used to draw the ImGui frame still compile; it does nothing now. */
    public void renderFrame() {
    }

    public void onMenuClosed() {
    }

    public void shutdown() {
    }
}