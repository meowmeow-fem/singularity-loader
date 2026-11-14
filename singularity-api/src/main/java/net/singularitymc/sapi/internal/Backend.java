package net.singularitymc.sapi.internal;

import net.singularitymc.sapi.server.Server;

/** What the runtime needs from the game. The Minecraft side is {@code internal.mc.McBackend}; tests use a fake. */
public interface Backend {
    /** @throws IllegalStateException when no server is running */
    Server server();

    boolean isServerRunning();

    boolean isServerThread();
}
