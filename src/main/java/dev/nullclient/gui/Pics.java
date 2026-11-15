package dev.nullclient.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Loads PNG and GIF files from config/nullclient/ui/ as textures the menu can draw, and can download them from a
 * web address. Texture loading must happen on the render thread; downloading runs in the background.
 */
public final class Pics {
    /** One picture: a single frame, or the frames of a GIF with their delays. */
    public record Pic(Identifier[] ids, int[] delay, int w, int h, int total) {
        Identifier at(long now) {
            if (ids.length == 1 || total <= 0) {
                return ids[0];
            }
            long t = now % total;
            for (int i = 0; i < ids.length; i++) {
                t -= delay[i];
                if (t < 0) {
                    return ids[i];
                }
            }
            return ids[ids.length - 1];
        }
    }

    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final Map<String, Pic> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final AtomicReference<String> STATUS = new AtomicReference<>("");
    private static String error = "";
    private static int counter;

    private Pics() {
    }

    public static Path dir() {
        Path d = FabricLoader.getInstance().getConfigDir().resolve("nullclient").resolve("ui");
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
            // listing will just come back empty
        }
        return d;
    }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir())) {
            s.map(p -> p.getFileName().toString()).filter(n -> {
                String l = n.toLowerCase(Locale.ROOT);
                return l.endsWith(".png") || l.endsWith(".gif");
            }).sorted(String.CASE_INSENSITIVE_ORDER).forEach(out::add);
        } catch (IOException ignored) {
            // empty list
        }
        return out;
    }

    public static String error() {
        return error;
    }

    /** Progress or result of the last download. */
    public static String status() {
        return STATUS.get();
    }

    /** The picture for a file name, or null if blank, missing, or it failed to load (see {@link #error()}). */
    public static Pic get(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Pic cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(name)) {
            return null;
        }
        try {
            Pic p = name.toLowerCase(Locale.ROOT).endsWith(".gif") ? loadGif(name) : loadPng(name);
            CACHE.put(name, p);
            error = "";
            return p;
        } catch (Throwable t) {
            FAILED.add(name);
            error = name + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            return null;
        }
    }

    public static void reload() {
        Minecraft mc = Minecraft.getInstance();
        for (Pic p : CACHE.values()) {
            for (Identifier id : p.ids()) {
                mc.getTextureManager().release(id);
            }
        }
        CACHE.clear();
        FAILED.clear();
        error = "";
    }

    private static String safe(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static Identifier register(String name, int index, NativeImage img) {
        Identifier id = Identifier.fromNamespaceAndPath("nullclient", "ui/" + safe(name) + "_" + index + "_" + (counter++));
        DynamicTexture tex = new DynamicTexture(() -> "nullclient ui " + name, img);
        tex.upload();
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return id;
    }

    private static Pic loadPng(String name) throws IOException {
        NativeImage img = NativeImage.read(Files.readAllBytes(dir().resolve(name)));
        int w = img.getWidth();
        int h = img.getHeight();
        return new Pic(new Identifier[]{register(name, 0, img)}, new int[]{100}, w, h, 0);
    }

    private static Pic loadGif(String name) throws IOException {
        GifFrames.Result r = GifFrames.read(dir().resolve(name));
        int n = r.pngs().size();
        Identifier[] ids = new Identifier[n];
        int[] delay = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            ids[i] = register(name, i, NativeImage.read(r.pngs().get(i)));
            delay[i] = r.delaysMs().get(i);
            total += delay[i];
        }
        return new Pic(ids, delay, r.w(), r.h(), total);
    }

    // ------------------------------------------------------------------ download

    /** "png", "gif", or null if the bytes are neither. */
    private static String kind(byte[] d) {
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if (d.length > 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "gif";
        }
        return null;
    }

    /**
     * Downloads a PNG or GIF from a web address into the ui folder and assigns it to the slot.
     * Runs in the background; progress and result show up in {@link #status()}.
     */
    public static void download(String address, String slotKey) {
        if (address == null || address.isBlank()) {
            STATUS.set("paste an image link first");
            return;
        }
        STATUS.set("downloading...");
        Thread t = new Thread(() -> {
            try {
                URI uri = URI.create(address.trim());
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                    throw new IOException("the link must start with http:// or https://");
                }
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "NullClient").GET().build();
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) {
                    throw new IOException("the site answered " + res.statusCode());
                }
                byte[] data;
                try (InputStream in = res.body()) {
                    data = in.readNBytes(MAX_BYTES + 1);
                }
                if (data.length > MAX_BYTES) {
                    throw new IOException("the file is bigger than 6 MB");
                }
                String ext = kind(data);
                if (ext == null) {
                    throw new IOException("that link is not a PNG or GIF image (use a direct link to the file)");
                }
                String base = uri.getPath() == null ? "" : uri.getPath();
                base = base.substring(base.lastIndexOf('/') + 1);
                int dot = base.lastIndexOf('.');
                if (dot > 0) {
                    base = base.substring(0, dot);
                }
                base = safe(base);
                if (base.isEmpty()) {
                    base = "image";
                }
                String file = base + "." + ext;
                if (Files.exists(dir().resolve(file))) {
                    file = base + "-" + (System.currentTimeMillis() % 100000) + "." + ext;
                }
                Files.write(dir().resolve(file), data);
                String saved = file;
                Minecraft.getInstance().execute(() -> Theme.setSlot(slotKey, saved));
                STATUS.set("saved " + saved + " and set it for " + slotKey);
            } catch (Throwable e) {
                STATUS.set("download failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }, "NullClient-download");
        t.setDaemon(true);
        t.start();
    }
}