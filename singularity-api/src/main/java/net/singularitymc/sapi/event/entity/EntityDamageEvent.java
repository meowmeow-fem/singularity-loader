package net.singularitymc.sapi.event.entity;

import net.singularitymc.api.event.CancellableEvent;
import net.singularitymc.sapi.entity.Entity;

/** A living entity (players too) is about to take damage. Cancel it and it is not hurt. */
public final class EntityDamageEvent extends CancellableEvent {
    private final Entity entity;
    private final String damageType;
    private final float amount;

    public EntityDamageEvent(Entity entity, String damageType, float amount) {
        this.entity = entity;
        this.damageType = damageType;
        this.amount = amount;
    }

    public Entity entity() {
        return entity;
    }

    /** Damage type id, e.g. {@code minecraft:fall}. */
    public String damageType() {
        return damageType;
    }

    public float amount() {
        return amount;
    }
}
