package dev.nullclient.ai;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Keeps the most recent AI events in memory (for ".ai why") and appends them to config/nullclient/ai.log. */
public final class RunLog {
    private static final int KEEP = 80;
    private static final Deque<String> RECENT = new ArrayDeque<>();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static Path file;

    private RunLog() {
    }

    public static synchronized void add(String text) {
        String line = "[" + LocalTime.now().format(FMT) + "] " + text;
        RECENT.addLast(line);
        while (RECENT.size() > KEEP) {
            RECENT.removeFirst();
        }
        try {
            if (file == null) {
                Path dir = FabricLoader.getInstance().getConfigDir().resolve("nullclient");
                Files.createDirectories(dir);
                file = dir.resolve("ai.log");
            }
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never break the AI
        }
    }

    public static synchronized List<String> recent(int n) {
        List<String> all = new ArrayList<>(RECENT);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }
}