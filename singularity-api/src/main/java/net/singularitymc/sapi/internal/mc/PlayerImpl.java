package net.singularitymc.sapi.internal.mc;

import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;
import net.singularitymc.sapi.entity.GameMode;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.internal.ApiRuntime;

/**
 * A player, looked up by UUID on every call: Minecraft makes a new {@link ServerPlayer} object on respawn and on
 * leaving the End, so holding the object would go stale.
 */
public final class PlayerImpl extends EntityImpl implements Player {
    private final MinecraftServer server;
    private final UUID uuid;
    private final String name;

    private PlayerImpl(ServerPlayer player) {
        super(player);
        this.server = player.level().getServer();
        this.uuid = player.getUUID();
        this.name = player.getName().getString();
    }

    public static PlayerImpl of(ServerPlayer player) {
        return new PlayerImpl(player);
    }

    private ServerPlayer online() {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (p == null) {
            throw new IllegalStateException(name + " is not online");
        }
        return p;
    }

    @Override
    protected ServerPlayer entity() {
        return online();
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isOnline() {
        return server.getPlayerList().getPlayer(uuid) != null;
    }

    @Override
    public boolean isValid() {
        return isOnline();
    }

    @Override
    public void sendMessage(String message) {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        if (p != null) {
            p.sendSystemMessage(Component.literal(message));
        }
    }

    @Override
    public boolean hasPermission(String node) {
        return ApiRuntime.get().permissions().check(this, node);
    }

    @Override
    public boolean isOp() {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        return p != null && p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public GameMode gameMode() {
        return GameMode.valueOf(online().gameMode().name());
    }

    @Override
    public void setGameMode(GameMode mode) {
        online().setGameMode(GameType.valueOf(mode.name()));
    }

    @Override
    public void kick(String reason) {
        online().connection.disconnect(Component.literal(reason));
    }

    /** The Minecraft player behind an API player, or {@code null} if that player is offline. */
    static ServerPlayer handle(Player player) {
        return player instanceof PlayerImpl p ? p.orNull() : null;
    }

    private ServerPlayer orNull() {
        return server.getPlayerList().getPlayer(uuid);
    }

    @Override
    public boolean isFlying() {
        ServerPlayer p = orNull();
        return p != null && p.getAbilities().flying;
    }

    @Override
    public boolean canFly() {
        ServerPlayer p = orNull();
        return p != null && p.getAbilities().mayfly;
    }

    @Override
    public boolean isGliding() {
        ServerPlayer p = orNull();
        return p != null && p.isFallFlying();
    }

    @Override
    public boolean isRiptiding() {
        ServerPlayer p = orNull();
        return p != null && p.isAutoSpinAttack();
    }

    @Override
    public boolean isInLiquid() {
        ServerPlayer p = orNull();
        return p != null && (p.isInWater() || p.isInLava());
    }

    @Override
    public boolean isClimbing() {
        ServerPlayer p = orNull();
        return p != null && p.onClimbable();
    }

    @Override
    public boolean isInVehicle() {
        ServerPlayer p = orNull();
        return p != null && p.isPassenger();
    }

    @Override
    public boolean isSupported() {
        ServerPlayer p = orNull();
        if (p == null) {
            return false;
        }
        // the hit box, a little wider, stretched half a block down: anything that collides with it holds the player
        AABB below = p.getBoundingBox().inflate(0.3, 0.0, 0.3).expandTowards(0.0, -0.6, 0.0);
        return !p.level().noCollision(p, below);
    }

    @Override
    public double movementSpeed() {
        ServerPlayer p = orNull();
        return p == null ? 0.0 : p.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    @Override
    public int effectLevel(String id) {
        ServerPlayer p = orNull();
        Identifier key = p == null ? null : Identifier.tryParse(id);
        if (key == null) {
            return 0;
        }
        return BuiltInRegistries.MOB_EFFECT.get(key).map(effect -> {
            MobEffectInstance active = p.getEffect(effect);
            return active == null ? 0 : active.getAmplifier() + 1;
        }).orElse(0);
    }

    @Override
    public int ping() {
        ServerPlayer p = orNull();
        return p == null ? 0 : p.connection.latency();
    }

    @Override
    public String toString() {
        return "Player " + name;
    }
}
