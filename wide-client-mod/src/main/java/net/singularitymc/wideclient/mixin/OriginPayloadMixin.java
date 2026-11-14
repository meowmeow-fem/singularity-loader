package net.singularitymc.wideclient.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.singularitymc.wideclient.ClientOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reads the frame shift a Singularity server sends ({@link ClientOrigin}). */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class OriginPayloadMixin {
    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
    private void SingularityClient$origin(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (packet.payload() instanceof DiscardedPayload discarded && ClientOrigin.accept(discarded.id())) {
            ci.cancel();
        }
    }
}
