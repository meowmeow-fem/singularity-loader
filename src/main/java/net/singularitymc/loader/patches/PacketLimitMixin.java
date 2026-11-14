package net.singularitymc.loader.patches;

import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Stage 1 of the extended world size (see docs/COORDINATES.md): replaces the hard-coded {@code 30,000,000}-block limits
 * in {@code ServerGamePacketListenerImpl} by the {@code max_coordinate} setting. With the default (30,000,000) nothing changes.
 * Every hook is optional ({@code require = 0}).
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PacketLimitMixin {
    @ModifyConstant(method = "clampHorizontal", constant = @Constant(doubleValue = 30000000.0), require = 0)
    private static double Singularity$clampHorizontal0(double original) {
        return PatchSettings.limitD();
    }

    @ModifyConstant(method = "clampHorizontal", constant = @Constant(doubleValue = -30000000.0), require = 0)
    private static double Singularity$clampHorizontal1(double original) {
        return -PatchSettings.limitD();
    }
}
