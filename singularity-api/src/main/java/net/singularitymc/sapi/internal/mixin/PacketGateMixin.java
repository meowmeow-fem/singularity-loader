package net.singularitymc.sapi.internal.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.singularitymc.sapi.internal.mc.LoginHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Player.restrict}: every packet a client sends passes through {@code Connection.genericsFtw} on its way to
 * its handler; a restricted player's packets are dropped here unless they are on the allow-list.
 */
@Mixin(Connection.class)
abstract class PacketGateMixin {
    @Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
    private static void sapi$gate(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (LoginHooks.blocked(packet, listener)) {
            ci.cancel();
        }
    }
}
