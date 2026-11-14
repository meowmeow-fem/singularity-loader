package net.singularitymc.loader.patches;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the server identify itself as modded: the brand shown in crash reports and sent to
 * clients changes from {@code vanilla} to {@code singularity}.
 *
 * <p>The target is addressed by name so that the loader does not need the game on its compile
 * class path. If a future game version renames the method the injection is simply skipped
 * ({@code require = 0}) instead of stopping the server.
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "getServerModName", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$changeServerBrand(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue("Singularity");
    }
}
