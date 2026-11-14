package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.server.MinecraftServer;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 29,999,984}-block limits
 * in {@code MinecraftServer} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}).
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerLimitMixin {
    @ModifyConstant(method = "getAbsoluteMaxWorldSize", constant = @Constant(intValue = 29999984), require = 0)
    private int Singularity$getAbsoluteMaxWorldSize0(int original) {
        return PatchSettings.border();
    }
}
