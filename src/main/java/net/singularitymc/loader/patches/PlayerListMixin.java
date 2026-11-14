package net.singularitymc.loader.patches;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.event.PlayerJoinEvent;
import net.singularitymc.event.PlayerLeaveEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Events: {@link PlayerJoinEvent} and {@link PlayerLeaveEvent}. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"), require = 0)
    private void Singularity$joinEvent(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        EventBus.post(new PlayerJoinEvent(player));
    }

    @Inject(method = "remove", at = @At("HEAD"), require = 0)
    private void Singularity$leaveEvent(ServerPlayer player, CallbackInfo ci) {
        EventBus.post(new PlayerLeaveEvent(player));
    }
}
