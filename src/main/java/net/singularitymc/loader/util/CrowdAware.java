package net.singularitymc.loader.util;

/**
 * Implemented by {@code LivingEntity} at run time (through a Mixin). Remembers how many pushable
 * neighbours the entity found the last time it ran its collision step, which is a cheap
 * measure of "how crowded is it here" that the other patches reuse.
 */
public interface CrowdAware {
    int singularity$getCrowd();
}
