package net.singularitymc.stress.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.singularitymc.stress.StressMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class ServerTickMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void stress$start(BooleanSupplier haveTime, CallbackInfo ci) {
        StressMod.tickStart((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void stress$end(BooleanSupplier haveTime, CallbackInfo ci) {
        StressMod.tickEnd();
    }
}
