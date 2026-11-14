package net.singularitymc.loader.patches.longpos;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** experimental_long_positions, 3.5.6: an entity appears at its position in the player's shifted frame. */
@Mixin(ClientboundAddEntityPacket.class)
abstract class AddEntityPacketLongMixin {
    private static final String WRITE_DOUBLE = "Lnet/minecraft/network/RegistryFriendlyByteBuf;writeDouble(D)Lnet/minecraft/network/FriendlyByteBuf;";

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = WRITE_DOUBLE, ordinal = 0))
    private double Singularity$x(double x) {
        return WidePacking.clientX(x);
    }

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = WRITE_DOUBLE, ordinal = 2))
    private double Singularity$z(double z) {
        return WidePacking.clientZ(z);
    }
}
