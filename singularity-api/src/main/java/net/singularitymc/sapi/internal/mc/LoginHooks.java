package net.singularitymc.sapi.internal.mc;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.singularitymc.sapi.internal.Logins;
import net.singularitymc.sapi.login.LoginDecision;

/** Called by the API mod's login and packet Mixins. All of it runs on network threads. */
public final class LoginHooks {
    /** Connections whose login went through Mojang's session servers, until the player object exists. */
    private static final Set<Connection> VERIFIED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * What a restricted player's client may still send, by class name (so that packets a game version does not
     * have cost nothing): what keeps the connection and the client's world alive, chat and commands (those are
     * let through here because the game checks their signatures in order, and are refused where they would take
     * effect), and answers to forms. Everything else is dropped, including every packet added by a later version.
     */
    private static final Set<String> ALLOWED = Set.of(
            "net.minecraft.network.protocol.common.ServerboundKeepAlivePacket",
            "net.minecraft.network.protocol.common.ServerboundPongPacket",
            "net.minecraft.network.protocol.common.ServerboundClientInformationPacket",
            "net.minecraft.network.protocol.common.ServerboundResourcePackPacket",
            "net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket",
            "net.minecraft.network.protocol.ping.ServerboundPingRequestPacket",
            "net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket",
            "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket",
            "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket",
            "net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket",
            "net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket",
            "net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket",
            "net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket",
            "net.minecraft.network.protocol.game.ServerboundClientCommandPacket",
            "net.minecraft.network.protocol.game.ServerboundContainerClosePacket",
            "net.minecraft.network.protocol.game.ServerboundChatPacket",
            "net.minecraft.network.protocol.game.ServerboundChatAckPacket",
            "net.minecraft.network.protocol.game.ServerboundChatCommandPacket",
            "net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket");

    private static final ClassValue<Boolean> IS_ALLOWED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String name = type.getName();
            int inner = name.indexOf('$'); // ServerboundMovePlayerPacket$Pos and the like
            return ALLOWED.contains(inner < 0 ? name : name.substring(0, inner));
        }
    };

    private LoginHooks() {}

    /** Whether a login on this connection goes through the gate (a plugin set one, and it is a real network client). */
    public static boolean gateActive(Connection connection) {
        return Logins.hasGate() && !connection.isMemoryConnection();
    }

    public static CompletableFuture<LoginDecision> ask(String name, Connection connection) {
        return Logins.ask(name, ip(connection.getRemoteAddress()));
    }

    /** The session servers confirmed the account behind this connection. */
    public static void verified(Connection connection) {
        VERIFIED.add(connection);
    }

    /** Start of {@code PlayerList.placeNewPlayer}: the connection becomes a player. */
    public static void joining(Connection connection, ServerPlayer player) {
        if (VERIFIED.remove(connection)) {
            Logins.markVerified(player.getUUID());
        }
    }

    /** {@code Connection.genericsFtw}: whether to drop a packet because its sender is restricted. */
    public static boolean blocked(Packet<?> packet, PacketListener listener) {
        if (!Logins.anyRestricted() || !(listener instanceof ServerGamePacketListenerImpl game)) {
            return false;
        }
        return Logins.isRestricted(game.player.getUUID()) && !IS_ALLOWED.get(packet.getClass());
    }

    /** A restricted player typed a command: whether to refuse it. */
    public static boolean commandBlocked(ServerPlayer player, String command) {
        return Logins.isRestricted(player.getUUID()) && !Logins.commandAllowed(player.getUUID(), command);
    }

    public static String ip(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return address == null ? "" : address.toString();
    }
}
