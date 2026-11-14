package net.singularitymc.sapi.internal.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.singularitymc.sapi.internal.mc.ServerHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code PlayerAttackEvent}: posted before a player's hit on an entity is worked out. */
@Mixin(Player.class)
abstract class AttackMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void sapi$attack(Entity target, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && ServerHooks.attack(player, target)) {
            ci.cancel();
        }
    }
}
