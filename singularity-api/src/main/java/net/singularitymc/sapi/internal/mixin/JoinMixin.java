package net.singularitymc.sapi.internal.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.singularitymc.sapi.internal.mc.LoginHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carries "this connection proved its account to Mojang" over to the player it becomes. */
@Mixin(PlayerList.class)
abstract class JoinMixin {
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void sapi$joining(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        LoginHooks.joining(connection, player);
    }
}
