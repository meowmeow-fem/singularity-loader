package net.singularitymc.loader.patches.wide;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stage 2 of the extended world size (see docs/COORDINATES.md): block positions on the wire keep the vanilla 26/12/26
 * layout. Positions a client sends are moved next to that client's player (see {@code PacketDecoderWideMixin}), since a
 * vanilla client can only send X/Z modulo 2^26. With experimental_long_positions, positions are in the player's
 * shifted frame on the wire ({@code net.singularitymc.network.FloatingOrigin}, 3.5.6).
 */
@Mixin(FriendlyByteBuf.class)
abstract class FriendlyByteBufWideMixin {
    @Redirect(method = "writeBlockPos(Lio/netty/buffer/ByteBuf;Lnet/minecraft/core/BlockPos;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J"))
    private static long Singularity$wireEncode(BlockPos pos) {
        return WidePacking.vanillaBlockAsLong(WidePacking.clientBlockX(pos.getX()), pos.getY(), WidePacking.clientBlockZ(pos.getZ()));
    }

    @Redirect(method = "readBlockPos(Lio/netty/buffer/ByteBuf;)Lnet/minecraft/core/BlockPos;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;of(J)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos Singularity$wireDecode(long packed) {
        if (WidePacking.hasNetworkOrigin()) {
            return BlockPos.of(WidePacking.blockAsLong(WidePacking.wireBlockX(packed) + WidePacking.networkOriginX(),
                WidePacking.vanillaBlockY(packed), WidePacking.wireBlockZ(packed) + WidePacking.networkOriginZ()));
        }
        return new BlockPos(WidePacking.wireBlockX(packed), WidePacking.vanillaBlockY(packed), WidePacking.wireBlockZ(packed));
    }
}
