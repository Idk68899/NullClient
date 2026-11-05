package dev.nullclient.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Annotation-driven event bus.
 *
 * <p>Listener methods are compiled once at registration into hidden-class lambdas through
 * {@link LambdaMetafactory}, so dispatch is a plain interface call. Resolved listener arrays are cached
 * per concrete event class and invalidated on (un)registration; posting never allocates or locks.
 */
public final class EventBus {
    private static final Logger LOG = LoggerFactory.getLogger("NullClient/EventBus");
    private static final Listener[] NONE = new Listener[0];

    private final Object lock = new Object();
    private final Map<Object, List<Listener>> owners = new IdentityHashMap<>();
    private final ConcurrentHashMap<Class<?>, Listener[]> cache = new ConcurrentHashMap<>();

    public void register(Object owner) {
        synchronized (lock) {
            if (owners.containsKey(owner)) {
                return;
            }
            List<Listener> made = new ArrayList<>();
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Subscribe sub = m.getAnnotation(Subscribe.class);
                    if (sub == null) {
                        continue;
                    }
                    if (m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        throw new IllegalArgumentException("@Subscribe method must take exactly one Event parameter: " + m);
                    }
                    made.add(Listener.create(owner, m, sub));
                }
            }
            owners.put(owner, made);
            cache.clear();
        }
    }

    public void unregister(Object owner) {
        synchronized (lock) {
            if (owners.remove(owner) != null) {
                cache.clear();
            }
        }
    }

    public <E extends Event> E post(E event) {
        Class<?> type = event.getClass();
        Listener[] listeners = cache.get(type);
        if (listeners == null) {
            synchronized (lock) {
                listeners = resolve(type);
                cache.put(type, listeners);
            }
        }
        CancellableEvent cancellable = event instanceof CancellableEvent c ? c : null;
        for (Listener l : listeners) {
            if (cancellable != null && cancellable.isCancelled() && !l.receiveCancelled) {
                continue;
            }
            try {
                l.invoker.accept(l.owner, event);
            } catch (Throwable t) {
                LOG.error("Listener {} failed on {}", l.describe, type.getSimpleName(), t);
            }
        }
        return event;
    }

    private Listener[] resolve(Class<?> type) {
        List<Listener> out = new ArrayList<>();
        for (List<Listener> list : owners.values()) {
            for (Listener l : list) {
                if (l.type.isAssignableFrom(type)) {
                    out.add(l);
                }
            }
        }
        if (out.isEmpty()) {
            return NONE;
        }
        out.sort(Comparator.comparingInt(l -> l.priority.ordinal()));
        return out.toArray(NONE);
    }

    private static final class Listener {
        final Object owner;
        final Class<?> type;
        final Priority priority;
        final boolean receiveCancelled;
        final BiConsumer<Object, Object> invoker;
        final String describe;

        private Listener(Object owner, Method m, Subscribe sub, BiConsumer<Object, Object> invoker) {
            this.owner = owner;
            this.type = m.getParameterTypes()[0];
            this.priority = sub.priority();
            this.receiveCancelled = sub.receiveCancelled();
            this.invoker = invoker;
            this.describe = m.getDeclaringClass().getSimpleName() + "#" + m.getName();
        }

        @SuppressWarnings("unchecked")
        static Listener create(Object owner, Method m, Subscribe sub) {
            BiConsumer<Object, Object> invoker = null;
            if (!Modifier.isStatic(m.getModifiers())) {
                try {
                    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup());
                    MethodHandle impl = lookup.unreflect(m);
                    CallSite site = LambdaMetafactory.metafactory(
                            lookup,
                            "accept",
                            MethodType.methodType(BiConsumer.class),
                            MethodType.methodType(void.class, Object.class, Object.class),
                            impl,
                            MethodType.methodType(void.class, m.getDeclaringClass(), m.getParameterTypes()[0]));
                    invoker = (BiConsumer<Object, Object>) site.getTarget().invoke();
                } catch (Throwable t) {
                    LOG.warn("Falling back to reflective dispatch for {}: {}", m, t.toString());
                }
            }
            if (invoker == null) {
                m.setAccessible(true);
                invoker = (o, e) -> {
                    try {
                        m.invoke(o, e);
                    } catch (InvocationTargetException ex) {
                        throw new RuntimeException(ex.getCause());
                    } catch (IllegalAccessException ex) {
                        throw new RuntimeException(ex);
                    }
                };
            }
            return new Listener(owner, m, sub, invoker);
        }
    }
}