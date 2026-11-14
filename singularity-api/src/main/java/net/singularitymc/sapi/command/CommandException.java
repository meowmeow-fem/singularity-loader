package net.singularitymc.sapi.command;

/** Thrown by a command to tell the sender what went wrong; the message is shown in red, no stack trace is logged. */
public class CommandException extends RuntimeException {
    public CommandException(String message) {
        super(message);
    }
}
