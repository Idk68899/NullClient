package dev.nullclient.gui;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saved look of the menu: which picture goes in which slot, accent colour, panel opacity. */
public final class Theme {
    public static final int[] ACCENTS = {0xFF00EBFF, 0xFFFF38CC, 0xFF33FF99, 0xFFFFAA33, 0xFFAA66FF};
    public static final String[] ACCENT_NAMES = {"cyan", "magenta", "green", "amber", "violet"};
    private static final int[] ALPHAS = {0x66, 0x99, 0xCC};
    private static final String[] ALPHA_NAMES = {"glass", "medium", "solid"};

    public static final int TEXT = 0xFFEAF2FF;
    public static final int MUTED = 0xFF8C98B0;
    public static final int GOOD = 0xFF55FF99;
    public static final int BAD = 0xFFFF5566;
    public static final int WARN = 0xFFFFCC44;

    private static final class Data {
        String background = "";
        String sidebar = "";
        String panel = "";
        String button = "";
        int accent;
        int opacity = 1;
    }

    private static Data data;

    private Theme() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nullclient").resolve("ui.json");
    }

    private static Data d() {
        if (data == null) {
            try {
                if (Files.exists(file())) {
                    data = new Gson().fromJson(Files.readString(file()), Data.class);
                }
            } catch (IOException | RuntimeException ignored) {
                data = null;
            }
            if (data == null) {
                data = new Data();
            }
        }
        return data;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new Gson().toJson(d()));
        } catch (IOException ignored) {
            // the look is a convenience; never break the game over it
        }
    }

    public static String slot(String key) {
        Data x = d();
        return switch (key) {
            case "background" -> x.background;
            case "sidebar" -> x.sidebar;
            case "panel" -> x.panel;
            default -> x.button;
        };
    }

    public static void setSlot(String key, String value) {
        Data x = d();
        switch (key) {
            case "background" -> x.background = value;
            case "sidebar" -> x.sidebar = value;
            case "panel" -> x.panel = value;
            default -> x.button = value;
        }
        save();
    }

    public static int accent() {
        return ACCENTS[Math.floorMod(d().accent, ACCENTS.length)];
    }

    public static String accentName() {
        return ACCENT_NAMES[Math.floorMod(d().accent, ACCENTS.length)];
    }

    public static void nextAccent() {
        d().accent = (d().accent + 1) % ACCENTS.length;
        save();
    }

    public static int panel() {
        return (ALPHAS[Math.floorMod(d().opacity, ALPHAS.length)] << 24) | 0x0A0D16;
    }

    public static String opacityName() {
        return ALPHA_NAMES[Math.floorMod(d().opacity, ALPHAS.length)];
    }

    public static void nextOpacity() {
        d().opacity = (d().opacity + 1) % ALPHAS.length;
        save();
    }
}