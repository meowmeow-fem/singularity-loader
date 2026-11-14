package net.singularitymc.loader.patches.longpos;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** experimental_long_positions, 3.5.6: the floating origin is stored on the player's connection. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonListenerLongAccessor {
    @Accessor("connection")
    Connection singularity$connection();
}
