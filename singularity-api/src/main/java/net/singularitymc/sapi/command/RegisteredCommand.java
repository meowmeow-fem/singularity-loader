package net.singularitymc.sapi.command;

/** A command known to the server. */
public interface RegisteredCommand {
    CommandSpec spec();

    /** Id of the mod or plugin it belongs to. */
    String owner();

    void setExecutor(CommandExecutor executor);

    void setCompleter(TabCompleter completer);
}
