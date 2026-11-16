package dev.nullclient.gui;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PC only: drag a PNG or GIF file onto the game window while the menu is open and it is copied into
 * config/nullclient/ui/. Every drop is also passed on to the game's own handler. Phone launchers have no
 * equivalent (Android's file picker is not reachable from a JVM mod), so there the Settings window points you at
 * the folder instead.
 */
public final class DropImages {
    private static final long MAX_BYTES = 6L * 1024L * 1024L;
    private static final AtomicReference<String> LAST = new AtomicReference<>("");
    private static boolean installed;
    private static GLFWDropCallback previous;

    private DropImages() {
    }

    public static String last() {
        return LAST.get();
    }

    public static void install() {
        if (installed) {
            return;
        }
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            return;
        }
        installed = true;
        previous = GLFW.glfwSetDropCallback(window, (w, count, names) -> {
            if (Minecraft.getInstance().gui.screen() instanceof MenuScreen) {
                for (int i = 0; i < count; i++) {
                    handle(GLFWDropCallback.getName(names, i));
                }
            }
            if (previous != null) {
                previous.invoke(w, count, names);
            }
        });
    }

    private static void handle(String path) {
        try {
            Path src = Path.of(path);
            String file = src.getFileName().toString();
            String lower = file.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".png") || lower.endsWith(".gif"))) {
                LAST.set("ignored " + file + " (only .png and .gif)");
                return;
            }
            if (Files.size(src) > MAX_BYTES) {
                LAST.set("ignored " + file + " (bigger than 6 MB)");
                return;
            }
            Files.copy(src, Pics.dir().resolve(file), StandardCopyOption.REPLACE_EXISTING);
            LAST.set("added " + file + " - tap Reload images, then Next");
        } catch (IOException | RuntimeException e) {
            LAST.set("could not add that file: " + e.getMessage());
        }
    }
}