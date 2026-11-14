package net.singularitymc.loader.patches.longpos;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.singularitymc.network.FloatingOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * experimental_long_positions, 3.5.6: a player who walks or flies out of their frame is synced to where they are,
 * which moves the frame ({@code TeleportLongMixin}).
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerLongMixin {
    @Shadow public ServerGamePacketListenerImpl connection;

    @Inject(method = "tick", at = @At("TAIL"))
    private void Singularity$keepInFrame(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (this.connection != null && !FloatingOrigin.fits(self, self.getX(), self.getZ())) {
            this.connection.teleport(self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot());
        }
    }
}
