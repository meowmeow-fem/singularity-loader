package net.singularitymc.loader.patches;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.event.CommandRegistrationEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Event: {@link CommandRegistrationEvent}, posted when the vanilla command tree is complete. */
@Mixin(Commands.class)
abstract class CommandsMixin {
    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void Singularity$commandRegistrationEvent(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        EventBus.post(new CommandRegistrationEvent(dispatcher, context, selection));
    }
}
