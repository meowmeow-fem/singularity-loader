package net.singularitymc.api.event;

/** Order in which listeners run: {@code LOWEST} first, {@code HIGHEST} last (so it has the last word), then {@code MONITOR}. */
public enum EventPriority {
    LOWEST, LOW, NORMAL, HIGH, HIGHEST,
    /** Runs last and also sees cancelled events. Observe only; do not change the event here. */
    MONITOR
}
