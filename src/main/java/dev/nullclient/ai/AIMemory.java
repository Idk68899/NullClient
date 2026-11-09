package dev.nullclient.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.nullclient.ai.nav.WorldView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What the AI remembers: a home, where the last task started, areas it already searched, and spots it could not
 * reach. Stored per dimension in config/nullclient/memory.json. Spots it gave up on expire after a few minutes.
 * Note: it cannot tell two singleplayer worlds apart, so use ".ai forget" when you switch worlds.
 */
public final class AIMemory {
    public static final AIMemory INSTANCE = new AIMemory();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long BAD_TTL_MS = 5L * 60L * 1000L;
    private static final int MAX_EXPLORED = 200;
    private static final int MAX_BAD = 300;
    private static final double SAME_WORLD_LIMIT = 3000.0;
    private static final double EXPLORED_RADIUS = 32.0;

    public static final class Dim {
        public int[] home;
        public int[] back;
        public int[] last;
        public List<Long> explored = new ArrayList<>();
        public Map<Long, Long> bad = new HashMap<>();
    }

    private static final class Data {
        Map<String, Dim> dims = new HashMap<>();
    }

    private Data data = new Data();
    private boolean loaded;
    private Path file;

    private AIMemory() {
    }

    private Path file() {
        if (file == null) {
            file = FabricLoader.getInstance().getConfigDir().resolve("nullclient").resolve("memory.json");
        }
        return file;
    }

    private static String key(ClientLevel level) {
        return level.dimension().toString();
    }

    private Dim dim(ClientLevel level) {
        return data.dims.computeIfAbsent(key(level), k -> new Dim());
    }

    /** Reads the file (first call only) and returns a one-line summary for the current dimension. */
    public synchronized String load(ClientLevel level, BlockPos pos) {
        if (!loaded) {
            try {
                if (Files.exists(file())) {
                    Data d = GSON.fromJson(Files.readString(file()), Data.class);
                    if (d != null && d.dims != null) {
                        data = d;
                    }
                }
            } catch (IOException | RuntimeException e) {
                data = new Data();
            }
            loaded = true;
        }
        Dim d = dim(level);
        String note = "";
        if (d.last != null) {
            double dx = pos.getX() - d.last[0];
            double dz = pos.getZ() - d.last[2];
            if (Math.hypot(dx, dz) > SAME_WORLD_LIMIT) {
                data.dims.put(key(level), new Dim());
                d = dim(level);
                note = " (looks like a different world - started fresh)";
            }
        }
        d.last = new int[]{pos.getX(), pos.getY(), pos.getZ()};
        save();
        return summary(level) + note;
    }

    public synchronized void save() {
        if (!loaded) {
            return;
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(data));
        } catch (IOException ignored) {
            // memory is a convenience; never break the game over it
        }
    }

    public synchronized String summary(ClientLevel level) {
        Dim d = dim(level);
        long now = System.currentTimeMillis();
        long live = d.bad.values().stream().filter(t -> now - t <= BAD_TTL_MS).count();
        return "memory: home " + (d.home == null ? "not set" : d.home[0] + " " + d.home[1] + " " + d.home[2])
                + ", " + d.explored.size() + " searched areas, " + live + " ignored spots";
    }

    public synchronized void setHome(ClientLevel level, BlockPos p) {
        Dim d = dim(level);
        d.home = new int[]{p.getX(), p.getY(), p.getZ()};
        d.last = d.home.clone();
        save();
    }

    public synchronized BlockPos home(ClientLevel level) {
        int[] h = dim(level).home;
        return h == null ? null : new BlockPos(h[0], h[1], h[2]);
    }

    public synchronized void setBack(ClientLevel level, BlockPos p) {
        Dim d = dim(level);
        d.back = new int[]{p.getX(), p.getY(), p.getZ()};
        d.last = d.back.clone();
    }

    public synchronized BlockPos back(ClientLevel level) {
        int[] b = dim(level).back;
        return b == null ? null : new BlockPos(b[0], b[1], b[2]);
    }

    public synchronized void markExplored(ClientLevel level, BlockPos p) {
        Dim d = dim(level);
        d.explored.add(p.asLong());
        while (d.explored.size() > MAX_EXPLORED) {
            d.explored.remove(0);
        }
    }

    public synchronized boolean nearExplored(ClientLevel level, BlockPos p) {
        for (long l : dim(level).explored) {
            BlockPos e = BlockPos.of(l);
            if (Math.hypot(e.getX() - p.getX(), e.getZ() - p.getZ()) < EXPLORED_RADIUS) {
                return true;
            }
        }
        return false;
    }

    public synchronized void rememberBad(ClientLevel level, Collection<Long> positions) {
        Dim d = dim(level);
        long now = System.currentTimeMillis();
        for (Long pos : positions) {
            d.bad.putIfAbsent(pos, now);
        }
        if (d.bad.size() > MAX_BAD) {
            Iterator<Map.Entry<Long, Long>> it = d.bad.entrySet().iterator();
            while (it.hasNext() && d.bad.size() > MAX_BAD) {
                it.next();
                it.remove();
            }
        }
    }

    /** Spots remembered as unreachable that have not expired yet. */
    public synchronized AIKeys.BadSet liveBad(ClientLevel level) {
        Dim d = dim(level);
        long now = System.currentTimeMillis();
        AIKeys.BadSet out = new AIKeys.BadSet();
        Iterator<Map.Entry<Long, Long>> it = d.bad.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (now - e.getValue() > BAD_TTL_MS) {
                it.remove();
            } else {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public synchronized void clearBad(ClientLevel level) {
        dim(level).bad.clear();
    }

    public synchronized void forget(ClientLevel level) {
        data.dims.remove(key(level));
        save();
    }

    /** A place to explore that is not near anywhere already searched, or null if none was found. */
    public BlockPos pickExploreGoal(ClientLevel level, LocalPlayer p) {
        WorldView view = new WorldView(level);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        BlockPos o = p.blockPosition();
        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = rnd.nextDouble(0.0, Math.PI * 2.0);
            double dist = 56.0 - (attempt % 10) * 3.0;
            int x = o.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = o.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            for (int y = o.getY() + 12; y >= o.getY() - 24; y--) {
                if (view.canStand(x, y, z)) {
                    BlockPos cand = new BlockPos(x, y, z);
                    if (!nearExplored(level, cand)) {
                        return cand;
                    }
                    break;
                }
            }
        }
        return null;
    }
}