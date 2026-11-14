package net.singularitymc.api.event;

/** Convenience base class for {@link Cancellable} events. */
public abstract class CancellableEvent implements Cancellable {
    private boolean cancelled;

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
