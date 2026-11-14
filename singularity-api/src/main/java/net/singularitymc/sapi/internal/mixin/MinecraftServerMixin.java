package net.singularitymc.sapi.internal.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.singularitymc.sapi.internal.mc.ServerHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scheduler and tick event at the start of every tick; plugins disabled when the server stops. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void sapi$tick(BooleanSupplier haveTime, CallbackInfo ci) {
        ServerHooks.tick();
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void sapi$stopping(CallbackInfo ci) {
        ServerHooks.stopping();
    }
}
