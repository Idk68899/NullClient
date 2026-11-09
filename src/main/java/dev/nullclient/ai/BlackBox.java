package dev.nullclient.ai;

import dev.nullclient.core.input.InputOverride;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rolling in-memory recording of what the AI was doing (position, camera, keys, target, route step).
 * It keeps the last ~60 seconds, and when something goes wrong it writes the last 30 seconds to
 * config/nullclient/blackbox.txt and adds a one-line summary to the run log. Deleted on every ".ai start".
 */
public final class BlackBox {
    private static final int CAP = 600;
    private static final int DUMP = 300;
    private static final Sample[] RING = new Sample[CAP];
    private static int head;
    private static int size;
    private static Path file;

    private record Sample(long tick, double x, double y, double z, float yaw, float pitch, int actions, String note) {
    }

    private BlackBox() {
    }

    private static Path file() {
        if (file == null) {
            file = FabricLoader.getInstance().getConfigDir().resolve("nullclient").resolve("blackbox.txt");
        }
        return file;
    }

    public static synchronized void sample(long tick, double x, double y, double z, float yaw, float pitch, int actions, String note) {
        RING[head] = new Sample(tick, x, y, z, yaw, pitch, actions, note);
        head = (head + 1) % CAP;
        if (size < CAP) {
            size++;
        }
    }

    public static synchronized void clear() {
        head = 0;
        size = 0;
        try {
            Files.deleteIfExists(file());
        } catch (IOException ignored) {
            // nothing to clean up
        }
    }

    private static List<Sample> last(int n) {
        int count = Math.min(n, size);
        List<Sample> out = new ArrayList<>(count);
        for (int i = count; i >= 1; i--) {
            out.add(RING[(head - i + CAP * 2) % CAP]);
        }
        return out;
    }

    private static String keys(int a) {
        StringBuilder sb = new StringBuilder();
        if (InputOverride.has(a, InputOverride.FORWARD)) sb.append('F');
        if (InputOverride.has(a, InputOverride.BACK)) sb.append('B');
        if (InputOverride.has(a, InputOverride.LEFT)) sb.append('L');
        if (InputOverride.has(a, InputOverride.RIGHT)) sb.append('R');
        if (InputOverride.has(a, InputOverride.JUMP)) sb.append('J');
        if (InputOverride.has(a, InputOverride.SNEAK)) sb.append('N');
        if (InputOverride.has(a, InputOverride.SPRINT)) sb.append('P');
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** Saves the recent history and returns (and logs) a one-line summary. Empty string if nothing was recorded. */
    public static synchronized String dump(String reason) {
        List<Sample> s = last(DUMP);
        if (s.size() < 2) {
            return "";
        }
        double pathLen = 0.0;
        double yawSum = 0.0;
        int forward = 0;
        for (int i = 1; i < s.size(); i++) {
            Sample a = s.get(i - 1);
            Sample b = s.get(i);
            pathLen += Math.hypot(b.x() - a.x(), b.z() - a.z());
            float dy = Math.abs(((b.yaw() - a.yaw()) % 360.0f + 540.0f) % 360.0f - 180.0f);
            yawSum += dy;
            if (InputOverride.has(b.actions(), InputOverride.FORWARD)) {
                forward++;
            }
        }
        Sample first = s.get(0);
        Sample lastS = s.get(s.size() - 1);
        double net = Math.hypot(lastS.x() - first.x(), lastS.z() - first.z());
        double seconds = (lastS.tick() - first.tick()) / 20.0;
        String text = String.format(Locale.ROOT,
                "blackbox (last %.0fs): walked %.1f blocks but ended %.1f from where it began, turned %.0f degrees, pressing forward %d%% of the time",
                seconds, pathLen, net, yawSum, Math.round(100.0 * forward / (s.size() - 1)));
        try {
            Files.createDirectories(file().getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("reason: ").append(reason).append(System.lineSeparator());
            sb.append(text).append(System.lineSeparator());
            sb.append("tick x y z yaw pitch keys note").append(System.lineSeparator());
            for (Sample x : s) {
                sb.append(String.format(Locale.ROOT, "%d %.2f %.2f %.2f %.0f %.0f %s %s",
                        x.tick(), x.x(), x.y(), x.z(), x.yaw(), x.pitch(), keys(x.actions()), x.note()))
                        .append(System.lineSeparator());
            }
            Files.writeString(file(), sb.toString());
        } catch (IOException ignored) {
            // the summary below still helps
        }
        RunLog.add(text + "  [" + reason + "]");
        return text;
    }
}