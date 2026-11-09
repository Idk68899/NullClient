package dev.nullclient;

import dev.nullclient.ai.AICommand;
import dev.nullclient.ai.AIManager;
import dev.nullclient.ai.AIMemory;
import dev.nullclient.ai.AiCommandTree;
import dev.nullclient.ai.world.WorldScanner;
import dev.nullclient.core.event.EventBus;
import dev.nullclient.core.event.Events;
import dev.nullclient.core.input.InputOverride;
import dev.nullclient.core.macro.MacroManager;
import dev.nullclient.core.session.ProfileManager;
import dev.nullclient.core.session.RouteManager;
import dev.nullclient.core.session.SessionStore;
import dev.nullclient.ui.LayoutManager;
import dev.nullclient.ui.NullUI;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class NullClient implements ClientModInitializer {
    public static final String MOD_ID = "nullclient";
    public static final Logger LOG = LoggerFactory.getLogger("NullClient");

    private static NullClient instance;

    private EventBus bus;
    private SessionStore store;
    private ProfileManager profiles;
    private RouteManager routes;
    private MacroManager macros;
    private AIManager ai;
    private LayoutManager layouts;
    private volatile NullUI ui;

    public static NullClient get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        Path root = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

        bus = new EventBus();
        store = new SessionStore(root);
        store.load();
        profiles = new ProfileManager(store, bus);
        routes = new RouteManager(store, profiles);
        macros = new MacroManager(root.resolve("macros"), bus);
        ai = new AIManager(profiles, macros, bus);
        layouts = new LayoutManager(root.resolve("layouts"));

        bus.register(macros);
        bus.register(ai);
        bus.register(new AICommand(ai, profiles));
        // The scanner stays idle until ".ai start" tells it what to index.
        bus.register(WorldScanner.INSTANCE);

        NullUI built = new NullUI(this);
        layouts.bindPanels(built::panels);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            built.pollHotkeys(mc);
            bus.post(Events.ClientTick.INSTANCE);
            InputOverride.drive(mc);
        });
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> !bus.post(new Events.ChatSend(message)).isCancelled());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> AiCommandTree.register(dispatcher));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> shutdown());

        instance = this;
        ui = built;
        LOG.info("NullClient initialised ({} profiles, state {})", profiles.all().size(), store.state());
    }

    private void shutdown() {
        ai.cancelAll();
        macros.shutdown();
        routes.shutdown();
        AIMemory.INSTANCE.save();
        store.save();
        if (ui != null) {
            ui.shutdown();
        }
    }

    public EventBus bus() {
        return bus;
    }

    public SessionStore store() {
        return store;
    }

    public ProfileManager profiles() {
        return profiles;
    }

    public RouteManager routes() {
        return routes;
    }

    public MacroManager macros() {
        return macros;
    }

    public AIManager ai() {
        return ai;
    }

    public LayoutManager layouts() {
        return layouts;
    }

    public NullUI ui() {
        return ui;
    }
}