package net.singularitymc.sapi.event.server;

/** Start of a server tick (20 per second). Keep listeners cheap; prefer the scheduler for timed work. */
public record ServerTickEvent(long tick) {}
