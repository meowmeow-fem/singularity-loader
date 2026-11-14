package net.singularitymc.sapi.event.player;

import net.singularitymc.sapi.entity.Player;

/** A player is leaving (still online and in the world while listeners run). */
public record PlayerQuitEvent(Player player) {}
