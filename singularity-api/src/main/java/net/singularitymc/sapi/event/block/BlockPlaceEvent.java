package net.singularitymc.sapi.event.block;

import java.util.Optional;
import net.singularitymc.api.event.CancellableEvent;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.world.BlockLocation;
import net.singularitymc.sapi.world.World;

/** A block item is about to be placed. Cancel it and nothing is placed. */
public final class BlockPlaceEvent extends CancellableEvent {
    private final Player player;
    private final World world;
    private final BlockLocation location;
    private final String item;

    public BlockPlaceEvent(Player player, World world, BlockLocation location, String item) {
        this.player = player;
        this.world = world;
        this.location = location;
        this.item = item;
    }

    /** Empty when it is not placed by a player. */
    public Optional<Player> player() {
        return Optional.ofNullable(player);
    }

    public World world() {
        return world;
    }

    /** Where the new block goes (true 64-bit position). */
    public BlockLocation location() {
        return location;
    }

    /** Item id of the block item, e.g. {@code minecraft:oak_planks}. */
    public String item() {
        return item;
    }
}
