package net.singularitymc.loader.patches.longpos;

import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** experimental_long_positions, 3.5.6: the world border's centre in the player's shifted frame. */
@Mixin({ClientboundInitializeBorderPacket.class, ClientboundSetBorderCenterPacket.class})
abstract class BorderPacketLongMixin {
    private static final String WRITE_DOUBLE = "Lnet/minecraft/network/FriendlyByteBuf;writeDouble(D)Lnet/minecraft/network/FriendlyByteBuf;";

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = WRITE_DOUBLE, ordinal = 0))
    private double Singularity$centerX(double x) {
        return WidePacking.clientBorderCenterX(x);
    }

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = WRITE_DOUBLE, ordinal = 1))
    private double Singularity$centerZ(double z) {
        return WidePacking.clientBorderCenterZ(z);
    }
}
