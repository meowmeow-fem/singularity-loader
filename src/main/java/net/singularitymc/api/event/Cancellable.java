package net.singularitymc.api.event;

/** An event that a listener can cancel; the action it describes then does not happen. */
public interface Cancellable {
    boolean isCancelled();

    void setCancelled(boolean cancelled);
}
