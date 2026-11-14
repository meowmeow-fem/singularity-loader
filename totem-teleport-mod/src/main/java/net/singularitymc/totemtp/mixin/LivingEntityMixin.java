package net.singularitymc.totemtp.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.singularitymc.totemtp.TotemTeleportMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code checkTotemDeathProtection} returns true when a totem was just used up to save the entity. At that moment,
 * if the entity is a player, we move them to a random safe spot on the surface.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @Inject(method = "checkTotemDeathProtection", at = @At("RETURN"))
    private void totemteleport$afterTotem(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue()) || !((Object) this instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || level.dimensionType().hasCeiling()) {
            return; // the Nether has a roof, so "the surface" would be above the bedrock ceiling
        }
        for (int attempt = 0; attempt < TotemTeleportMod.ATTEMPTS; attempt++) {
            double angle = player.getRandom().nextDouble() * Math.PI * 2;
            double distance = TotemTeleportMod.MIN_DISTANCE
                    + player.getRandom().nextDouble() * (TotemTeleportMod.MAX_DISTANCE - TotemTeleportMod.MIN_DISTANCE);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinY()) {
                continue;
            }
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) {
                continue; // do not land in water or lava
            }
            player.teleportTo(x + 0.5, y, z + 0.5);
            player.sendSystemMessage(Component.literal("Your totem threw you to " + x + ", " + y + ", " + z + "!"));
            return;
        }
    }
}
