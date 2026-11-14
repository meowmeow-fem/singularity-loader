package net.singularitymc.sapi.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import net.singularitymc.loader.util.Log;
import net.singularitymc.sapi.Logger;

/** {@link Logger} on top of the loader's log (console + ServerLogs/Latest.log). */
public final class LogAdapter implements Logger {
    private final Log log;

    public LogAdapter(String channel) {
        this.log = Log.get(channel);
    }

    @Override public void debug(String message, Object... args) { log.debug(message, args); }
    @Override public void info(String message, Object... args) { log.info(message, args); }
    @Override public void warn(String message, Object... args) { log.warn(message, args); }
    @Override public void error(String message, Object... args) { log.error(message, args); }

    @Override
    public void error(String message, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        // the message may contain {} from user input; escape by passing it as an argument
        log.error("{}\n{}", message, trace.toString().stripTrailing());
    }
}
