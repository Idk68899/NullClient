package dev.nullclient.ai.plan;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data-driven game knowledge: tags, gather sources (blocks or mobs), recipes, smelting, fuel. Loaded from knowledge.json,
 * extended by knowledge_extra.json and by recipes learned from the Minecraft Wiki (see {@link WikiKnowledge}).
 */
public final class Knowledge {
    public static final class Gather {
        public String item;
        public List<String> blocks = List.of();
        public List<String> mobs = List.of();
        public List<String> tool = List.of();
        public double chance = 1.0;
        public boolean scan;
    }

    public static final class Recipe {
        public String out;
        public int count = 1;
        public List<String> pattern = List.of();
        public Map<String, String> key = Map.of();
        public List<String> shapeless = List.of();

        /** 2 if it fits the inventory crafting grid, 3 if it needs a crafting table. */
        public int grid() {
            if (!shapeless.isEmpty()) {
                return shapeless.size() > 4 ? 3 : 2;
            }
            int w = 0;
            for (String row : pattern) {
                w = Math.max(w, row.length());
            }
            return Math.max(w, pattern.size()) > 2 ? 3 : 2;
        }

        public Map<String, Integer> ingredients() {
            Map<String, Integer> m = new LinkedHashMap<>();
            if (!shapeless.isEmpty()) {
                for (String t : shapeless) {
                    m.merge(t, 1, Integer::sum);
                }
            } else {
                for (String row : pattern) {
                    for (char c : row.toCharArray()) {
                        if (c != ' ') {
                            m.merge(key.get(String.valueOf(c)), 1, Integer::sum);
                        }
                    }
                }
            }
            return m;
        }
    }

    public static final class Smelt {
        public String out;
        public String in;
    }

    public static final class Fuel {
        public String item;
        public double burn = 1;
    }

    private Map<String, List<String>> tags = Map.of();
    private List<Gather> gather = List.of();
    private List<Recipe> recipes = List.of();
    private List<Smelt> smelt = List.of();
    private List<Fuel> fuel = List.of();

    private static Knowledge instance;

    private static Knowledge read(String resource, boolean required) {
        try (InputStream in = Knowledge.class.getResourceAsStream(resource)) {
            if (in == null) {
                if (required) {
                    throw new IllegalStateException(resource + " is missing");
                }
                return null;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new Gson().fromJson(r, Knowledge.class);
            }
        } catch (IOException | RuntimeException e) {
            if (required) {
                throw new IllegalStateException(resource + " unreadable", e);
            }
            return null;
        }
    }

    public static synchronized Knowledge get() {
        if (instance == null) {
            Knowledge k = read("/nullclient/knowledge.json", true);
            Knowledge extra = read("/nullclient/knowledge_extra.json", false);
            if (extra != null) {
                k.merge(extra);
            }
            WikiKnowledge.applyCache(k);
            instance = k;
        }
        return instance;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private void merge(Knowledge o) {
        recipes = concat(recipes, o.recipes);
        gather = concat(gather, o.gather);
        smelt = concat(smelt, o.smelt);
        fuel = concat(fuel, o.fuel);
        Map<String, List<String>> t = new LinkedHashMap<>(tags);
        t.putAll(o.tags);
        tags = t;
    }

    /** Adds a recipe learned at runtime (from the wiki cache or a live lookup). */
    public synchronized void addRecipe(Recipe r) {
        List<Recipe> n = new ArrayList<>(recipes);
        n.add(r);
        recipes = n;
    }

    public List<Fuel> fuel() {
        return fuel;
    }

    /** "#tag" expands to its members; anything else is itself. */
    public List<String> members(String token) {
        return token.startsWith("#") ? tags.getOrDefault(token.substring(1), List.of()) : List.of(token);
    }

    public Gather gatherFor(String item) {
        for (Gather g : gather) {
            if (g.item.equals(item) || members(g.item).contains(item)) {
                return g;
            }
        }
        return null;
    }

    public Recipe recipeFor(String item) {
        for (Recipe r : recipes) {
            if (r.out.equals(item) || members(r.out).contains(item)) {
                return r;
            }
        }
        return null;
    }

    public Smelt smeltFor(String item) {
        for (Smelt s : smelt) {
            if (s.out.equals(item) || members(s.out).contains(item)) {
                return s;
            }
        }
        return null;
    }

    /** True if the AI knows some way to obtain the item. */
    public boolean known(String item) {
        return gatherFor(item) != null || recipeFor(item) != null || smeltFor(item) != null;
    }

    /** Block ids the world scanner should index. */
    public Set<String> scanIds() {
        Set<String> ids = new HashSet<>(Set.of("crafting_table", "furnace"));
        for (Gather g : gather) {
            if (g.scan) {
                for (String b : g.blocks) {
                    ids.addAll(members(b));
                }
            }
        }
        return ids;
    }
}