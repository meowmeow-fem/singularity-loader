package net.singularitymc.sapi.internal.mc;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;
import net.singularitymc.sapi.entity.GameMode;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.internal.ApiRuntime;

/**
 * A player, looked up by UUID on every call: Minecraft makes a new {@link ServerPlayer} object on respawn and on
 * leaving the End, so holding the object would go stale.
 */
public final class PlayerImpl extends EntityImpl implements Player {
    private final MinecraftServer server;
    private final UUID uuid;
    private final String name;

    private PlayerImpl(ServerPlayer player) {
        super(player);
        this.server = player.level().getServer();
        this.uuid = player.getUUID();
        this.name = player.getName().getString();
    }

    public static PlayerImpl of(ServerPlayer player) {
        return new PlayerImpl(player);
    }

    private ServerPlayer online() {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (p == null) {
            throw new IllegalStateException(name + " is not online");
        }
        return p;
    }

    @Override
    protected ServerPlayer entity() {
        return online();
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isOnline() {
        return server.getPlayerList().getPlayer(uuid) != null;
    }

    @Override
    public boolean isValid() {
        return isOnline();
    }

    @Override
    public void sendMessage(String message) {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (p != null) {
            p.sendSystemMessage(Component.literal(message));
        }
    }

    @Override
    public boolean hasPermission(String node) {
        return ApiRuntime.get().permissions().check(this, node);
    }

    @Override
    public boolean isOp() {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        return p != null && p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public GameMode gameMode() {
        return GameMode.valueOf(online().gameMode().name());
    }

    @Override
    public void setGameMode(GameMode mode) {
        online().setGameMode(GameType.valueOf(mode.name()));
    }

    @Override
    public void kick(String reason) {
        online().connection.disconnect(Component.literal(reason));
    }

    @Override
    public String toString() {
        return "Player " + name;
    }
}
