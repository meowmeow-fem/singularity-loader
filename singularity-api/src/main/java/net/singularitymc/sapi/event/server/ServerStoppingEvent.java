package net.singularitymc.sapi.event.server;

import net.singularitymc.sapi.server.Server;

/** The server is stopping; worlds and players are still there (posted right before the plugins are disabled). */
public record ServerStoppingEvent(Server server) {}
