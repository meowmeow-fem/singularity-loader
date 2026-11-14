package net.singularitymc.loader.util;

/**
 * Implemented by {@code Mob} at run time (through a Mixin): remembers whether a player was near the last time it was
 * checked, so the entity activation range (4.2) does not search the player list every tick.
 */
public interface ActivationAware {
    long singularity$nextNearCheck();

    boolean singularity$near();

    void singularity$setNear(boolean near, long nextCheck);
}
