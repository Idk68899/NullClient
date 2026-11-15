package dev.nullclient.gui;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallbackI;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PC only: lets you drag a PNG or GIF file straight onto the game window to add it to config/nullclient/ui/.
 * Phone launchers have no equivalent (Android's file picker is not reachable from a JVM mod), so there this
 * stays silent and the Settings page points you at the folder instead.
 */
public final class DropImages {
    private static final AtomicReference<String> LAST = new AtomicReference<>("");
    private static boolean installed;

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
        GLFW.glfwSetDropCallback(window, (GLFWDropCallbackI) (w, count, names) -> {
            for (int i = 0; i < count; i++) {
                handle(org.lwjgl.system.MemoryUtil.memUTF8(org.lwjgl.system.MemoryUtil.memGetAddress(names + (long) i * Long.BYTES)));
            }
        });
    }

    private static void handle(String path) {
        try {
            Path src = Path.of(path);
            String lower = src.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".png") || lower.endsWith(".gif"))) {
                LAST.set("ignored " + src.getFileName() + " (only .png and .gif)");
                return;
            }
            Path dest = Pics.dir().resolve(src.getFileName());
            Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LAST.set("added " + src.getFileName());
        } catch (Exception e) {
            LAST.set("could not add that file: " + e.getMessage());
        }
    }
}