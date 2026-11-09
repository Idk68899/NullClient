package dev.nullclient.ai;

import dev.nullclient.ai.world.BlockSpec;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns free text into [verb, args...] and checks that named items and blocks exist. */
public final class AIParse {
    private static final Set<String> DIRECT = Set.of("goto", "wander", "follow", "stop");
    private static final Set<String> ITEM_VERBS = Set.of("get", "plan", "mine", "make", "craft", "give", "find", "collect", "gather", "bring");
    private static final Set<String> FILLER = Set.of("me", "a", "an", "the", "some", "please", "pls", "i", "want", "need", "to", "can", "you", "of");

    private AIParse() {
    }

    public static boolean isVerb(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        return DIRECT.contains(s) || ITEM_VERBS.contains(s);
    }

    /** Turns free text into [verb, args...] understood by TaskFactory. */
    public static String[] normalize(String[] raw) {
        List<String> w = new ArrayList<>();
        for (String s : raw) {
            w.add(s.toLowerCase(Locale.ROOT));
        }
        String head = w.get(0);
        if (DIRECT.contains(head)) {
            return w.toArray(String[]::new);
        }
        String verb = "get";
        if (ITEM_VERBS.contains(head)) {
            verb = head.equals("plan") ? "plan" : head.equals("mine") ? "mine" : "get";
            w.remove(0);
        }
        List<String> name = new ArrayList<>();
        int count = 1;
        for (String s : w) {
            if (FILLER.contains(s)) {
                continue;
            }
            String num = s.startsWith("x") ? s.substring(1) : s;
            if (num.matches("\\d{1,3}")) {
                count = Integer.parseInt(num);
                continue;
            }
            name.add(s);
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("what should I " + verb + "? e.g. .ai get a diamond pickaxe");
        }
        String item = alias(String.join("_", name).replace("minecraft:", ""));
        return count > 1 ? new String[]{verb, item, Integer.toString(count)} : new String[]{verb, item};
    }

    private static String alias(String item) {
        String s = item;
        if (s.endsWith("_pick")) {
            s = s + "axe";
        }
        for (String tool : List.of("pickaxe", "axe", "shovel", "hoe", "sword")) {
            if (s.equals("wood_" + tool)) {
                s = "wooden_" + tool;
            }
        }
        return switch (s) {
            case "wood", "log", "logs" -> "oak_log";
            case "iron" -> "iron_ingot";
            case "gold" -> "gold_ingot";
            case "table" -> "crafting_table";
            case "diamonds" -> "diamond";
            case "stone" -> "cobblestone";
            default -> s;
        };
    }

    /** Rejects item or block names the game does not have, with "did you mean" suggestions. */
    public static void validate(String[] task) {
        String verb = task[0];
        if (task.length < 2 || !(verb.equals("get") || verb.equals("plan") || verb.equals("mine"))) {
            return;
        }
        String name = task[1];
        boolean mine = verb.equals("mine");
        boolean ok = false;
        List<String> ids = new ArrayList<>();
        if (mine) {
            for (var key : BuiltInRegistries.BLOCK.keySet()) {
                String id = key.getPath();
                ids.add(id);
                if (BlockSpec.test(id, name)) {
                    ok = true;
                }
            }
        } else {
            for (var key : BuiltInRegistries.ITEM.keySet()) {
                String id = key.getPath();
                ids.add(id);
                if (id.equals(name)) {
                    ok = true;
                }
            }
        }
        if (!ok) {
            throw new IllegalArgumentException("unknown " + (mine ? "block" : "item") + " '" + name + "'" + hint(name, ids));
        }
    }

    private static String hint(String word, List<String> ids) {
        int tolerance = 1 + word.length() / 6;
        List<String> best = new ArrayList<>();
        ids.stream()
                .filter(id -> id.contains(word) || Arrays.stream(id.split("_")).anyMatch(seg -> levenshtein(seg, word) <= tolerance))
                .sorted(Comparator.comparingInt(String::length))
                .limit(3)
                .forEach(best::add);
        return best.isEmpty() ? "" : " - did you mean " + String.join(", ", best) + "?";
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}