package net.singularitymc.api.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The one event bus of the server. Mods register listeners; the loader's patches post the events.
 *
 * <pre>{@code
 * EventBus.register(BlockBreakEvent.class, e -> {
 *     if (e.getState().is(Blocks.BEDROCK)) e.setCancelled(true);
 * });
 * }</pre>
 *
 * <p>A listener for a class also receives events of its subclasses. Cancelled events are not passed to listeners
 * (except {@link EventPriority#MONITOR}) unless they were registered with {@code receiveCancelled = true}.
 * An exception in a listener is logged and does not stop the other listeners or the server.
 *
 * <p>This class has no Minecraft types, so the loader and every mod share one instance. Events themselves
 * (package {@code net.singularitymc.event}) carry Minecraft objects and are loaded with the game.
 */
public final class EventBus {
    private static final System.Logger LOG = System.getLogger("Singularity");
    private static final Map<Class<?>, List<Entry<?>>> LISTENERS = new ConcurrentHashMap<>();

    private record Entry<E>(Class<E> type, EventPriority priority, boolean receiveCancelled, Consumer<? super E> listener) {}

    private EventBus() {}

    public static <E> void register(Class<E> type, Consumer<? super E> listener) {
        register(type, EventPriority.NORMAL, false, listener);
    }

    public static <E> void register(Class<E> type, EventPriority priority, Consumer<? super E> listener) {
        register(type, priority, false, listener);
    }

    public static <E> void register(Class<E> type, EventPriority priority, boolean receiveCancelled, Consumer<? super E> listener) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(priority);
        Objects.requireNonNull(listener);
        LISTENERS.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(new Entry<>(type, priority, receiveCancelled, listener));
    }

    /** Removes a listener that was registered with {@code register}. */
    public static void unregister(Consumer<?> listener) {
        LISTENERS.values().forEach(list -> list.removeIf(e -> e.listener() == listener));
    }

    /** Sends {@code event} to its listeners and returns it, so {@code post(new X()).isCancelled()} works. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <E> E post(E event) {
        List<Entry<?>> matching = new ArrayList<>();
        for (Map.Entry<Class<?>, List<Entry<?>>> e : LISTENERS.entrySet()) {
            if (e.getKey().isInstance(event)) {
                matching.addAll(e.getValue());
            }
        }
        if (matching.isEmpty()) {
            return event;
        }
        matching.sort(Comparator.comparing(Entry::priority));
        for (Entry entry : matching) {
            boolean cancelled = event instanceof Cancellable c && c.isCancelled();
            if (cancelled && !entry.receiveCancelled() && entry.priority() != EventPriority.MONITOR) {
                continue;
            }
            try {
                entry.listener().accept(event);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.ERROR, "A listener for " + entry.type().getSimpleName() + " failed", t);
            }
        }
        return event;
    }
}
