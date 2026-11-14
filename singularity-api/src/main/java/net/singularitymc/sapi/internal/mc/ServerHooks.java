package net.singularitymc.sapi.internal.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.sapi.event.player.PlayerChatEvent;
import net.singularitymc.sapi.event.server.ServerStartedEvent;
import net.singularitymc.sapi.event.server.ServerStoppingEvent;
import net.singularitymc.sapi.event.server.ServerTickEvent;
import net.singularitymc.sapi.internal.ApiRuntime;

/** Called by the API mod's Mixins ({@code internal.mixin}); Mixin classes themselves cannot be referenced. */
public final class ServerHooks {
    private static volatile ApiRuntime runtime;
    private static volatile McBackend backend;
    private static volatile boolean stopped;

    private ServerHooks() {}

    static void init(ApiRuntime r, McBackend b) {
        runtime = r;
        backend = b;
    }

    /** {@code DedicatedServer.initServer} returned {@code true}: worlds are loaded, the first tick is next. */
    public static void started(MinecraftServer server) {
        ApiRuntime r = runtime;
        if (r == null) {
            return;
        }
        backend.attach(server);
        BrigadierBridge.sync(r, server);
        r.pluginManager().enableAll();
        BrigadierBridge.sync(r, server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(p);
        }
        EventBus.post(new ServerStartedEvent(r.server()));
    }

    /** Start of {@code MinecraftServer.tickServer}. */
    public static void tick() {
        ApiRuntime r = runtime;
        if (r == null || !r.isServerRunning()) {
            return;
        }
        r.scheduler().tick();
        EventBus.post(new ServerTickEvent(r.scheduler().currentTick()));
    }

    /** Start of {@code MinecraftServer.stopServer}. */
    public static void stopping() {
        ApiRuntime r = runtime;
        if (r == null || stopped) {
            return;
        }
        stopped = true;
        if (r.isServerRunning()) {
            EventBus.post(new ServerStoppingEvent(r.server()));
        }
        r.shutdown();
        backend.detach();
    }

    /** {@code ServerGamePacketListenerImpl.broadcastChatMessage}: whether to drop the message. */
    public static boolean chat(ServerPlayer player, String message) {
        ApiRuntime r = runtime;
        if (r == null || !r.isServerRunning()) {
            return false;
        }
        return EventBus.post(new PlayerChatEvent(PlayerImpl.of(player), message)).isCancelled();
    }
}
