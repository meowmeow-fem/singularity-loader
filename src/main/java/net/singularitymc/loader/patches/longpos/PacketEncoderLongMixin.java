package net.singularitymc.loader.patches.longpos;

import com.llamalad7.mixinextras.sugar.Local;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.singularitymc.loader.util.WidePacking;
import net.singularitymc.network.FloatingOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * experimental_long_positions, 3.5.6: packets go out in the player's frame (see {@link FloatingOrigin}). The origin
 * is set for the codecs (block, section, chunk positions, a few raw coordinates) while the packet is encoded.
 */
@Mixin(PacketEncoder.class)
abstract class PacketEncoderLongMixin {
    private static final String ENCODE = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V";

    @ModifyVariable(method = ENCODE, at = @At("HEAD"), argsOnly = true)
    private Packet<?> Singularity$toClient(Packet<?> packet, @Local(argsOnly = true) ChannelHandlerContext ctx) {
        WidePacking.setNetworkOrigin(FloatingOrigin.origin(ctx.channel()));
        return FloatingOrigin.toClient(packet);
    }

    @Inject(method = ENCODE, at = @At("RETURN"))
    private void Singularity$done(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        WidePacking.clearNetworkOrigin();
    }
}
