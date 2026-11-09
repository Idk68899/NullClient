package dev.nullclient.ai.bt;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Typed shared state for one behavior tree. A key can have a default supplier that fills it in on first read. */
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
    private final Map<Key<?>, Supplier<?>> defaults = new HashMap<>();

    public <T> void setDefault(Key<T> key, Supplier<T> supplier) {
        defaults.put(key, supplier);
    }

    public <T> void set(Key<T> key, T value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    public <T> T get(Key<T> key) {
        Object v = data.get(key);
        if (v == null) {
            Supplier<?> d = defaults.get(key);
            if (d != null) {
                v = d.get();
                if (v != null) {
                    data.put(key, v);
                }
            }
        }
        return key.type.cast(v);
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