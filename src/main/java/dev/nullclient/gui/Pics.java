package dev.nullclient.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Loads PNG and GIF files from config/nullclient/ui/ as textures the menu can draw. Must be called on the render thread. */
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

    private static final Map<String, Pic> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
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
}