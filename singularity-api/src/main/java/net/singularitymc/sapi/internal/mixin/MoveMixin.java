package net.singularitymc.sapi.internal.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.singularitymc.sapi.internal.mc.ServerHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code PlayerMoveEvent}: the player's position is remembered when a movement packet starts being handled and
 * compared when it is done. The packet first arrives on a network thread, where the method only re-schedules itself
 * (by throwing): that call is ignored, and the fields below are touched by the server thread alone.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class MoveMixin {
    @Shadow
    public ServerPlayer player;

    @Unique
    private boolean sapi$moveArmed;
    @Unique
    private double sapi$fromX;
    @Unique
    private double sapi$fromY;
    @Unique
    private double sapi$fromZ;
    @Unique
    private float sapi$fromYaw;
    @Unique
    private float sapi$fromPitch;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void sapi$moveStart(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!ServerHooks.onServerThread(player)) {
            return;
        }
        sapi$moveArmed = true;
        sapi$fromX = player.getX();
        sapi$fromY = player.getY();
        sapi$fromZ = player.getZ();
        sapi$fromYaw = player.getYRot();
        sapi$fromPitch = player.getXRot();
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void sapi$moveEnd(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (sapi$moveArmed && ServerHooks.onServerThread(player)) {
            sapi$moveArmed = false;
            ServerHooks.moved(player, sapi$fromX, sapi$fromY, sapi$fromZ, sapi$fromYaw, sapi$fromPitch);
        }
    }
}
