package net.singularitymc.example.mixin;

import net.singularitymc.example.ExampleMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Mixin that runs when the dedicated server starts loading its world.
 *
 * <p>The target class is given by name ({@code targets}) so the mod does not need Minecraft on its
 * compile class path. Minecraft 26.x is unobfuscated, so method names can be used as they are.
 */
@Mixin(targets = "net.minecraft.server.dedicated.DedicatedServer")
abstract class DedicatedServerMixin {
    @Inject(method = "initServer", at = @At("HEAD"))
    private void examplemod$onInitServer(CallbackInfoReturnable<Boolean> cir) {
        ExampleMod.log("DedicatedServer.initServer() is starting (this line comes from a Mixin).");
    }
}
