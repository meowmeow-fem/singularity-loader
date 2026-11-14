package net.singularitymc.sapi.internal.mc;

import net.minecraft.server.MinecraftServer;
import net.singularitymc.sapi.internal.Backend;
import net.singularitymc.sapi.server.Server;

/** The running {@link MinecraftServer}, set by {@link ServerHooks}. */
public final class McBackend implements Backend {
    private volatile MinecraftServer server;
    private volatile ServerImpl impl;

    void attach(MinecraftServer server) {
        this.server = server;
        this.impl = new ServerImpl(server);
    }

    void detach() {
        this.impl = null;
        this.server = null;
    }

    MinecraftServer minecraft() {
        return server;
    }

    @Override
    public Server server() {
        ServerImpl i = impl;
        if (i == null) {
            throw new IllegalStateException("The server has not started yet (use onEnable or ServerStartedEvent)");
        }
        return i;
    }

    @Override
    public boolean isServerRunning() {
        return impl != null;
    }

    @Override
    public boolean isServerThread() {
        MinecraftServer s = server;
        return s != null && s.isSameThread();
    }
}
