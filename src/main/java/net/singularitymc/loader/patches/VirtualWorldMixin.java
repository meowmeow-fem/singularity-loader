package net.singularitymc.loader.patches;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Set;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.singularitymc.loader.util.FarLandsStages;
import net.singularitymc.loader.util.PatchSettings;
import net.singularitymc.loader.util.VirtualWindows;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The virtual 64-bit world (docs/FARLANDS.md): {@code /vtp <x> <z>} with 64-bit coordinates, {@code /vpos}, the
 * position in the action bar inside a window, and loading the windows with the world.
 */
abstract class VirtualWorldMixin {
    private VirtualWorldMixin() {}

    @Mixin(Commands.class)
    abstract static class Command {
        @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void Singularity$virtualCommands(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
            dispatcher.register(Commands.literal("vtp").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("x", LongArgumentType.longArg())
                    .then(Commands.argument("z", LongArgumentType.longArg())
                        .executes(c -> Singularity$teleport(c.getSource(), LongArgumentType.getLong(c, "x"), LongArgumentType.getLong(c, "z"))))));
            dispatcher.register(Commands.literal("vpos").executes(c -> Singularity$position(c.getSource())));
        }

        private static int Singularity$teleport(CommandSourceStack source, long x, long z) throws CommandSyntaxException {
            ServerPlayer player = source.getPlayerOrException();
            int limit = PatchSettings.limit();
            int realX;
            int realZ;
            if (x > -limit && x < limit && z > -limit && z < limit) {
                realX = (int) x;
                realZ = (int) z;
            } else {
                VirtualWindows.Window w = VirtualWindows.windowFor(x, z, limit);
                if (w == null) {
                    source.sendFailure(Component.literal("No real space left for a virtual window. Windows need max_coordinate of at least "
                        + 3 * VirtualWindows.SIZE + "; each one takes a 16,777,216-block square in the far -X/-Z corner."));
                    return 0;
                }
                realX = (int) (w.realX() + (x - w.virtualX()));
                realZ = (int) (w.realZ() + (z - w.virtualZ()));
            }
            player.teleportTo(player.level(), realX + 0.5, 140.0, realZ + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
            source.sendSuccess(() -> Component.literal("Teleported to virtual " + FarLandsStages.describe(x, z) + ", real position " + realX + ", " + realZ), true);
            return 1;
        }

        private static int Singularity$position(CommandSourceStack source) throws CommandSyntaxException {
            ServerPlayer player = source.getPlayerOrException();
            int rx = player.getBlockX();
            int rz = player.getBlockZ();
            VirtualWindows.Window w = VirtualWindows.atReal(rx, rz);
            long vx = w == null ? rx : w.virtualX(rx);
            long vz = w == null ? rz : w.virtualZ(rz);
            source.sendSuccess(() -> Component.literal((w == null ? "Real world: " : "Virtual world: ") + FarLandsStages.describe(vx, vz)), false);
            return 1;
        }
    }

    @Mixin(MinecraftServer.class)
    abstract static class Load {
        @Inject(method = "createLevels", at = @At("HEAD"), require = 0)
        private void Singularity$loadWindows(CallbackInfo ci) {
            VirtualWindows.load(((MinecraftServer) (Object) this).getWorldPath(LevelResource.ROOT));
        }
    }

    @Mixin(ServerPlayer.class)
    abstract static class Hud {
        @Inject(method = "tick", at = @At("RETURN"), require = 0)
        private void Singularity$showVirtualPosition(CallbackInfo ci) {
            ServerPlayer self = (ServerPlayer) (Object) this;
            if (self.tickCount % 20 != 0) {
                return;
            }
            VirtualWindows.Window w = VirtualWindows.atReal(self.getBlockX(), self.getBlockZ());
            if (w != null) {
                self.sendSystemMessage(Component.literal(FarLandsStages.describe(w.virtualX(self.getBlockX()), w.virtualZ(self.getBlockZ()))), true);
            }
        }
    }
}
