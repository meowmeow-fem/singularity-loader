package net.singularitymc.wideclient.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import net.singularitymc.loader.util.WidePacking;
import net.singularitymc.wideclient.NetworkReference;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client side of the wire format (docs/COORDINATES.md, "Client mod"): servers send block and section positions in the
 * vanilla layout, whose X/Z wrap every 2^26 blocks. While a packet is decoded, the local player's position is the
 * reference that {@code FriendlyByteBufWideMixin} / {@code SectionPosWideMixin} un-alias around. When this client hosts
 * a LAN game, packets from other players are decoded around that player, as on a Singularity server.
 */
@Mixin(PacketDecoder.class)
abstract class ClientPacketDecoderMixin {
    @Inject(method = "decode", at = @At("HEAD"))
    private void SingularityClient$rememberPlayer(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        WidePacking.clearNetworkReference();
        if (!(ctx.pipeline().get("packet_handler") instanceof Connection connection)) {
            return;
        }
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener) {
            ServerPlayer player = listener.player;
            if (player != null) {
                WidePacking.setNetworkReference(Mth.floor(player.getX()), Mth.floor(player.getZ()));
            }
        } else if (connection.getPacketListener() instanceof ClientGamePacketListener) {
            LocalPlayer player = Minecraft.getInstance().player;
            double[] ref = player == null ? NetworkReference.get(false, 0, 0) : NetworkReference.get(true, player.getX(), player.getZ());
            if (ref != null) {
                WidePacking.setNetworkReference(Mth.floor(ref[0]), Mth.floor(ref[1]));
            }
        }
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void SingularityClient$watchTeleports(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        for (Object packet : out) {
            if (packet instanceof ClientboundPlayerPositionPacket teleport) {
                Vec3 pos = teleport.change().position();
                double x = pos.x;
                double z = pos.z;
                boolean relativeX = teleport.relatives().contains(Relative.X);
                boolean relativeZ = teleport.relatives().contains(Relative.Z);
                if (relativeX || relativeZ) {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player == null) {
                        continue;
                    }
                    x += relativeX ? player.getX() : 0;
                    z += relativeZ ? player.getZ() : 0;
                }
                NetworkReference.onTeleport(x, z);
            }
        }
        WidePacking.clearNetworkReference();
    }
}
