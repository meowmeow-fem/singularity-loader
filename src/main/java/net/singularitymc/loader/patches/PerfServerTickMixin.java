package net.singularitymc.loader.patches;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times every server tick for the adaptive governor (4.2, {@code adaptive_performance}). */
@Mixin(MinecraftServer.class)
abstract class PerfServerTickMixin {
    @Inject(method = "tickServer", at = @At("HEAD"), require = 0)
    private void Singularity$perfTickStart(BooleanSupplier haveTime, CallbackInfo ci) {
        PerfHooks.tickStart();
    }

    @Inject(method = "tickServer", at = @At("RETURN"), require = 0)
    private void Singularity$perfTickEnd(BooleanSupplier haveTime, CallbackInfo ci) {
        PerfHooks.tickEnd((MinecraftServer) (Object) this);
    }
}
