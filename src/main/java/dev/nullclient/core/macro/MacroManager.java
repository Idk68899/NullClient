package dev.nullclient.core.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.nullclient.core.event.EventBus;
import dev.nullclient.core.event.Events;
import dev.nullclient.core.event.Subscribe;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MacroManager {
    public static final String OWNER = "macro";

    private static final Logger LOG = LoggerFactory.getLogger("NullClient/Macro");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private final EventBus bus;
    private final Map<String, Macro> macros = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private Macro recording;
    private int recTick;
    private int lastActions;
    private float lastYaw;
    private float lastPitch;

    private Macro playing;
    private int playTick;
    private int playIdx;
    private boolean loop;

    public MacroManager(Path dir, EventBus bus) {
        this.dir = dir;
        this.bus = bus;
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
                for (Path p : ds) {
                    try {
                        Macro m = GSON.fromJson(Files.readString(p), Macro.class);
                        if (m != null && m.name != null && !m.name.isEmpty()) {
                            macros.put(m.name, m);
                        }
                    } catch (Exception e) {
                        LOG.warn("Skipping unreadable macro {}", p, e);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("Cannot initialise macro directory", e);
        }
    }

    public Collection<Macro> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(macros.values()));
    }

    public boolean isRecording() {
        return recording != null;
    }

    public boolean isPlaying() {
        return playing != null;
    }

    public int recordedTicks() {
        return recTick;
    }

    public String playingName() {
        return playing == null ? "" : playing.name;
    }

    public String recordingName() {
        return recording == null ? "" : recording.name;
    }

    public boolean startRecording(String rawName) {
        String name = rawName == null ? "" : rawName.trim().replaceAll("[^A-Za-z0-9_\\- ]", "_");
        if (name.isEmpty() || recording != null || playing != null) {
            return false;
        }
        Macro m = new Macro();
        m.name = name;
        recording = m;
        recTick = 0;
        lastActions = -1;
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        bus.post(new Events.MacroState(name, Events.MacroState.Kind.RECORD_START));
        return true;
    }

    public void stopRecording(boolean save) {
        Macro m = recording;
        if (m == null) {
            return;
        }
        recording = null;
        m.length = recTick;
        if (save && !m.frames.isEmpty()) {
            macros.put(m.name, m);
            write(m);
        }
        bus.post(new Events.MacroState(m.name, Events.MacroState.Kind.RECORD_STOP));
    }

    public boolean play(String name, boolean loop) {
        Macro m = macros.get(name);
        if (m == null || recording != null || m.frames.isEmpty()) {
            return false;
        }
        stopPlayback();
        this.playing = m;
        this.playTick = 0;
        this.playIdx = 0;
        this.loop = loop;
        bus.post(new Events.MacroState(m.name, Events.MacroState.Kind.PLAY_START));
        return true;
    }

    public void stopPlayback() {
        Macro m = playing;
        if (m == null) {
            return;
        }
        playing = null;
        InputOverride.release(OWNER);
        bus.post(new Events.MacroState(m.name, Events.MacroState.Kind.PLAY_STOP));
    }

    public void delete(String name) {
        Macro m = macros.remove(name);
        if (m != null) {
            try {
                Files.deleteIfExists(fileOf(m.name));
            } catch (IOException e) {
                LOG.warn("Could not delete macro file", e);
            }
        }
    }

    public void shutdown() {
        stopRecording(true);
        stopPlayback();
    }

    @Subscribe
    public void onTick(Events.ClientTick e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            stopRecording(true);
            stopPlayback();
            return;
        }
        if (recording != null) {
            int a = mc.screen == null ? readActions(mc) : 0;
            float yaw = p.getYRot();
            float pitch = p.getXRot();
            boolean first = recording.frames.isEmpty();
            if (first || a != lastActions || Math.abs(yaw - lastYaw) > 0.2f || Math.abs(pitch - lastPitch) > 0.2f) {
                recording.frames.add(new Macro.Frame(recTick, a, yaw, pitch));
                lastActions = a;
                lastYaw = yaw;
                lastPitch = pitch;
            }
            recTick++;
        }
        if (playing != null) {
            List<Macro.Frame> f = playing.frames;
            while (playIdx < f.size() && f.get(playIdx).tick <= playTick) {
                playIdx++;
            }
            if (playIdx > 0) {
                Macro.Frame cur = f.get(playIdx - 1);
                p.setYRot(cur.yaw);
                p.setXRot(cur.pitch);
                if (!InputOverride.set(OWNER, cur.actions)) {
                    stopPlayback();
                    return;
                }
            }
            playTick++;
            if (playTick > playing.length) {
                if (loop) {
                    playTick = 0;
                    playIdx = 0;
                } else {
                    stopPlayback();
                }
            }
        }
    }

    @Subscribe
    public void onProfileSwitch(Events.ProfileSwitch e) {
        stopPlayback();
    }

    private static int readActions(Minecraft mc) {
        int a = 0;
        if (mc.options.keyUp.isDown()) a |= InputOverride.FORWARD;
        if (mc.options.keyDown.isDown()) a |= InputOverride.BACK;
        if (mc.options.keyLeft.isDown()) a |= InputOverride.LEFT;
        if (mc.options.keyRight.isDown()) a |= InputOverride.RIGHT;
        if (mc.options.keyJump.isDown()) a |= InputOverride.JUMP;
        if (mc.options.keyShift.isDown()) a |= InputOverride.SNEAK;
        if (mc.options.keySprint.isDown()) a |= InputOverride.SPRINT;
        return a;
    }

    private Path fileOf(String name) {
        return dir.resolve(name.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".json");
    }

    private void write(Macro m) {
        try {
            Files.writeString(fileOf(m.name), GSON.toJson(m));
        } catch (IOException e) {
            LOG.error("Failed to save macro {}", m.name, e);
        }
    }
}