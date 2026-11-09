package dev.nullclient.ai;

import dev.nullclient.NullClient;
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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chat interface. ".ai start" turns the engine on; after that plain requests work on the active profile:
 * ".ai get me a diamond pickaxe". A profile can be named first: ".ai test get diamond_pickaxe".
 * Messages starting with ".ai" never reach the server.
 */
public final class AICommand {
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
        RunLog.add(who + e.kind() + " " + e.agent().label() + (e.message().isEmpty() ? "" : " - " + e.message()));
        switch (e.kind()) {
            case SUCCEEDED -> {
                AIMemory.INSTANCE.save();
                reply(who + "done - " + e.agent().label(), ChatFormatting.GREEN);
            }
            case FAILED -> {
                AIMemory.INSTANCE.save();
                reply(who + "FAILED - " + e.message() + "  (.ai why for details)", ChatFormatting.RED);
            }
            case CANCELLED -> {
                AIMemory.INSTANCE.save();
                reply(who + "cancelled", ChatFormatting.YELLOW);
            }
            default -> {
            }
        }
    }

    private static ClientLevel world() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            throw new IllegalStateException("not in a world");
        }
        return mc.level;
    }

    private void handle(String[] t) {
        if (t.length == 1 || t[1].equalsIgnoreCase("help")) {
            reply("first: .ai start      then just say what you want: .ai get me a diamond pickaxe", ChatFormatting.GRAY);
            reply("also: goto x y z | mine <block> [n] | wander | follow <player> | stop | home | back", ChatFormatting.GRAY);
            reply(".ai sethome | where | forget | why | talk | menu | list | stopall | debug | off", ChatFormatting.GRAY);
            return;
        }
        String first = t[1].toLowerCase(Locale.ROOT);
        switch (first) {
            case "start" -> {
                start();
                return;
            }
            case "off" -> {
                ai.cancelAll();
                AIRuntime.stop();
                reply("AI off - the scanner is stopped and nothing runs in the background. .ai start to turn it on again", ChatFormatting.YELLOW);
                return;
            }
            case "debug" -> {
                debug();
                return;
            }
            case "why" -> {
                List<String> lines = RunLog.recent(10);
                if (lines.isEmpty()) {
                    reply("nothing logged yet - run a task first", ChatFormatting.GRAY);
                }
                for (String l : lines) {
                    reply(l, ChatFormatting.GRAY);
                }
                reply("full log: config/nullclient/ai.log", ChatFormatting.DARK_GRAY);
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
            case "stopall" -> {
                ai.cancelAll();
                reply("all agents cancelled", ChatFormatting.YELLOW);
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
            default -> {
            }
        }

        if (!AIRuntime.started()) {
            throw new IllegalStateException("the AI is off - type .ai start first");
        }
        switch (first) {
            case "sethome" -> {
                ClientLevel lv = world();
                BlockPos pos = Minecraft.getInstance().player.blockPosition();
                AIMemory.INSTANCE.setHome(lv, pos);
                reply("home set at " + pos.toShortString() + "  (.ai home to come back here)", ChatFormatting.GREEN);
                return;
            }
            case "home", "back" -> {
                goRemembered(first);
                return;
            }
            case "where" -> {
                ClientLevel lv = world();
                BlockPos h = AIMemory.INSTANCE.home(lv);
                BlockPos b = AIMemory.INSTANCE.back(lv);
                reply("home: " + (h == null ? "not set" : h.toShortString()) + "   last task started at: " + (b == null ? "-" : b.toShortString()), ChatFormatting.GRAY);
                reply(AIMemory.INSTANCE.summary(lv), ChatFormatting.GRAY);
                return;
            }
            case "forget" -> {
                AIMemory.INSTANCE.forget(world());
                reply("memory cleared for this dimension", ChatFormatting.YELLOW);
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
        if (named.isEmpty() && t.length >= 3 && AIParse.isVerb(t[2]) && !AIParse.isVerb(t[1])) {
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
        String[] task = AIParse.normalize(words);

        if (task[0].equals("stop")) {
            reply(ai.cancel(profile) ? "stopped " + profile.name : profile.name + " has no running task", ChatFormatting.YELLOW);
            return;
        }
        AIParse.validate(task);
        if (task[0].equals("plan")) {
            for (String l : TaskFactory.preview(Arrays.copyOfRange(task, 1, task.length))) {
                reply(l, ChatFormatting.GRAY);
            }
            return;
        }
        remember();
        AIAgent agent = ai.assign(profile, task);
        reply("on it: " + agent.label() + "  (profile " + profile.name + ", .ai stop to cancel)", ChatFormatting.GREEN);
    }

    /** Notes where this task starts (for ".ai back") and forgets spots given up on during earlier tasks. */
    private static void remember() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            AIMemory.INSTANCE.setBack(mc.level, mc.player.blockPosition());
            AIMemory.INSTANCE.clearBad(mc.level);
        }
    }

    private void goRemembered(String kind) {
        ClientLevel lv = world();
        LocalPlayer p = Minecraft.getInstance().player;
        BlockPos target = kind.equals("home") ? AIMemory.INSTANCE.home(lv) : AIMemory.INSTANCE.back(lv);
        if (target == null) {
            throw new IllegalArgumentException(kind.equals("home")
                    ? "no home set - stand where you want it and type .ai sethome"
                    : "nothing to go back to yet - start a task first");
        }
        if (profiles.store().isLocked()) {
            throw new IllegalStateException("profile store is locked; unlock it in the UI first");
        }
        Profile profile = defaultProfile();
        AIMemory.INSTANCE.setBack(lv, p.blockPosition());
        AIAgent agent = ai.assign(profile, new String[]{"goto", Integer.toString(target.getX()),
                Integer.toString(target.getY()), Integer.toString(target.getZ())});
        reply("heading " + kind + " to " + target.toShortString() + " (" + agent.label() + ")", ChatFormatting.GREEN);
    }

    private void start() {
        if (AIRuntime.started()) {
            reply("the AI is already running (.ai off to shut it down)", ChatFormatting.GRAY);
            return;
        }
        reply("starting the AI...", ChatFormatting.GRAY);
        for (String line : AIRuntime.start()) {
            reply("  " + line, ChatFormatting.GRAY);
        }
        reply("ready. try: .ai get me a wooden pickaxe      (.ai off when you are done)", ChatFormatting.GREEN);
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

    private void debug() {
        String mc = FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String build = FabricLoader.getInstance().getModContainer("nullclient")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        reply("NullClient build " + build + " on minecraft " + mc, ChatFormatting.GRAY);
        reply("AI engine: " + (AIRuntime.started() ? "ON" : "OFF (type .ai start)"),
                AIRuntime.started() ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        NullUI ui = NullClient.get().ui();
        reply("menu: " + ui.status() + ", frame hook " + (InputOverride.uiHookRan ? "running" : "NOT running"),
                InputOverride.uiHookRan && !ui.status().startsWith("FAILED") ? ChatFormatting.GREEN : ChatFormatting.RED);
        reply("movement: " + (InputOverride.velocityMode() ? "DIRECT (keys did not move you)" : "key presses")
                + (InputOverride.driving() ? ", driving now" : ", idle"), ChatFormatting.GRAY);
        Profile a = profiles.active();
        reply("active profile: " + (a == null ? "none" : a.name) + ", agents: " + ai.agents().size()
                + ", chat narration: " + (Say.verbose ? "on" : "off"), ChatFormatting.GRAY);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && AIRuntime.started()) {
            BlockPos o = p.blockPosition();
            WorldScanner s = WorldScanner.INSTANCE;
            reply("scanner (96 blocks, " + (s.settled() ? "scan complete" : "still scanning") + "): logs " + s.find("log", o, 96, 5000).size()
                    + ", coal " + s.find("coal_ore", o, 96, 5000).size()
                    + ", iron " + s.find("iron_ore", o, 96, 5000).size()
                    + ", diamond " + s.find("diamond_ore", o, 96, 5000).size(), ChatFormatting.GRAY);
            reply(AIMemory.INSTANCE.summary(Minecraft.getInstance().level), ChatFormatting.GRAY);
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