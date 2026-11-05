package dev.nullclient.ai;

import dev.nullclient.core.event.EventBus;
import dev.nullclient.core.event.Events;
import dev.nullclient.core.event.Subscribe;
import dev.nullclient.core.input.InputOverride;
import dev.nullclient.core.macro.MacroManager;
import dev.nullclient.core.session.Profile;
import dev.nullclient.core.session.ProfileManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns one agent per profile. Only the agent of the currently active profile ticks; switching profiles
 * suspends the previous agent and resumes the new one.
 */
public final class AIManager {
    private final ProfileManager profiles;
    private final MacroManager macros;
    private final EventBus bus;
    private final Map<String, AIAgent> agents = new LinkedHashMap<>();

    public AIManager(ProfileManager profiles, MacroManager macros, EventBus bus) {
        this.profiles = profiles;
        this.macros = macros;
        this.bus = bus;
    }

    public Collection<AIAgent> agents() {
        return Collections.unmodifiableCollection(new ArrayList<>(agents.values()));
    }

    public AIAgent agentOf(Profile p) {
        return agents.get(p.id);
    }

    public AIAgent activeAgent() {
        Profile a = profiles.active();
        if (a == null) {
            return null;
        }
        AIAgent agent = agents.get(a.id);
        return agent != null && agent.isLive() ? agent : null;
    }

    /** Takes control of {@code profile} with the given task, activating the profile if necessary. */
    public AIAgent assign(Profile profile, String[] task) {
        AIAgent agent = TaskFactory.create(profile, task);
        macros.stopPlayback();
        AIAgent old = agents.put(profile.id, agent);
        if (old != null && old.isLive()) {
            finish(old, AIAgent.State.CANCELLED, "replaced by new task");
        }
        profiles.setActive(profile);
        bus.post(new Events.AITaskState(agent, Events.AITaskState.Kind.STARTED, agent.label()));
        return agent;
    }

    public boolean cancel(Profile profile) {
        AIAgent a = agents.get(profile.id);
        if (a == null || !a.isLive()) {
            return false;
        }
        finish(a, AIAgent.State.CANCELLED, "cancelled");
        return true;
    }

    public void cancelAll() {
        for (AIAgent a : new ArrayList<>(agents.values())) {
            if (a.isLive()) {
                finish(a, AIAgent.State.CANCELLED, "cancelled");
            }
        }
    }

    @Subscribe
    public void onTick(Events.ClientTick e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            cancelAll();
            return;
        }
        AIAgent agent = activeAgent();
        if (agent == null) {
            InputOverride.release(AIAgent.OWNER);
            return;
        }
        if (agent.state() == AIAgent.State.SUSPENDED) {
            agent.setState(AIAgent.State.RUNNING, "");
            bus.post(new Events.AITaskState(agent, Events.AITaskState.Kind.RESUMED, agent.label()));
        }
        if (bus.post(new Events.AITick(agent, agent.ticks())).isCancelled()) {
            return;
        }
        AIAgent.State done = agent.tick(mc);
        if (done != null) {
            finish(agent, done, agent.message());
        }
    }

    @Subscribe
    public void onProfileSwitch(Events.ProfileSwitch e) {
        InputOverride.release(AIAgent.OWNER);
        Profile prev = e.previous();
        if (prev != null) {
            AIAgent a = agents.get(prev.id);
            if (a != null && a.state() == AIAgent.State.RUNNING) {
                a.setState(AIAgent.State.SUSPENDED, "profile deactivated");
                bus.post(new Events.AITaskState(a, Events.AITaskState.Kind.SUSPENDED, "profile deactivated"));
            }
        }
    }

    private void finish(AIAgent agent, AIAgent.State terminal, String message) {
        agent.setState(terminal, message);
        if (agents.get(agent.profile().id) == agent || agents.containsValue(agent)) {
            InputOverride.release(AIAgent.OWNER);
        }
        Events.AITaskState.Kind kind = switch (terminal) {
            case SUCCEEDED -> Events.AITaskState.Kind.SUCCEEDED;
            case FAILED -> Events.AITaskState.Kind.FAILED;
            default -> Events.AITaskState.Kind.CANCELLED;
        };
        bus.post(new Events.AITaskState(agent, kind, message));
    }
}