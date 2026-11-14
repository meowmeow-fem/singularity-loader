package net.singularitymc.loader.patches;

import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.world.level.border.WorldBorder;
import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code world_border_warning = false} (the default): border packets tell clients a warning distance and time of 0.
 * A client draws the red border tint once the player is within the warning distance (or the time warning of a moving
 * border), so no client shows it, with or without a resource pack. The server's border itself (damage, interaction
 * limits) is unchanged; a player outside it still sees the tint. Packets read from the network keep their values.
 */
abstract class BorderWarningMixin {
    private BorderWarningMixin() {}

    @Mixin(ClientboundInitializeBorderPacket.class)
    abstract static class Initialize {
        @Redirect(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder;)V", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;getWarningBlocks()I"))
        private int Singularity$warningBlocks(WorldBorder border) {
            return PatchSettings.borderWarning() ? border.getWarningBlocks() : 0;
        }

        @Redirect(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder;)V", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;getWarningTime()I"))
        private int Singularity$warningTime(WorldBorder border) {
            return PatchSettings.borderWarning() ? border.getWarningTime() : 0;
        }
    }

    @Mixin(ClientboundSetBorderWarningDistancePacket.class)
    abstract static class Distance {
        @Redirect(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder;)V", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;getWarningBlocks()I"))
        private int Singularity$warningBlocks(WorldBorder border) {
            return PatchSettings.borderWarning() ? border.getWarningBlocks() : 0;
        }
    }

    @Mixin(ClientboundSetBorderWarningDelayPacket.class)
    abstract static class Delay {
        @Redirect(method = "<init>(Lnet/minecraft/world/level/border/WorldBorder;)V", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/border/WorldBorder;getWarningTime()I"))
        private int Singularity$warningTime(WorldBorder border) {
            return PatchSettings.borderWarning() ? border.getWarningTime() : 0;
        }
    }
}
