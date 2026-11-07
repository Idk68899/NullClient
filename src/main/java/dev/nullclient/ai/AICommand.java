package dev.nullclient.ai;

import dev.nullclient.NullClient;
import dev.nullclient.ai.world.BlockSpec;
import dev.nullclient.ai.world.WorldScanner;
import dev.nullclient.core.event.Events;
import dev.nullclient.core.event.Priority;
import dev.nullclient.core.event.Subscribe;
import dev.nullclient.core.input.InputOverride;
import dev.nullclient.core.session.Profile;
import dev.nullclient.core.session.ProfileManager;
import dev.nullclient.ui.NullUI;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Chat interface. Plain requests work on the active profile: ".ai get me a diamond pickaxe".
 * A profile can be named first: ".ai test get diamond_pickaxe" (created if it does not exist).
 * Messages starting with ".ai" never reach the server.
 */
public final class AICommand {
    private static final Set<String> DIRECT = Set.of("goto", "wander", "follow", "stop");
    private static final Set<String> ITEM_VERBS = Set.of("get", "plan", "mine", "make", "craft", "give", "find", "collect", "gather", "bring");
    private static final Set<String> FILLER = Set.of("me", "a", "an", "the", "some", "please", "pls", "i", "want", "need", "to", "can", "you", "of");

    private final AIManager ai;
    private final ProfileManager profiles;

    public AICommand(AIManager ai, ProfileManager profiles) {
        this.ai = ai;
        this.profiles = profiles;
    }

    @Subscribe(priority = Priority.HIGHEST)
    public void onChat(Events.ChatSend e) {
        String msg = e.message().trim();
        if (!(msg.equalsIgnoreCase(".ai") || msg.regionMatches(true, 0, ".ai ", 0, 4))) {
            return;
        }
        e.setCancelled(true);
        String[] t = msg.split("\\s+");
        try {
            handle(t);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            reply(ex.getMessage(), ChatFormatting.RED);
        }
    }

    /** Reports how a task ended, since there is no other feedback when the menu is closed. */
    @Subscribe
    public void onTaskState(Events.AITaskState e) {
        String who = e.agent().profile().name + ": ";
        switch (e.kind()) {
            case SUCCEEDED -> reply(who + "done - " + e.agent().label(), ChatFormatting.GREEN);
            case FAILED -> reply(who + "FAILED - " + e.message(), ChatFormatting.RED);
            case CANCELLED -> reply(who + "cancelled", ChatFormatting.YELLOW);
            default -> {
            }
        }
    }

    private static boolean isVerb(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        return DIRECT.contains(s) || ITEM_VERBS.contains(s);
    }

    private void handle(String[] t) {
        if (t.length == 1 || t[1].equalsIgnoreCase("help")) {
            reply("just say what you want: .ai get me a diamond pickaxe", ChatFormatting.GRAY);
            reply("also: goto x y z | mine <block> [n] | wander | follow <player> | stop", ChatFormatting.GRAY);
            reply(".ai menu | talk | new <name> | profiles | list | stopall | debug", ChatFormatting.GRAY);
            return;
        }
        String first = t[1].toLowerCase(Locale.ROOT);
        switch (first) {
            case "debug" -> {
                debug();
                return;
            }
            case "talk" -> {
                Say.verbose = !Say.verbose;
                reply("chat narration " + (Say.verbose ? "ON" : "OFF") + " (the action bar always shows live status)", ChatFormatting.GRAY);
                return;
            }
            case "menu" -> {
                NullUI ui = NullClient.get().ui();
                if (ui.status().startsWith("FAILED")) {
                    reply("menu unavailable: ImGui " + ui.status(), ChatFormatting.RED);
                    return;
                }
                ui.requestToggle();
                reply("toggling the menu (close it with ESC or NullClient > Close menu)", ChatFormatting.GRAY);
                return;
            }
            case "list" -> {
                if (ai.agents().isEmpty()) {
                    reply("no agents", ChatFormatting.GRAY);
                }
                for (AIAgent a : ai.agents()) {
                    reply(a.profile().name + ": " + a.label() + " [" + a.state() + "] " + a.message(), ChatFormatting.GRAY);
                }
                return;
            }
            case "stopall" -> {
                ai.cancelAll();
                reply("all agents cancelled", ChatFormatting.YELLOW);
                return;
            }
            default -> {
            }
        }
        if (profiles.store().isLocked()) {
            throw new IllegalStateException("profile store is locked; unlock it in the UI first");
        }
        if (first.equals("profiles")) {
            if (profiles.all().isEmpty()) {
                reply("no profiles yet; the first task creates one automatically", ChatFormatting.GRAY);
            }
            for (Profile p : profiles.all()) {
                reply(p.name + (p == profiles.active() ? "  (active)" : ""), ChatFormatting.GRAY);
            }
            return;
        }
        if (first.equals("new")) {
            if (t.length < 3) {
                throw new IllegalArgumentException("usage: .ai new <name>");
            }
            Profile created = profiles.create(t[2]);
            profiles.setActive(created);
            reply("created and activated profile '" + created.name + "'", ChatFormatting.GREEN);
            return;
        }

        Optional<Profile> named = profiles.byName(t[1]);
        if (named.isEmpty() && t.length >= 3 && isVerb(t[2]) && !isVerb(t[1])) {
            Profile created = profiles.create(t[1]);
            reply("created profile '" + created.name + "'", ChatFormatting.GREEN);
            named = Optional.of(created);
        }
        Profile profile;
        String[] words;
        if (named.isPresent()) {
            if (t.length < 3) {
                throw new IllegalArgumentException("tell " + named.get().name + " what to do, e.g. .ai " + named.get().name + " get a diamond pickaxe");
            }
            profile = named.get();
            words = Arrays.copyOfRange(t, 2, t.length);
        } else {
            profile = defaultProfile();
            words = Arrays.copyOfRange(t, 1, t.length);
        }
        String[] task = normalize(words);

        if (task[0].equals("stop")) {
            reply(ai.cancel(profile) ? "stopped " + profile.name : profile.name + " has no running task", ChatFormatting.YELLOW);
            return;
        }
        validate(task);
        if (task[0].equals("plan")) {
            for (String l : TaskFactory.preview(Arrays.copyOfRange(task, 1, task.length))) {
                reply(l, ChatFormatting.GRAY);
            }
            return;
        }
        AIAgent agent = ai.assign(profile, task);
        reply("on it: " + agent.label() + "  (profile " + profile.name + ", .ai stop to cancel)", ChatFormatting.GREEN);
    }

    private Profile defaultProfile() {
        Profile active = profiles.active();
        if (active != null) {
            return active;
        }
        if (!profiles.all().isEmpty()) {
            return profiles.all().get(0);
        }
        Profile created = profiles.create("Default");
        profiles.setActive(created);
        return created;
    }

    /** Rejects item or block names the game does not have, with "did you mean" suggestions. */
    private static void validate(String[] task) {
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

    /** Turns free text into [verb, args...] understood by TaskFactory. */
    static String[] normalize(String[] raw) {
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

    private void debug() {
        String mc = FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String build = FabricLoader.getInstance().getModContainer("nullclient")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        reply("NullClient build " + build + " on minecraft " + mc, ChatFormatting.GRAY);
        NullUI ui = NullClient.get().ui();
        reply("menu: " + ui.status() + ", frame hook " + (InputOverride.uiHookRan ? "running" : "NOT running"),
                InputOverride.uiHookRan && !ui.status().startsWith("FAILED") ? ChatFormatting.GREEN : ChatFormatting.RED);
        reply("movement: " + (InputOverride.velocityMode() ? "DIRECT (keys did not move you)" : "key presses")
                + (InputOverride.driving() ? ", driving now" : ", idle"), ChatFormatting.GRAY);
        Profile a = profiles.active();
        reply("active profile: " + (a == null ? "none" : a.name) + ", agents: " + ai.agents().size()
                + ", chat narration: " + (Say.verbose ? "on" : "off"), ChatFormatting.GRAY);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            BlockPos o = p.blockPosition();
            WorldScanner s = WorldScanner.INSTANCE;
            reply("scanner (96 blocks): logs " + s.find("log", o, 96, 5000).size()
                    + ", coal " + s.find("coal_ore", o, 96, 5000).size()
                    + ", iron " + s.find("iron_ore", o, 96, 5000).size()
                    + ", diamond " + s.find("diamond_ore", o, 96, 5000).size(), ChatFormatting.GRAY);
        }
        AIAgent running = ai.activeAgent();
        if (running != null) {
            reply("doing: " + running.activeNodes(), ChatFormatting.AQUA);
            reply("ticks: " + running.ticks() + ", note: " + (running.message().isEmpty() ? "-" : running.message()), ChatFormatting.GRAY);
        }
    }

    private static void reply(String text, ChatFormatting color) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        p.sendSystemMessage(Component.literal("[NullAI] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(color)));
    }
}