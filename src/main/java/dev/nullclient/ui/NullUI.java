package dev.nullclient.ui;

import dev.nullclient.NullClient;
import dev.nullclient.core.script.ScriptRunner;
import dev.nullclient.gui.AutoLogin;
import dev.nullclient.gui.Join;
import dev.nullclient.gui.MenuScreen;
import dev.nullclient.gui.Pov;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Opens and closes the menu ({@link MenuScreen}), finishes pending joins, runs auto login and scripts, and handles hotkeys. */
public final class NullUI {
    private final NullClient nc;
    private boolean toggleDown;
    private boolean panicDown;
    private volatile boolean pendingToggle;

    public NullUI(NullClient nc) {
        this.nc = nc;
        AutoLogin.register();
    }

    /** Legacy: the old ImGui panels are gone, so there is nothing to list. */
    public List<Panel> panels() {
        return List.of();
    }

    public String status() {
        return "custom drawn menu";
    }

    public boolean isMenuOpen() {
        return Minecraft.getInstance().gui.screen() instanceof MenuScreen;
    }

    /** Opens or closes the menu on the next tick (a chat screen must close first). */
    public void requestToggle() {
        pendingToggle = true;
    }

    private void toggleNow(Minecraft mc) {
        if (mc.gui.screen() instanceof MenuScreen) {
            mc.gui.setScreen(null);
        } else if (mc.gui.screen() == null) {
            mc.gui.setScreen(new MenuScreen());
        }
    }

    /** RIGHT SHIFT toggles the menu; END is the panic key that stops every agent, script and macro. */
    public void pollHotkeys(Minecraft mc) {
        Join.tick(mc);
        AutoLogin.tick(mc);
        Pov.tick(mc);
        ScriptRunner.tick(mc);
        if (pendingToggle && (mc.gui.screen() == null || mc.gui.screen() instanceof MenuScreen)) {
            pendingToggle = false;
            toggleNow(mc);
        }
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            return;
        }
        boolean toggle = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (toggle && !toggleDown && !MenuScreen.typing()) {
            toggleNow(mc);
        }
        toggleDown = toggle;

        boolean panic = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_END) == GLFW.GLFW_PRESS;
        if (panic && !panicDown) {
            ScriptRunner.stop();
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