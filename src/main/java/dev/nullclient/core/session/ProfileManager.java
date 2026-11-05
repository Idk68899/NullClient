package dev.nullclient.core.session;

import dev.nullclient.core.event.EventBus;
import dev.nullclient.core.event.Events;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ProfileManager {
    private static final String[] ADJ = {"Silent", "Crimson", "Frost", "Neon", "Quantum", "Hollow", "Static", "Velvet", "Iron", "Lunar"};
    private static final String[] NOUN = {"Falcon", "Cipher", "Warden", "Specter", "Drifter", "Anvil", "Beacon", "Nomad", "Vortex", "Relic"};
    private static final SecureRandom RNG = new SecureRandom();

    private final SessionStore store;
    private final EventBus bus;

    public ProfileManager(SessionStore store, EventBus bus) {
        this.store = store;
        this.bus = bus;
    }

    public SessionStore store() {
        return store;
    }

    public List<Profile> all() {
        return Collections.unmodifiableList(store.data().profiles);
    }

    public Optional<Profile> byName(String name) {
        return all().stream().filter(p -> p.name.equalsIgnoreCase(name)).findFirst();
    }

    public Optional<Profile> byId(String id) {
        return id == null ? Optional.empty() : all().stream().filter(p -> p.id.equals(id)).findFirst();
    }

    public Profile active() {
        return byId(store.data().activeProfileId).orElse(null);
    }

    public static String sanitize(String raw) {
        String s = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    public Profile create(String rawName) {
        if (store.isLocked()) {
            throw new IllegalStateException("Store is locked");
        }
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name must not be empty");
        }
        if (byName(name).isPresent()) {
            throw new IllegalArgumentException("Profile '" + name + "' already exists");
        }
        Profile p = new Profile(name);
        store.data().profiles.add(p);
        store.save();
        return p;
    }

    public Profile generate() {
        String name;
        do {
            name = ADJ[RNG.nextInt(ADJ.length)] + "_" + NOUN[RNG.nextInt(NOUN.length)] + "_" + (10 + RNG.nextInt(90));
        } while (byName(name).isPresent());
        return create(name);
    }

    public void rename(Profile p, String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name must not be empty");
        }
        Optional<Profile> clash = byName(name);
        if (clash.isPresent() && clash.get() != p) {
            throw new IllegalArgumentException("Profile '" + name + "' already exists");
        }
        p.name = name;
        store.save();
    }

    public void setNote(Profile p, String note) {
        p.note = note == null ? "" : note;
        store.save();
    }

    public void delete(Profile p) {
        if (p == active()) {
            setActive(null);
        }
        store.data().profiles.remove(p);
        store.save();
    }

    public void setActive(Profile p) {
        Profile old = active();
        if (old == p) {
            return;
        }
        store.data().activeProfileId = p == null ? null : p.id;
        if (p != null) {
            p.lastUsedAt = System.currentTimeMillis();
        }
        store.save();
        bus.post(new Events.ProfileSwitch(old, p));
    }

    public void assignRoute(Profile p, Route r) {
        p.routeId = r == null ? null : r.id;
        store.save();
    }

    public Route routeOf(Profile p) {
        if (p == null || p.routeId == null) {
            return null;
        }
        return store.data().routes.stream().filter(r -> r.id.equals(p.routeId)).findFirst().orElse(null);
    }

    public static String describe(Profile p) {
        return p == null ? "none" : p.name.toLowerCase(Locale.ROOT);
    }
}