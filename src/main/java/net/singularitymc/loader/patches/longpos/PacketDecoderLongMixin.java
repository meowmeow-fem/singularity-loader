package net.singularitymc.loader.patches.longpos;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import net.singularitymc.network.FloatingOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * experimental_long_positions, 3.5.6: packets from a player come back into the server's frame (see
 * {@link FloatingOrigin}); block positions are already moved by their codec.
 */
@Mixin(PacketDecoder.class)
abstract class PacketDecoderLongMixin {
    @Inject(method = "decode", at = @At("RETURN"))
    private void Singularity$fromClient(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        long[] origin = FloatingOrigin.origin(ctx.channel());
        for (int i = 0; i < out.size(); i++) {
            out.set(i, FloatingOrigin.fromClient(out.get(i), origin));
        }
    }
}
