package dev.nullclient.ai;

import dev.nullclient.core.event.Events;
import dev.nullclient.core.event.Priority;
import dev.nullclient.core.event.Subscribe;
import dev.nullclient.core.input.InputOverride;
import dev.nullclient.core.session.Profile;
import dev.nullclient.core.session.ProfileManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Chat interface. Plain requests work on the active profile: ".ai get me a diamond pickaxe".
 * A profile can still be named first: ".ai test get diamond_pickaxe". Messages starting with ".ai"
 * never reach the server.
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

    private void handle(String[] t) {
        if (t.length == 1 || t[1].equalsIgnoreCase("help")) {
            reply("just say what you want: .ai get me a diamond pickaxe", ChatFormatting.GRAY);
            reply("also: goto x y z | mine <block> [n] | wander | follow <player> | stop", ChatFormatting.GRAY);
            reply(".ai new <name> | profiles | list | stopall | debug", ChatFormatting.GRAY);
            return;
        }
        String first = t[1].toLowerCase(Locale.ROOT);
        switch (first) {
            case "debug" -> {
                debug();
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
            case "table" -> "crafting_table";
            case "diamonds" -> "diamond";
            case "stone" -> "cobblestone";
            default -> s;
        };
    }

    private void debug() {
        String mc = FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        reply("minecraft " + mc + ", NullClient loaded", ChatFormatting.GRAY);
        reply("menu hook: " + (InputOverride.uiHookRan ? "running" : "NOT running (menu cannot draw)"), InputOverride.uiHookRan ? ChatFormatting.GREEN : ChatFormatting.RED);
        reply("movement hook: " + (InputOverride.inputHookRan ? "running" : "NOT running (AI cannot move)"), InputOverride.inputHookRan ? ChatFormatting.GREEN : ChatFormatting.RED);
        Profile a = profiles.active();
        reply("active profile: " + (a == null ? "none" : a.name) + ", agents: " + ai.agents().size()
                + ", movement override held: " + InputOverride.active(), ChatFormatting.GRAY);
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