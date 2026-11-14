package net.singularitymc.loader.patches.longpos;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * experimental_long_positions, 3.5.6: sounds are sent at int eighths of a block, which vanilla computes with a cast
 * that sticks past 2^28 blocks; the position is wrapped first (see {@link WidePacking#wrapSoundCoord}) and shifted
 * into the player's frame when written.
 */
@Mixin(ClientboundSoundPacket.class)
abstract class SoundPacketLongMixin {
    private static final String CTOR = "<init>(Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;DDDFFJ)V";
    private static final String WRITE_INT = "Lnet/minecraft/network/RegistryFriendlyByteBuf;writeInt(I)Lnet/minecraft/network/FriendlyByteBuf;";

    @ModifyVariable(method = CTOR, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static double Singularity$wrapX(double x) {
        return WidePacking.wrapSoundCoord(x);
    }

    @ModifyVariable(method = CTOR, at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private static double Singularity$wrapZ(double z) {
        return WidePacking.wrapSoundCoord(z);
    }

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = WRITE_INT, ordinal = 0))
    private int Singularity$x(int x8) {
        return WidePacking.clientSoundX(x8);
    }

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = WRITE_INT, ordinal = 2))
    private int Singularity$z(int z8) {
        return WidePacking.clientSoundZ(z8);
    }
}
