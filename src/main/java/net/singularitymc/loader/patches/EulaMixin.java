package net.singularitymc.loader.patches;

import net.minecraft.server.Eula;
import net.singularitymc.loader.util.ServerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft asks its own {@code eula.txt}. Singularity has already asked the owner (answer in {@code ServerEula.txt}),
 * so the game gets that answer and never creates an {@code eula.txt}.
 */
@Mixin(Eula.class)
public abstract class EulaMixin {
    @Inject(method = "readFile", at = @At("HEAD"), cancellable = true, require = 0)
    private void singularity$answer(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(ServerState.isEulaAccepted());
    }

    @Inject(method = "saveDefaults", at = @At("HEAD"), cancellable = true, require = 0)
    private void singularity$noEulaFile(CallbackInfo ci) {
        ci.cancel();
    }
}
