package dev.nullclient.ai;

import dev.nullclient.core.event.Events;
import dev.nullclient.core.event.Priority;
import dev.nullclient.core.event.Subscribe;
import dev.nullclient.core.session.Profile;
import dev.nullclient.core.session.ProfileManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Chat interface: {@code .ai <profile_name> <task>}. Messages starting with ".ai" never reach the server. */
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

    private void handle(String[] t) {
        if (t.length == 1 || t[1].equalsIgnoreCase("help")) {
            reply("usage: .ai <profile> <task>   tasks: " + TaskFactory.USAGE, ChatFormatting.GRAY);
            reply(".ai list | .ai stopall", ChatFormatting.GRAY);
            return;
        }
        if (t[1].equalsIgnoreCase("list")) {
            if (ai.agents().isEmpty()) {
                reply("no agents", ChatFormatting.GRAY);
            }
            for (AIAgent a : ai.agents()) {
                reply(a.profile().name + ": " + a.label() + " [" + a.state() + "] " + a.message(), ChatFormatting.GRAY);
            }
            return;
        }
        if (t[1].equalsIgnoreCase("stopall")) {
            ai.cancelAll();
            reply("all agents cancelled", ChatFormatting.YELLOW);
            return;
        }
        if (profiles.store().isLocked()) {
            throw new IllegalStateException("profile store is locked; unlock it in the UI first");
        }
        if (t.length < 3) {
            throw new IllegalArgumentException("usage: .ai <profile> <task>   tasks: " + TaskFactory.USAGE);
        }
        Optional<Profile> profile = profiles.byName(t[1]);
        if (profile.isEmpty()) {
            throw new IllegalArgumentException("unknown profile '" + t[1] + "'");
        }
        String[] task = Arrays.copyOfRange(t, 2, t.length);
        if (task[0].equalsIgnoreCase("stop")) {
            reply(ai.cancel(profile.get()) ? "stopped " + profile.get().name : profile.get().name + " has no running task", ChatFormatting.YELLOW);
            return;
        }
        if (task[0].equalsIgnoreCase("plan")) {
            List<String> lines = TaskFactory.preview(Arrays.copyOfRange(task, 1, task.length));
            for (String l : lines) {
                reply(l, ChatFormatting.GRAY);
            }
            return;
        }
        AIAgent agent = ai.assign(profile.get(), task);
        reply(profile.get().name + " -> " + agent.label(), ChatFormatting.GREEN);
    }

    private static void reply(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.hud.getChat().addMessage(Component.literal("[NullAI] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(color)));
    }
}