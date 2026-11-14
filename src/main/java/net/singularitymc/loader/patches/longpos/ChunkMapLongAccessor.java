package net.singularitymc.loader.patches.longpos;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** experimental_long_positions, 3.5.6: moving a player's frame drops and re-tracks their chunks and entities. */
@Mixin(ChunkMap.class)
public interface ChunkMapLongAccessor {
    @Invoker("updatePlayerStatus")
    void singularity$updatePlayerStatus(ServerPlayer player, boolean track);
}
