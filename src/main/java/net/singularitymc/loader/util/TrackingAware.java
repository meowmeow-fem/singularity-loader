package net.singularitymc.loader.util;

/**
 * Implemented by {@code ServerPlayer} at run time (through a Mixin): the server tick in which this player's moves
 * last re-checked every tracked entity (see {@code ChunkMapMoveMixin}).
 */
public interface TrackingAware {
    int singularity$lastTrackingTick();

    void singularity$setLastTrackingTick(int tick);
}
