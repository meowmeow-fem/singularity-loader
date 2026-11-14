package net.singularitymc.sapi.event;

import java.util.function.Consumer;
import net.singularitymc.api.event.EventPriority;

/**
 * Listeners of one mod or plugin, on the server's one {@link net.singularitymc.api.event.EventBus}. The API events are
 * in this package's sub-packages ({@code player}, {@code block}, {@code entity}, {@code server}); mods can also listen
 * to the loader's events with Minecraft types ({@code net.singularitymc.event}). The loader's listeners for an action
 * run first, then the API event is posted; cancelling either cancels the action.
 *
 * <p>A listener that throws is logged with the owner's name; the others still run.
 */
public interface Events {
    default <E> Listener listen(Class<E> type, Consumer<? super E> listener) {
        return listen(type, EventPriority.NORMAL, false, listener);
    }

    default <E> Listener listen(Class<E> type, EventPriority priority, Consumer<? super E> listener) {
        return listen(type, priority, false, listener);
    }

    /** @param receiveCancelled also call it for events another listener cancelled */
    <E> Listener listen(Class<E> type, EventPriority priority, boolean receiveCancelled, Consumer<? super E> listener);

    /** Posts an event (also your own event classes) to every listener and returns it. */
    <E> E post(E event);

    /** Removes every listener of this owner. */
    void unregisterAll();

    /** A registered listener. */
    interface Listener {
        void unregister();
    }
}
