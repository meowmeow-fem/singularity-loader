package net.singularitymc.loader.patches;

import net.minecraft.server.dedicated.DedicatedServer;
import net.singularitymc.gui.ServerGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server window (shown unless the server runs with {@code nogui}): Singularity's own instead of the vanilla one.
 * If ours is switched off or cannot be made, the game goes on and opens its own.
 */
@Mixin(DedicatedServer.class)
abstract class ServerWindowMixin {
    @Inject(method = "showGui", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$window(CallbackInfo ci) {
        if (ServerGui.open((DedicatedServer) (Object) this)) {
            ci.cancel();
        }
    }
}
