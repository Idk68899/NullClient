package dev.nullclient.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Saves and restores GUI state: ImGui window geometry (its ini blob) plus per-panel visibility.
 * The layout named {@value #LAST} is written whenever the menu closes and restored at startup.
 */
public final class LayoutManager {
    public static final String LAST = "_last";

    private static final Logger LOG = LoggerFactory.getLogger("NullClient/Layout");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Layout {
        public String name = "";
        public String ini = "";
        public Map<String, Boolean> panels = new LinkedHashMap<>();
    }

    private final Path dir;
    private Supplier<List<Panel>> panelSource = List::of;

    public LayoutManager(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.error("Cannot create layout directory", e);
        }
    }

    public void bindPanels(Supplier<List<Panel>> source) {
        this.panelSource = source;
    }

    public List<String> list() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                n = n.substring(0, n.length() - 5);
                if (!n.equals(LAST)) {
                    names.add(n);
                }
            }
        } catch (IOException e) {
            LOG.warn("Cannot list layouts", e);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Requires a live ImGui context. */
    public boolean save(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            return false;
        }
        Layout l = new Layout();
        l.name = name;
        l.ini = ImGui.saveIniSettingsToMemory();
        for (Panel p : panelSource.get()) {
            l.panels.put(p.title, p.open.get());
        }
        try {
            Files.writeString(dir.resolve(name + ".json"), GSON.toJson(l));
            return true;
        } catch (IOException e) {
            LOG.error("Failed to save layout {}", name, e);
            return false;
        }
    }

    /** Requires a live ImGui context. */
    public boolean load(String rawName) {
        Path f = dir.resolve(sanitize(rawName) + ".json");
        if (!Files.exists(f)) {
            return false;
        }
        try {
            Layout l = GSON.fromJson(Files.readString(f), Layout.class);
            if (l == null) {
                return false;
            }
            if (l.ini != null && !l.ini.isEmpty()) {
                ImGui.loadIniSettingsFromMemory(l.ini);
            }
            for (Panel p : panelSource.get()) {
                Boolean v = l.panels.get(p.title);
                if (v != null) {
                    p.open.set(v);
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.error("Failed to load layout {}", rawName, e);
            return false;
        }
    }

    public void delete(String name) {
        try {
            Files.deleteIfExists(dir.resolve(sanitize(name) + ".json"));
        } catch (IOException e) {
            LOG.warn("Failed to delete layout {}", name, e);
        }
    }

    private static String sanitize(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}