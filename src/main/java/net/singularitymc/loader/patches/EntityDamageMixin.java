package net.singularitymc.loader.patches;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.event.EntityDamageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Event: {@link EntityDamageEvent}. */
@Mixin(LivingEntity.class)
abstract class EntityDamageMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$damageEvent(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (EventBus.post(new EntityDamageEvent((LivingEntity) (Object) this, level, source, amount)).isCancelled()) {
            cir.setReturnValue(false);
        }
    }
}
