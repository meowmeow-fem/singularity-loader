package net.singularitymc.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.singularitymc.api.event.CancellableEvent;

/** A living entity is about to take damage. Cancel it and the entity is not hurt. The amount is read-only for now. */
public final class EntityDamageEvent extends CancellableEvent {
    private final LivingEntity entity;
    private final ServerLevel level;
    private final DamageSource source;
    private final float amount;

    public EntityDamageEvent(LivingEntity entity, ServerLevel level, DamageSource source, float amount) {
        this.entity = entity;
        this.level = level;
        this.source = source;
        this.amount = amount;
    }

    public LivingEntity getEntity() { return entity; }
    public ServerLevel getLevel() { return level; }
    public DamageSource getSource() { return source; }
    public float getAmount() { return amount; }
}
