package net.singularitymc.sapi.internal.mixin;

import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.singularitymc.sapi.internal.mc.LoginHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A restricted player's commands are refused where they would run (after the game has checked the packet's place
 * in the signed chat chain), except the ones the restriction allows.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class RestrictedCommandMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void sapi$unsigned(String command, CallbackInfo ci) {
        if (LoginHooks.commandBlocked(player, command)) {
            ci.cancel();
        }
    }

    @Inject(method = "performSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void sapi$signed(ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeen, CallbackInfo ci) {
        if (LoginHooks.commandBlocked(player, packet.command())) {
            ci.cancel();
        }
    }
}
