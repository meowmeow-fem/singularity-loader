package net.singularitymc.loader.patches.longpos;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * experimental_long_positions, 3.5.6: chunk positions on the wire are in the player's shifted frame and use the
 * vanilla key layout (a 64-bit key would mean nothing to the client).
 */
@Mixin(FriendlyByteBuf.class)
abstract class FriendlyByteBufLongMixin {
    @Redirect(method = {"writeChunkPos(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/network/FriendlyByteBuf;",
        "writeChunkPos(Lio/netty/buffer/ByteBuf;Lnet/minecraft/world/level/ChunkPos;)V"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ChunkPos;pack()J"))
    private static long Singularity$wireEncode(ChunkPos pos) {
        return WidePacking.chunkPack(WidePacking.clientChunkX(pos.x()), WidePacking.clientChunkZ(pos.z()));
    }

    @Redirect(method = {"readChunkPos()Lnet/minecraft/world/level/ChunkPos;", "readChunkPos(Lio/netty/buffer/ByteBuf;)Lnet/minecraft/world/level/ChunkPos;"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ChunkPos;unpack(J)Lnet/minecraft/world/level/ChunkPos;"))
    private static ChunkPos Singularity$wireDecode(long key) {
        return ChunkPos.unpack(WidePacking.chunkPack((long) WidePacking.chunkX(key) + (WidePacking.networkOriginX() >> 4),
            (long) WidePacking.chunkZ(key) + (WidePacking.networkOriginZ() >> 4)));
    }
}
