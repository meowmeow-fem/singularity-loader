package net.singularitymc.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.singularitymc.api.event.CancellableEvent;

/** A player is about to break a block. Cancel it and the block stays. */
public final class BlockBreakEvent extends CancellableEvent {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState state;

    public BlockBreakEvent(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        this.player = player;
        this.level = level;
        this.pos = pos.immutable();
        this.state = state;
    }

    public ServerPlayer getPlayer() { return player; }
    public ServerLevel getLevel() { return level; }
    public BlockPos getPos() { return pos; }
    public BlockState getState() { return state; }
}
