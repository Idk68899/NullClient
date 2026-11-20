package dev.nullclient.ai.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.nullclient.NullClient;
import dev.nullclient.ai.RunLog;
import dev.nullclient.core.event.Events;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When an item is not in the knowledge files, looks up its crafting recipe on minecraft.wiki in the background, checks every
 * ingredient against the game's real item list, caches it in config/nullclient/wiki-cache.json, and retries the command.
 * Only crafting recipes are learned (not where blocks or mobs drop things). The wiki text is CC BY-NC-SA; this reads
 * recipe facts only and keeps them on your device. At most 8 requests per lookup, 1.2 s apart.
 */
public final class WikiKnowledge {
    private static final String API = "https://minecraft.wiki/api.php";
    private static final Set<String> TRIED = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> TAGS = Map.of(
            "planks", "#planks", "any planks", "#planks", "wood planks", "#planks", "any wood planks", "#planks",
            "log", "#logs", "logs", "#logs", "any log", "#logs", "any logs", "#logs");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private static volatile boolean busy;
    private static Set<String> itemIds;
    private static final List<Knowledge.Recipe> CACHE = new ArrayList<>();

    private WikiKnowledge() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("nullclient").resolve("wiki-cache.json");
    }

    private static synchronized Set<String> ids() {
        if (itemIds == null) {
            Set<String> s = new HashSet<>();
            for (var key : BuiltInRegistries.ITEM.keySet()) {
                s.add(key.getPath());
            }
            itemIds = s;
        }
        return itemIds;
    }

    /** Loads recipes learned in earlier sessions. Called once while the knowledge is being built. */
    static void applyCache(Knowledge k) {
        try {
            if (Files.exists(file())) {
                List<Knowledge.Recipe> list = new Gson().fromJson(Files.readString(file()), new TypeToken<List<Knowledge.Recipe>>() {
                }.getType());
                if (list != null) {
                    for (Knowledge.Recipe r : list) {
                        if (r != null && r.out != null && !k.known(r.out)) {
                            k.addRecipe(r);
                            CACHE.add(r);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // a damaged cache is just ignored
        }
    }

    private static synchronized void persist(Knowledge.Recipe r) {
        CACHE.add(r);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(CACHE));
        } catch (IOException ignored) {
            // the cache is a convenience
        }
    }

    /**
     * Starts a background lookup for the item (once per item per session). Returns true if one started; the command in
     * {@code retry} (for example "get iron_boots") is run again by itself when a recipe was found.
     */
    public static boolean learn(String item, String retry) {
        if (busy || !ids().contains(item) || !TRIED.add(item)) {
            return false;
        }
        busy = true;
        Thread t = new Thread(() -> run(item, retry), "NullClient-wiki");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p != null) {
            p.sendSystemMessage(Component.literal("[Wiki] " + text));
        }
    }

    private static void run(String item, String retry) {
        List<Knowledge.Recipe> found = new ArrayList<>();
        String error = "";
        try {
            Deque<String[]> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(new String[]{item, "0"});
            int calls = 0;
            while (!queue.isEmpty() && calls < 8) {
                String[] cur = queue.poll();
                if (!seen.add(cur[0]) || Knowledge.get().known(cur[0])) {
                    continue;
                }
                if (calls++ > 0) {
                    Thread.sleep(1200L);
                }
                Knowledge.Recipe r = fetch(cur[0]);
                if (r == null) {
                    continue;
                }
                found.add(r);
                int depth = Integer.parseInt(cur[1]);
                if (depth < 3) {
                    for (String ing : r.ingredients().keySet()) {
                        if (!ing.startsWith("#") && !Knowledge.get().known(ing)) {
                            queue.add(new String[]{ing, Integer.toString(depth + 1)});
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        String err = error;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            busy = false;
            boolean main = false;
            for (Knowledge.Recipe r : found) {
                Knowledge.get().addRecipe(r);
                persist(r);
                RunLog.add("learned from the wiki: " + r.out + " <- " + r.ingredients());
                if (r.out.equals(item)) {
                    main = true;
                }
            }
            if (main) {
                say("found a recipe for " + item + " (" + found.size() + " recipe(s) learned) - retrying");
                NullClient nc = NullClient.get();
                if (nc != null) {
                    nc.bus().post(new Events.ChatSend(".ai " + retry));
                }
            } else {
                say("could not find a usable recipe for " + item + (err.isEmpty() ? "" : " (" + err + ")")
                        + " - it may not be craftable, or the wiki page uses a layout I cannot read yet");
                RunLog.add("wiki lookup for " + item + " failed" + (err.isEmpty() ? "" : ": " + err));
            }
        });
    }

    private static String title(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static Knowledge.Recipe fetch(String id) throws IOException, InterruptedException {
        URI uri = URI.create(API + "?action=parse&prop=wikitext&format=json&formatversion=2&redirects=1&page="
                + URLEncoder.encode(title(id), StandardCharsets.UTF_8));
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "NullClient/1.0 (Minecraft mod; recipe lookup)").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("the wiki answered HTTP " + res.statusCode());
        }
        JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
        if (!o.has("parse")) {
            return null;
        }
        JsonElement w = o.getAsJsonObject("parse").get("wikitext");
        if (w == null) {
            return null;
        }
        String text = w.isJsonObject() ? w.getAsJsonObject().get("*").getAsString() : w.getAsString();
        return parse(text, id);
    }

    // ------------------------------------------------------------------ wikitext

    private static int closing(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length() - 1; i++) {
            if (s.startsWith("{{", i)) {
                depth++;
                i++;
            } else if (s.startsWith("}}", i)) {
                depth--;
                i++;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static List<String> split(String body) {
        List<String> parts = new ArrayList<>();
        int brace = 0;
        int link = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (body.startsWith("{{", i)) {
                brace++;
            } else if (body.startsWith("}}", i)) {
                brace--;
            } else if (body.startsWith("[[", i)) {
                link++;
            } else if (body.startsWith("]]", i)) {
                link--;
            }
            if (c == '|' && brace == 0 && link == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    private static String plain(String s) {
        String t = s.replaceAll("\\{\\{[^{}]*}}", "").replaceAll("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)]]", "$1")
                .replaceAll("<[^>]*>", "").replace("'''", "").replace("''", "");
        return t.trim();
    }

    private static String toId(String name) {
        String n = plain(name).toLowerCase(Locale.ROOT);
        if (n.isEmpty()) {
            return null;
        }
        String tag = TAGS.get(n);
        if (tag != null) {
            return tag;
        }
        String id = n.replace("'", "").replace(' ', '_').replace('-', '_');
        return ids().contains(id) ? id : null;
    }

    private static String param(Map<String, String> params, String... names) {
        for (String n : names) {
            String v = params.get(n);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Knowledge.Recipe parse(String text, String id) {
        String lower = text.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = lower.indexOf("{{crafting", from);
            if (i < 0) {
                return null;
            }
            int end = closing(text, i);
            if (end < 0) {
                return null;
            }
            List<String> parts = split(text.substring(i + 2, end - 2));
            Map<String, String> params = new LinkedHashMap<>();
            for (int k = 1; k < parts.size(); k++) {
                int eq = parts.get(k).indexOf('=');
                if (eq > 0) {
                    params.put(parts.get(k).substring(0, eq).trim().toLowerCase(Locale.ROOT), parts.get(k).substring(eq + 1).trim());
                }
            }
            Knowledge.Recipe r = build(params, id);
            if (r != null) {
                return r;
            }
            from = end;
        }
    }

    private static Knowledge.Recipe build(Map<String, String> params, String id) {
        String out = param(params, "output", "result");
        if (out == null) {
            return null;
        }
        int count = 1;
        String outName = out;
        int sep = Math.max(out.lastIndexOf(','), out.lastIndexOf(';'));
        if (sep > 0) {
            try {
                count = Integer.parseInt(out.substring(sep + 1).trim());
                outName = out.substring(0, sep);
            } catch (NumberFormatException ignored) {
                // not a count
            }
        }
        String amount = param(params, "amount", "count", "outputcount");
        if (amount != null) {
            try {
                count = Integer.parseInt(amount.trim());
            } catch (NumberFormatException ignored) {
                // keep the count found above
            }
        }
        String outId = toId(outName);
        if (outId == null || !outId.equals(id) || count < 1 || count > 64) {
            return null;
        }
        String[][] grid = new String[3][3];
        int minR = 9;
        int maxR = -1;
        int minC = 9;
        int maxC = -1;
        int n = 0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                String v = params.get(("" + (char) ('a' + c)) + (r + 1));
                if (v == null || plain(v).isEmpty()) {
                    continue;
                }
                String token = toId(v);
                if (token == null) {
                    return null;
                }
                grid[r][c] = token;
                n++;
                minR = Math.min(minR, r);
                maxR = Math.max(maxR, r);
                minC = Math.min(minC, c);
                maxC = Math.max(maxC, c);
            }
        }
        if (n == 0) {
            return null;
        }
        Knowledge.Recipe rec = new Knowledge.Recipe();
        rec.out = outId;
        rec.count = count;
        String sh = params.get("shapeless");
        boolean shapeless = sh != null && !sh.isBlank() && !sh.trim().equals("0") && !sh.trim().equalsIgnoreCase("no") && !sh.trim().equalsIgnoreCase("false");
        List<String> flat = new ArrayList<>();
        Map<String, String> letters = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        Map<String, String> key = new LinkedHashMap<>();
        for (int r = minR; r <= maxR; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = minC; c <= maxC; c++) {
                String t = grid[r][c];
                if (t == null) {
                    row.append(' ');
                    continue;
                }
                flat.add(t);
                String letter = letters.get(t);
                if (letter == null) {
                    letter = Character.toString((char) ('A' + letters.size()));
                    letters.put(t, letter);
                    key.put(letter, t);
                }
                row.append(letter);
            }
            rows.add(row.toString());
        }
        if (shapeless) {
            rec.shapeless = flat;
        } else {
            rec.pattern = rows;
            rec.key = key;
        }
        return rec;
    }
}