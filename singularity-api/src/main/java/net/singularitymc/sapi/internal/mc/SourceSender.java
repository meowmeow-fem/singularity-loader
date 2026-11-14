package net.singularitymc.sapi.internal.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.internal.ApiRuntime;

/** A command source that is not a player: the console (also RCON) or a command block / function. */
final class SourceSender implements CommandSender {
    private final CommandSourceStack source;
    private final boolean console;

    SourceSender(CommandSourceStack source, boolean console) {
        this.source = source;
        this.console = console;
    }

    /** The sender for a Brigadier source: the player if a player runs it (also via /execute as), else this. */
    static CommandSender of(CommandSourceStack source) {
        ServerPlayer p = source.getPlayer();
        if (p != null) {
            return PlayerImpl.of(p);
        }
        boolean console = source.getEntity() == null && source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
        return new SourceSender(source, console);
    }

    @Override
    public String name() {
        return source.getTextName();
    }

    @Override
    public void sendMessage(String message) {
        source.sendSystemMessage(Component.literal(message));
    }

    @Override
    public boolean hasPermission(String node) {
        return console || ApiRuntime.get().permissions().defaultOf(node).test(isOp());
    }

    @Override
    public boolean isOp() {
        return console || source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public boolean isConsole() {
        return console;
    }
}
