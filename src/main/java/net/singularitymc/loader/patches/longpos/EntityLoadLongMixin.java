package net.singularitymc.loader.patches.longpos;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * experimental_long_positions, 3.5.5: an entity's saved block positions (home, leash knot, respawn point, memories)
 * are ints; once its position is read, it is remembered, so they are carried back next to it.
 */
@Mixin(Entity.class)
abstract class EntityLoadLongMixin {
    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getZ();

    @Inject(method = "load(Lnet/minecraft/world/level/storage/ValueInput;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setPosRaw(DDD)V", shift = At.Shift.AFTER))
    private void Singularity$carryFromPosition(ValueInput input, CallbackInfo ci) {
        WidePacking.carryAt(getX(), getZ());
    }
}
