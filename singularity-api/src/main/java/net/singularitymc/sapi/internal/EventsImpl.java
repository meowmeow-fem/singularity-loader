package net.singularitymc.sapi.internal;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.api.event.EventPriority;
import net.singularitymc.sapi.Logger;
import net.singularitymc.sapi.event.Events;

/** The listeners of one owner on the loader's {@link EventBus}; removed together by {@link #unregisterAll()}. */
public final class EventsImpl implements Events {
    private final String owner;
    private final Logger logger;
    private final Set<Consumer<?>> registered = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public EventsImpl(String owner, Logger logger) {
        this.owner = owner;
        this.logger = logger;
    }

    @Override
    public <E> Listener listen(Class<E> type, EventPriority priority, boolean receiveCancelled, Consumer<? super E> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            throw new IllegalStateException(owner + " is disabled; it cannot register listeners");
        }
        Consumer<E> wrapped = event -> {
            try {
                listener.accept(event);
            } catch (Throwable t) {
                logger.error("A listener of " + owner + " for " + type.getSimpleName() + " failed", t);
            }
        };
        registered.add(wrapped);
        EventBus.register(type, priority, receiveCancelled, wrapped);
        return () -> {
            if (registered.remove(wrapped)) {
                EventBus.unregister(wrapped);
            }
        };
    }

    @Override
    public <E> E post(E event) {
        return EventBus.post(event);
    }

    @Override
    public void unregisterAll() {
        for (Consumer<?> c : registered) {
            EventBus.unregister(c);
        }
        registered.clear();
    }

    /** Unregisters everything and refuses new listeners. */
    public void close() {
        closed = true;
        unregisterAll();
    }

    public int size() {
        return registered.size();
    }
}
