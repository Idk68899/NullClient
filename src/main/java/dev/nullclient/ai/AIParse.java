package dev.nullclient.ai;

import dev.nullclient.ai.world.BlockSpec;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns free text into [verb, args...] and checks that named items and blocks exist. Understands basic commands like "get down". */
public final class AIParse {
    private static final Set<String> DIRECT = Set.of("goto", "wander", "follow", "stop", "down", "up", "course", "armor");
    private static final Set<String> ITEM_VERBS = Set.of("get", "plan", "mine", "make", "craft", "give", "find", "collect", "gather", "bring");
    private static final Set<String> FILLER = Set.of("me", "a", "an", "the", "some", "please", "pls", "i", "want", "need", "to", "can", "you", "of");

    private static final Set<String> NOISE = Set.of("please", "pls", "just", "can", "could", "would", "you", "kindly", "now", "me", "the",
            "this", "that", "from", "here", "to", "of", "off", "a", "an", "and", "then", "it", "there", "are", "is", "i", "want", "need");
    private static final Set<String> MOVES = Set.of("get", "go", "come", "climb", "jump", "walk", "move", "head", "run", "take",
            "tower", "building", "hill", "mountain", "cliff", "platform", "roof", "wall", "tree", "island", "pillar", "cave", "underground", "put");
    private static final Set<String> DOWN = Set.of("down", "descend", "downstairs");
    private static final Set<String> UP = Set.of("up", "out", "outside", "surface", "exit", "upstairs");
    private static final Set<String> STAY = Set.of("stay", "wait", "stand", "halt", "freeze", "still");

    private AIParse() {
    }

    public static boolean isVerb(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        return DIRECT.contains(s) || ITEM_VERBS.contains(s);
    }

    /** "get down", "go up", "stay" and similar: returns the real task, or null if the words are about something else. */
    private static String[] basic(List<String> words) {
        List<String> w = new ArrayList<>();
        for (String s : words) {
            if (!NOISE.contains(s)) {
                w.add(s);
            }
        }
        if (w.isEmpty()) {
            return null;
        }
        boolean down = false;
        boolean up = false;
        boolean stay = false;
        for (String s : w) {
            if (DOWN.contains(s)) {
                down = true;
            } else if (UP.contains(s)) {
                up = true;
            } else if (STAY.contains(s)) {
                stay = true;
            } else if (!MOVES.contains(s)) {
                return null;
            }
        }
        if (down && !up && !stay) {
            return new String[]{"down"};
        }
        if (up && !down && !stay) {
            return new String[]{"up"};
        }
        if (stay && !down && !up) {
            return new String[]{"stop"};
        }
        return null;
    }

    /** "full set of iron armor" -> [armor, iron]. */
    private static String[] armor(List<String> w) {
        boolean armor = false;
        for (String s : w) {
            if (s.equals("armor") || s.equals("armour") || s.endsWith("_armor") || s.endsWith("_armour")) {
                armor = true;
            }
        }
        if (!armor) {
            return null;
        }
        String mat = "iron";
        for (String s : w) {
            if (s.contains("netherite")) {
                throw new IllegalArgumentException("netherite armor needs a smithing table upgrade, which I cannot do yet - try diamond");
            } else if (s.contains("chain")) {
                throw new IllegalArgumentException("chainmail armor cannot be crafted in survival - try iron");
            } else if (s.contains("leather")) {
                mat = "leather";
            } else if (s.contains("gold")) {
                mat = "golden";
            } else if (s.contains("diamond")) {
                mat = "diamond";
            } else if (s.contains("iron")) {
                mat = "iron";
            }
        }
        return new String[]{"armor", mat};
    }

    /** Turns free text into [verb, args...] understood by TaskFactory. */
    public static String[] normalize(String[] raw) {
        List<String> w = new ArrayList<>();
        for (String s : raw) {
            w.add(s.toLowerCase(Locale.ROOT));
        }
        String[] basic = basic(w);
        if (basic != null) {
            return basic;
        }
        String head = w.get(0);
        if (DIRECT.contains(head) && !head.equals("armor")) {
            return w.toArray(String[]::new);
        }
        String[] armor = armor(w);
        if (armor != null) {
            return armor;
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
            if (s.equals("gold_" + tool)) {
                s = "golden_" + tool;
            }
        }
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            if (s.equals("gold_" + piece)) {
                s = "golden_" + piece;
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