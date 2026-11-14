package net.singularitymc.loader.patches.longpos;

import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.singularitymc.network.FloatingOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * experimental_long_positions, 3.5.6: right before the server sends a player their (new) position, their frame moves
 * if that position is outside it ({@link FloatingOrigin#beforePositionSync}): chunks and entities are dropped in the
 * old frame and tracked again in the new one, and border, spawn and time are sent again.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class TeleportLongMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void Singularity$moveFrame(PositionMoveRotation change, Set<Relative> relatives, CallbackInfo ci) {
        ServerPlayer p = this.player;
        ServerLevel level = p.level();
        boolean tracked = level.players().contains(p);
        FloatingOrigin.beforePositionSync(p, ((ServerCommonListenerLongAccessor) this).singularity$connection(), tracked, apply -> {
            ChunkMapLongAccessor chunks = (ChunkMapLongAccessor) level.getChunkSource().chunkMap;
            chunks.singularity$updatePlayerStatus(p, false);
            apply.run();
            chunks.singularity$updatePlayerStatus(p, true);
            ChunkPos at = p.chunkPosition();
            ((ServerGamePacketListenerImpl) (Object) this).send(new ClientboundSetChunkCacheCenterPacket(at.x(), at.z()));
            level.getServer().getPlayerList().sendLevelInfo(p, level);
        });
    }
}
