package net.singularitymc.sapi.event.block;

import net.singularitymc.api.event.CancellableEvent;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.world.BlockLocation;
import net.singularitymc.sapi.world.World;

/** A player is about to break a block. Cancel it and the block stays. */
public final class BlockBreakEvent extends CancellableEvent {
    private final Player player;
    private final World world;
    private final BlockLocation location;
    private final String block;

    public BlockBreakEvent(Player player, World world, BlockLocation location, String block) {
        this.player = player;
        this.world = world;
        this.location = location;
        this.block = block;
    }

    public Player player() {
        return player;
    }

    public World world() {
        return world;
    }

    /** True 64-bit position of the block. */
    public BlockLocation location() {
        return location;
    }

    /** Block id, e.g. {@code minecraft:stone}. */
    public String block() {
        return block;
    }
}
