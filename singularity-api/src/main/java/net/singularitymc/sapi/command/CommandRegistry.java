package net.singularitymc.sapi.command;

import java.util.Collection;
import java.util.Optional;

/**
 * The commands of one mod or plugin. Commands become Minecraft (Brigadier) commands with one greedy argument, so
 * they work in chat, the console, command blocks and {@code /execute ... run}, and get tab completion.
 *
 * <p>Every command is also available as {@code <owner id>:<name>}. If the plain name is already taken (by Minecraft
 * or another owner), only the prefixed form is registered and a warning is logged. Commands registered while the
 * server runs are sent to online players at once.
 */
public interface CommandRegistry {
    RegisteredCommand register(CommandSpec spec, CommandExecutor executor);

    RegisteredCommand register(CommandSpec spec, CommandExecutor executor, TabCompleter completer);

    /** A command of this owner, also one declared in {@code plugin.toml} (set its executor in {@code onEnable}). */
    Optional<RegisteredCommand> get(String name);

    Collection<RegisteredCommand> all();
}
