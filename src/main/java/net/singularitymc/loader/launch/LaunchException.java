package net.singularitymc.loader.launch;

/**
 * A problem the user can fix (missing server jar, wrong Java version, broken mod...). Printed
 * without a stack trace.
 */
public final class LaunchException extends Exception {
    private static final long serialVersionUID = 1L;

    public LaunchException(String message) {
        super(message);
    }

    public LaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
