package net.singularitymc.sapi.internal.mc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.world.BlockLocation;
import net.singularitymc.sapi.world.World;

/** A dimension, looked up by key on every call (stays valid as long as the server runs). */
public final class WorldImpl implements World {
    private final MinecraftServer server;
    private final ResourceKey<Level> key;

    WorldImpl(MinecraftServer server, ResourceKey<Level> key) {
        this.server = server;
        this.key = key;
    }

    public static WorldImpl of(Level level) {
        return new WorldImpl(((ServerLevel) level).getServer(), level.dimension());
    }

    ServerLevel level() {
        ServerLevel l = server.getLevel(key);
        if (l == null) {
            throw new IllegalStateException("World " + id() + " is not loaded");
        }
        return l;
    }

    @Override
    public String id() {
        return key.identifier().toString();
    }

    @Override
    public String getBlock(BlockLocation pos) {
        BlockState state = level().getBlockState(Coords.toBlockPos(pos));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    @Override
    public boolean setBlock(BlockLocation pos, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("'" + blockId + "' is not a block id");
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockPos p = Coords.toBlockPos(pos);
        return level().setBlock(p, block.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Override
    public boolean isLoaded(BlockLocation pos) {
        return level().isLoaded(Coords.toBlockPos(pos));
    }

    @Override
    public int minY() {
        return level().getMinY();
    }

    @Override
    public int maxY() {
        return level().getMaxY();
    }

    @Override
    public long seed() {
        return level().getSeed();
    }

    @Override
    public List<Player> players() {
        List<Player> out = new ArrayList<>();
        for (ServerPlayer p : List.copyOf(level().players())) {
            out.add(PlayerImpl.of(p));
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorldImpl w && w.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return id();
    }
}
