package dev.nullclient.ai.bt;

import java.util.HashMap;
import java.util.Map;

/** Typed shared state for one behavior tree. */
public final class Blackboard {
    public static final class Key<T> {
        private final String name;
        private final Class<T> type;

        private Key(String name, Class<T> type) {
            this.name = name;
            this.type = type;
        }

        public static <T> Key<T> of(String name, Class<T> type) {
            return new Key<>(name, type);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Map<Key<?>, Object> data = new HashMap<>();

    public <T> void set(Key<T> key, T value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    public <T> T get(Key<T> key) {
        return key.type.cast(data.get(key));
    }

    public <T> T getOrDefault(Key<T> key, T fallback) {
        T v = get(key);
        return v == null ? fallback : v;
    }

    public boolean has(Key<?> key) {
        return data.containsKey(key);
    }

    public void clear() {
        data.clear();
    }
}