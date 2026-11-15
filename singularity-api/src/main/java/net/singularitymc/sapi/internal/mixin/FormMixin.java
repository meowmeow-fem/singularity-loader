package net.singularitymc.sapi.internal.mixin;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.singularitymc.sapi.internal.mc.ServerHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Answers to {@code sapi.gui.Form}s arrive as "custom click actions". Like every packet this one reaches its
 * handler twice, on a network thread (where the game only re-schedules it) and then on the server thread; only the
 * second call is looked at.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class FormMixin {
    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void sapi$form(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game
                && ServerHooks.formAnswer(game.player, packet.id(), packet.payload())) {
            ci.cancel();
        }
    }
}
