package net.singularitymc.sapi.internal.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.singularitymc.sapi.internal.mc.ServerHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Server started: the worlds are loaded ({@code initServer} returned true), plugins are enabled. */
@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
    @Inject(method = "initServer", at = @At("RETURN"))
    private void sapi$started(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            ServerHooks.started((MinecraftServer) (Object) this);
        }
    }
}
