package dev.nullclient.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saved server entries (a name and an address) for the Setup tab. Stored in config/nullclient/servers.json. */
final class Servers {
    static final class Entry {
        String name = "";
        String ip = "";
    }

    private static List<Entry> list;

    private Servers() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nullclient").resolve("servers.json");
    }

    static List<Entry> all() {
        if (list == null) {
            try {
                if (Files.exists(file())) {
                    list = new Gson().fromJson(Files.readString(file()), new TypeToken<List<Entry>>() {
                    }.getType());
                }
            } catch (IOException | RuntimeException ignored) {
                list = null;
            }
            if (list == null) {
                list = new ArrayList<>();
            }
        }
        return list;
    }

    static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new Gson().toJson(all()));
        } catch (IOException ignored) {
            // saved servers are a convenience; never break the game over them
        }
    }

    static Entry add(String name, String ip) {
        Entry e = new Entry();
        e.name = name;
        e.ip = ip;
        all().add(e);
        save();
        return e;
    }

    static void remove(Entry e) {
        all().remove(e);
        save();
    }
}