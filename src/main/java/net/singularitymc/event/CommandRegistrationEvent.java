package net.singularitymc.event;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Fires every time the server builds its command tree (at startup and on every {@code /reload}), after the vanilla
 * commands are registered. Add commands with Brigadier, as in Fabric's {@code CommandRegistrationCallback}:
 *
 * <pre>{@code
 * EventBus.register(CommandRegistrationEvent.class, e ->
 *     e.getDispatcher().register(Commands.literal("hello").executes(ctx -> {
 *         ctx.getSource().sendSuccess(() -> Component.literal("Hello!"), false);
 *         return 1;
 *     })));
 * }</pre>
 */
public final class CommandRegistrationEvent {
    private final CommandDispatcher<CommandSourceStack> dispatcher;
    private final CommandBuildContext registryAccess;
    private final Commands.CommandSelection environment;

    public CommandRegistrationEvent(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess,
            Commands.CommandSelection environment) {
        this.dispatcher = dispatcher;
        this.registryAccess = registryAccess;
        this.environment = environment;
    }

    public CommandDispatcher<CommandSourceStack> getDispatcher() { return dispatcher; }

    /** Registry access for arguments that need registries (items, blocks, ...). */
    public CommandBuildContext getRegistryAccess() { return registryAccess; }

    /** {@code DEDICATED}, {@code INTEGRATED} or {@code ALL}: which kind of server this command tree is for. */
    public Commands.CommandSelection getEnvironment() { return environment; }

    public boolean isDedicated() { return environment != Commands.CommandSelection.INTEGRATED; }
}
