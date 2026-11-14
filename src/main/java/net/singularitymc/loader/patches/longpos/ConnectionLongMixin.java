package net.singularitymc.loader.patches.longpos;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.singularitymc.network.FloatingOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** experimental_long_positions, 3.5.6: the floating origin is stored on the connection's channel. */
@Mixin(Connection.class)
abstract class ConnectionLongMixin implements FloatingOrigin.ChannelHolder {
    @Shadow
    private Channel channel;

    @Override
    public Channel singularity$channel() {
        return this.channel;
    }
}
