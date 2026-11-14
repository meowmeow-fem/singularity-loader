package net.singularitymc.loader.patches;

import java.util.Set;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity tracking, 4.2: the check "does this player see this entity" runs for every entity of the world whenever a
 * player moves, and vanilla starts each one by walking the entity's passengers through a stream to find its tracking
 * range. In a profile with 50 players that was a third of the tick. An entity without passengers (nearly all) has a
 * fixed range, and one that is out of it needs no more than a look at who sees it.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin {
    @Shadow @Final private Entity entity;
    @Shadow @Final private int range;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;
    @Shadow @Final private ServerEntity serverEntity;

    @Shadow
    private int scaledRange(int range) {
        throw new AssertionError();
    }

    @Inject(method = "getEffectiveRange", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$rangeWithoutPassengers(CallbackInfoReturnable<Integer> cir) {
        if (!this.entity.isVehicle()) {
            cir.setReturnValue(this.scaledRange(this.range));
        }
    }

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$outOfRange(ServerPlayer player, CallbackInfo ci) {
        Entity e = this.entity;
        if (player == e || e.isVehicle()) {
            return;
        }
        double dx = player.getX() - e.getX(), dz = player.getZ() - e.getZ();
        double r = this.scaledRange(this.range);
        if (dx * dx + dz * dz > r * r) {
            // vanilla's result for "out of range": stop showing it if this player saw it
            if (!this.seenBy.isEmpty() && this.seenBy.remove(player.connection)) {
                this.serverEntity.removePairing(player);
            }
            ci.cancel();
        }
    }
}
