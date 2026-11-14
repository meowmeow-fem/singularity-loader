package net.singularitymc.loader.patches.longpos;

import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** experimental_long_positions, 3.5.6: the centre of the client's chunk cache, in its shifted frame. */
@Mixin(ClientboundSetChunkCacheCenterPacket.class)
abstract class ChunkCacheCenterPacketLongMixin {
    private static final String WRITE_VAR_INT = "Lnet/minecraft/network/FriendlyByteBuf;writeVarInt(I)Lnet/minecraft/network/FriendlyByteBuf;";

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = WRITE_VAR_INT, ordinal = 0))
    private int Singularity$x(int x) {
        return WidePacking.clientChunkX(x);
    }

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = WRITE_VAR_INT, ordinal = 1))
    private int Singularity$z(int z) {
        return WidePacking.clientChunkZ(z);
    }
}
