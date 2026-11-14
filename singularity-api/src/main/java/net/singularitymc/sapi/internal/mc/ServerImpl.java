package net.singularitymc.sapi.internal.mc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.api.SingularityLoader;
import net.singularitymc.loader.util.ServerState;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.server.Server;
import net.singularitymc.sapi.world.World;

final class ServerImpl implements Server {
    private final MinecraftServer server;

    ServerImpl(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public Collection<Player> players() {
        List<ServerPlayer> snapshot = null;
        for (int i = 0; snapshot == null; i++) {
            try {
                snapshot = List.copyOf(server.getPlayerList().getPlayers());
            } catch (ConcurrentModificationException | NullPointerException e) {
                // the list changed while it was copied from another thread; try again
                if (i == 10) {
                    snapshot = List.of();
                }
            }
        }
        List<Player> out = new ArrayList<>(snapshot.size());
        for (ServerPlayer p : snapshot) {
            out.add(PlayerImpl.of(p));
        }
        return out;
    }

    @Override
    public Optional<Player> player(String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(PlayerImpl::of);
    }

    @Override
    public Optional<Player> player(UUID uuid) {
        return Optional.ofNullable(server.getPlayerList().getPlayer(uuid)).map(PlayerImpl::of);
    }

    @Override
    public List<World> worlds() {
        List<World> out = new ArrayList<>();
        for (ServerLevel l : server.getAllLevels()) {
            out.add(WorldImpl.of(l));
        }
        return out;
    }

    @Override
    public Optional<World> world(String id) {
        String full = id.contains(":") ? id : "minecraft:" + id;
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().identifier().toString().equals(full)) {
                return Optional.of(WorldImpl.of(l));
            }
        }
        return Optional.empty();
    }

    @Override
    public World overworld() {
        return WorldImpl.of(server.overworld());
    }

    @Override
    public void broadcast(String message) {
        Component c = Component.literal(message);
        if (server.isSameThread()) {
            server.getPlayerList().broadcastSystemMessage(c, false);
        } else {
            server.execute(() -> server.getPlayerList().broadcastSystemMessage(c, false));
        }
    }

    @Override
    public boolean dispatchCommand(String command) {
        Runnable run = () -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        if (server.isSameThread()) {
            run.run();
        } else {
            server.execute(run);
        }
        return true;
    }

    @Override
    public CommandSender console() {
        return new SourceSender(server.createCommandSourceStack(), true);
    }

    @Override
    public long tickCount() {
        return server.getTickCount();
    }

    @Override
    public boolean isServerThread() {
        return server.isSameThread();
    }

    @Override
    public long maxCoordinate() {
        return ServerState.worldLimit64();
    }

    @Override
    public boolean longPositions() {
        return ServerState.longPositions();
    }

    @Override
    public String minecraftVersion() {
        return SingularityLoader.getInstance().getGameVersion();
    }

    @Override
    public String loaderVersion() {
        return SingularityLoader.getInstance().getLoaderVersion();
    }
}
