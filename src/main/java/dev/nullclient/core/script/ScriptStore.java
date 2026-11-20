package dev.nullclient.core.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saved scripts, in config/nullclient/scripts.json. */
public final class ScriptStore {
    private static List<Script> list;

    private ScriptStore() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nullclient").resolve("scripts.json");
    }

    public static synchronized List<Script> all() {
        if (list == null) {
            try {
                if (Files.exists(file())) {
                    list = new Gson().fromJson(Files.readString(file()), new TypeToken<List<Script>>() {
                    }.getType());
                }
            } catch (IOException | RuntimeException ignored) {
                list = null;
            }
            if (list == null) {
                list = new ArrayList<>();
            }
            for (Script s : list) {
                if (s.steps == null) {
                    s.steps = new ArrayList<>();
                }
                if (s.name == null) {
                    s.name = "script";
                }
            }
        }
        return list;
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(all()));
        } catch (IOException ignored) {
            // scripts are a convenience; never break the game over them
        }
    }

    public static synchronized Script add(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("give the script a name");
        }
        for (Script s : all()) {
            if (s.name.equalsIgnoreCase(n)) {
                throw new IllegalArgumentException("a script called '" + n + "' already exists");
            }
        }
        Script s = new Script();
        s.name = n;
        all().add(s);
        save();
        return s;
    }

    public static synchronized void remove(Script s) {
        all().remove(s);
        save();
    }
}