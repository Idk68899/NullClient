package dev.nullclient.core.session;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stores SOCKS5/HTTP route definitions and their per-profile assignment. Routes are data plus a
 * reachability tester; this module does not touch the game's network pipeline.
 */
public final class RouteManager {
    private final SessionStore store;
    private final ProfileManager profiles;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NullClient-RouteTest");
        t.setDaemon(true);
        return t;
    });

    public RouteManager(SessionStore store, ProfileManager profiles) {
        this.store = store;
        this.profiles = profiles;
    }

    public List<Route> all() {
        return Collections.unmodifiableList(store.data().routes);
    }

    public Route add(String name, Route.Type type, String host, int port, String user, String pass) {
        if (store.isLocked()) {
            throw new IllegalStateException("Store is locked");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be 1-65535");
        }
        Route r = new Route();
        r.name = name == null || name.isBlank() ? host.trim() + ":" + port : name.trim();
        r.type = type;
        r.host = host.trim();
        r.port = port;
        r.username = user == null ? "" : user;
        r.password = pass == null ? "" : pass;
        store.data().routes.add(r);
        store.save();
        return r;
    }

    public void remove(Route r) {
        for (Profile p : profiles.all()) {
            if (r.id.equals(p.routeId)) {
                p.routeId = null;
            }
        }
        store.data().routes.remove(r);
        store.save();
    }

    public void assign(Profile p, Route r) {
        profiles.assignRoute(p, r);
    }

    public Route activeRoute() {
        return profiles.routeOf(profiles.active());
    }

    public void test(Route r) {
        if (r.testing) {
            return;
        }
        r.testing = true;
        r.status = "testing...";
        executor.submit(() -> {
            RouteTester.Result res = RouteTester.run(r);
            r.latencyMs = res.ok() ? res.latencyMs() : -1;
            r.status = res.message();
            r.testing = false;
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}