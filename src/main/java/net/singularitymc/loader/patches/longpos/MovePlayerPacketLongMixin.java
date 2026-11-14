package net.singularitymc.loader.patches.longpos;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.singularitymc.network.FloatingOrigin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/** experimental_long_positions, 3.5.6: a player's movement comes in the client's frame and is moved to the server's. */
@Mixin(ServerboundMovePlayerPacket.class)
abstract class MovePlayerPacketLongMixin implements FloatingOrigin.MovePlayerShift {
    @Shadow @Final @Mutable protected double x;
    @Shadow @Final @Mutable protected double z;
    @Shadow @Final protected boolean hasPos;

    @Override
    public void singularity$shift(double dx, double dz) {
        if (this.hasPos) {
            this.x += dx;
            this.z += dz;
        }
    }
}
