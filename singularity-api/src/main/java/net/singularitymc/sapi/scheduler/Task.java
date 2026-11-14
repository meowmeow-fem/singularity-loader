package net.singularitymc.sapi.scheduler;

/** A scheduled task. */
public interface Task {
    /** Stops it; a run that has started finishes. */
    void cancel();

    boolean isCancelled();

    boolean isRepeating();

    /** Id of the mod or plugin that scheduled it. */
    String owner();
}
