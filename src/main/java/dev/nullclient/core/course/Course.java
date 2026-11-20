package dev.nullclient.core.course;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A parkour course: an ordered list of checkpoints. Saved in config/nullclient/courses.json. */
public final class Course {
    public String name = "";
    public List<Pt> points = new ArrayList<>();

    public static final class Pt {
        public int x;
        public int y;
        public int z;

        public Pt() {
        }

        public Pt(BlockPos p) {
            x = p.getX();
            y = p.getY();
            z = p.getZ();
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public List<BlockPos> positions() {
        List<BlockPos> out = new ArrayList<>();
        for (Pt p : points) {
            out.add(p.pos());
        }
        return out;
    }

    private static List<Course> list;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nullclient").resolve("courses.json");
    }

    public static synchronized List<Course> all() {
        if (list == null) {
            try {
                if (Files.exists(file())) {
                    list = new Gson().fromJson(Files.readString(file()), new TypeToken<List<Course>>() {
                    }.getType());
                }
            } catch (IOException | RuntimeException ignored) {
                list = null;
            }
            if (list == null) {
                list = new ArrayList<>();
            }
            for (Course c : list) {
                if (c.points == null) {
                    c.points = new ArrayList<>();
                }
                if (c.name == null) {
                    c.name = "course";
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
            // courses are a convenience; never break the game over them
        }
    }

    public static synchronized Course find(String name) {
        for (Course c : all()) {
            if (c.name.equalsIgnoreCase(name.trim())) {
                return c;
            }
        }
        return null;
    }

    public static synchronized Course add(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("give the course a name");
        }
        if (find(n) != null) {
            throw new IllegalArgumentException("a course called '" + n + "' already exists");
        }
        Course c = new Course();
        c.name = n;
        all().add(c);
        save();
        return c;
    }

    public static synchronized void remove(Course c) {
        all().remove(c);
        save();
    }
}