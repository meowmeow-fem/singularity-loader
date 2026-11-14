package net.singularitymc.sapi.internal.mixin;

import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.singularitymc.sapi.internal.mc.ServerHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code PlayerChatEvent}: posted on the server thread right before a chat message is sent to everyone. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ChatMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void sapi$chat(PlayerChatMessage message, CallbackInfo ci) {
        if (ServerHooks.chat(player, message.signedContent())) {
            ci.cancel();
        }
    }
}
