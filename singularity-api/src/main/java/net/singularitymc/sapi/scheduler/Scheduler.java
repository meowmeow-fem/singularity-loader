package net.singularitymc.sapi.scheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Runs code later on the server thread (counted in ticks, 20 per second) or on a background thread. Tasks of a plugin
 * are cancelled when it is disabled. A task that throws is logged; a repeating task keeps running.
 */
public interface Scheduler {
    /** On the server thread, at the start of the next tick. */
    Task runTask(Runnable task);

    /** On the server thread, after {@code delayTicks} ticks (0 = next tick). */
    Task runLater(long delayTicks, Runnable task);

    /** On the server thread, first after {@code delayTicks}, then every {@code periodTicks} (at least 1). */
    Task runTimer(long delayTicks, long periodTicks, Runnable task);

    /** On a background thread, now. Do not touch worlds or players there; hand results back with {@link #callSync}. */
    Task runAsync(Runnable task);

    /** Runs {@code call} on the server thread (next tick) and completes the future with its result. */
    <T> CompletableFuture<T> callSync(Callable<T> call);

    /** Cancels every task of this owner. */
    void cancelAll();
}
