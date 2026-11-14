package net.singularitymc.sapi.event.player;

import net.singularitymc.sapi.entity.Player;

/** A player has finished joining. */
public record PlayerJoinEvent(Player player) {}
