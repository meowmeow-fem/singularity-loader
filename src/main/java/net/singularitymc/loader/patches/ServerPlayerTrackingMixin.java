package net.singularitymc.loader.patches;

import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.loader.util.TrackingAware;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** When this player's moves last re-checked every tracked entity (4.2, see {@code ChunkMapMoveMixin}). */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerTrackingMixin implements TrackingAware {
    @Unique private int singularity$lastTrackingTick = Integer.MIN_VALUE / 2;

    @Override
    public int singularity$lastTrackingTick() {
        return singularity$lastTrackingTick;
    }

    @Override
    public void singularity$setLastTrackingTick(int tick) {
        this.singularity$lastTrackingTick = tick;
    }
}
