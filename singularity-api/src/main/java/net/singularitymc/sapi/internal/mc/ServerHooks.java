package net.singularitymc.sapi.internal.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.api.event.EventBus;
import net.minecraft.world.entity.Entity;
import net.singularitymc.sapi.event.player.PlayerAttackEvent;
import net.singularitymc.sapi.event.player.PlayerChatEvent;
import net.singularitymc.sapi.event.player.PlayerMoveEvent;
import net.singularitymc.sapi.world.Location;
import net.singularitymc.sapi.world.World;
import net.singularitymc.sapi.event.server.ServerStartedEvent;
import net.singularitymc.sapi.event.server.ServerStoppingEvent;
import net.singularitymc.sapi.event.server.ServerTickEvent;
import net.singularitymc.sapi.internal.ApiRuntime;
import net.singularitymc.sapi.internal.Logins;

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

    /** Whether the caller is on the server thread of a running server (packets first arrive on a network thread). */
    public static boolean onServerThread(ServerPlayer player) {
        ApiRuntime r = runtime;
        return r != null && r.isServerRunning() && player.level().getServer().isSameThread();
    }

    /**
     * End of {@code ServerGamePacketListenerImpl.handleMovePlayer} on the server thread; the arguments are where the
     * player was before the packet. A cancelled event puts the player back there.
     */
    public static void moved(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        ApiRuntime r = runtime;
        if (r == null || !r.isServerRunning() || player.hasDisconnected()) {
            return;
        }
        if (Logins.isRestricted(player.getUUID())) {
            // a restricted player stays where they are; only falling is let through (or they would hang in the air
            // and be kicked for flying). No event: to plugins this player is not moving.
            boolean sideways = player.getX() != x || player.getZ() != z;
            boolean up = player.getY() > y;
            if (sideways || up) {
                player.connection.teleport(x, up ? y : player.getY(), z, player.getYRot(), player.getXRot());
            }
            return;
        }
        World world = WorldImpl.of(player.level());
        Location from = new Location(world, x, y, z, yaw, pitch);
        Location to = new Location(world, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        if (EventBus.post(new PlayerMoveEvent(PlayerImpl.of(player), from, to)).isCancelled()) {
            player.connection.teleport(x, y, z, yaw, pitch);
        }
    }

    /** Start of {@code Player.attack}: whether to drop the hit. */
    public static boolean attack(ServerPlayer player, Entity target) {
        ApiRuntime r = runtime;
        if (r == null || !r.isServerRunning()) {
            return false;
        }
        if (Logins.isRestricted(player.getUUID())) {
            return true;
        }
        double distance = Math.sqrt(target.getBoundingBox().distanceToSqr(player.getEyePosition()));
        return EventBus.post(new PlayerAttackEvent(PlayerImpl.of(player), EntityImpl.of(target), distance,
                player.entityInteractionRange())).isCancelled();
    }

    /** {@code ServerGamePacketListenerImpl.broadcastChatMessage}: whether to drop the message. */
    public static boolean chat(ServerPlayer player, String message) {
        ApiRuntime r = runtime;
        if (r == null || !r.isServerRunning()) {
            return false;
        }
        if (Logins.isRestricted(player.getUUID())) {
            return true; // also keeps a password typed without the slash out of everybody's chat
        }
        return EventBus.post(new PlayerChatEvent(PlayerImpl.of(player), message)).isCancelled();
    }

    /** {@code ServerCommonPacketListenerImpl.handleCustomClickAction}: whether it was a form's answer. */
    public static boolean formAnswer(ServerPlayer player, net.minecraft.resources.Identifier action,
            java.util.Optional<net.minecraft.nbt.Tag> payload) {
        if (!onServerThread(player)) {
            return false; // the network thread's call: the game re-schedules it to the server thread
        }
        if (FormImpl.answer(player, action, payload)) {
            return true;
        }
        return Logins.isRestricted(player.getUUID()); // nothing else from a restricted player
    }

    /** See {@link LoginHooks#ip}. */
    public static String ip(java.net.SocketAddress address) {
        return LoginHooks.ip(address);
    }
}
