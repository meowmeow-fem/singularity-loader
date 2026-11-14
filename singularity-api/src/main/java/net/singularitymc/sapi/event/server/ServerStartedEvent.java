package net.singularitymc.sapi.event.server;

import net.singularitymc.sapi.server.Server;

/** The server has loaded its worlds and is about to tick (right after the plugins were enabled). */
public record ServerStartedEvent(Server server) {}
