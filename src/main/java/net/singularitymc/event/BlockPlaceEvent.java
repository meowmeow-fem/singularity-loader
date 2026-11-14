package net.singularitymc.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.singularitymc.api.event.CancellableEvent;

/** A block item is about to be placed (by a player, or a dispenser-like source: then {@link #getPlayer()} is {@code null}). */
public final class BlockPlaceEvent extends CancellableEvent {
    private final Player player;
    private final ServerLevel level;
    private final BlockPos pos;
    private final ItemStack stack;

    public BlockPlaceEvent(Player player, ServerLevel level, BlockPos pos, ItemStack stack) {
        this.player = player;
        this.level = level;
        this.pos = pos.immutable();
        this.stack = stack;
    }

    /** May be {@code null}. */
    public Player getPlayer() { return player; }
    public ServerLevel getLevel() { return level; }
    /** The block position the new block goes to. */
    public BlockPos getPos() { return pos; }
    public ItemStack getStack() { return stack; }
}
