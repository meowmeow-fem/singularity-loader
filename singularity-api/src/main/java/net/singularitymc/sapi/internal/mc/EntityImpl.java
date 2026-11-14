package net.singularitymc.sapi.internal.mc;

import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.singularitymc.sapi.world.Location;
import net.singularitymc.sapi.world.World;

/** An entity; {@link PlayerImpl} for players. */
public class EntityImpl implements net.singularitymc.sapi.entity.Entity {
    private final Entity entity;

    EntityImpl(Entity entity) {
        this.entity = entity;
    }

    public static net.singularitymc.sapi.entity.Entity of(Entity e) {
        return e instanceof ServerPlayer p ? PlayerImpl.of(p) : new EntityImpl(e);
    }

    /** The Minecraft entity (throws for a player who left). */
    protected Entity entity() {
        return entity;
    }

    @Override
    public UUID uuid() {
        return entity().getUUID();
    }

    @Override
    public String type() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity().getType()).toString();
    }

    @Override
    public String name() {
        return entity().getName().getString();
    }

    @Override
    public Location location() {
        Entity e = entity();
        return new Location(WorldImpl.of(e.level()), e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot());
    }

    @Override
    public World world() {
        return WorldImpl.of(entity().level());
    }

    @Override
    public boolean teleport(Location to) {
        ServerLevel level = ((WorldImpl) to.world()).level();
        return entity().teleportTo(level, to.x(), to.y(), to.z(), Set.of(), to.yaw(), to.pitch(), true);
    }

    @Override
    public boolean isValid() {
        return !entity.isRemoved();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof net.singularitymc.sapi.entity.Entity other && other.uuid().equals(uuid());
    }

    @Override
    public int hashCode() {
        return uuid().hashCode();
    }

    @Override
    public String toString() {
        return type() + " " + uuid();
    }
}
