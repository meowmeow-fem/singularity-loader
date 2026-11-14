package net.singularitymc.sapi.command;

/** Runs a command. */
@FunctionalInterface
public interface CommandExecutor {
    /**
     * @param label the name or alias that was typed (without {@code /})
     * @param args  the words after it (split at spaces; never null)
     * @return {@code false} to show the command's usage to the sender
     * @throws CommandException to show its message to the sender in red
     */
    boolean execute(CommandSender sender, String label, String[] args);
}
