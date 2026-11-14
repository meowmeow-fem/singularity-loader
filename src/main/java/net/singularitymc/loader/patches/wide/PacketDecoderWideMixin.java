package net.singularitymc.loader.patches.wide;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.singularitymc.loader.util.WidePacking;
import net.singularitymc.network.FloatingOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stage 2 of the extended world size (see docs/COORDINATES.md): while a player's packet is decoded, remembers where that
 * player is, so {@code FriendlyByteBufWideMixin} can un-alias the block positions in it (reach is a few blocks, the
 * alias period 2^26). With experimental_long_positions the client's frame is shifted ({@link FloatingOrigin}, 3.5.6):
 * the origin is set too, and the player's position is taken in the client's frame.
 */
@Mixin(PacketDecoder.class)
abstract class PacketDecoderWideMixin {
    @Inject(method = "decode", at = @At("HEAD"))
    private void Singularity$rememberPlayer(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        WidePacking.clearNetworkReference();
        long[] origin = FloatingOrigin.origin(ctx.channel());
        WidePacking.setNetworkOrigin(origin);
        if (ctx.pipeline().get("packet_handler") instanceof Connection connection
                && connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener) {
            ServerPlayer player = listener.player;
            if (player != null) {
                WidePacking.setNetworkReference(Mth.floor(player.getX() - origin[0]), Mth.floor(player.getZ() - origin[1]));
            }
        }
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void Singularity$forgetPlayer(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        WidePacking.clearNetworkReference();
        WidePacking.clearNetworkOrigin();
    }
}
