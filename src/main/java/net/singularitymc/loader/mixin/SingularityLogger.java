package net.singularitymc.loader.mixin;

import net.singularitymc.loader.util.Log;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

/** Routes Mixin's log output through the loader's {@link Log}. */
final class SingularityLogger extends LoggerAdapterAbstract {
    private final Log log = Log.get("Mixin");

    SingularityLogger(String name) {
        super(name);
    }

    @Override
    public String getType() {
        return "Singularity";
    }

    @Override
    public void catching(Level level, Throwable t) {
        log(level, "Catching " + t.getClass().getName() + ": " + t.getMessage(), t);
    }

    @Override
    public void log(Level level, String message, Object... params) {
        log.log(map(level), message, params);
    }

    @Override
    public void log(Level level, String message, Throwable t) {
        log.log(map(level), message, t);
    }

    @Override
    public <T extends Throwable> T throwing(T t) {
        log.log(Log.Level.ERROR, "Throwing {}", t);
        return t;
    }

    private static Log.Level map(Level level) {
        return switch (level) {
            case FATAL, ERROR -> Log.Level.ERROR;
            case WARN -> Log.Level.WARN;
            case INFO -> Log.Level.INFO;
            case DEBUG, TRACE -> Log.Level.DEBUG;
        };
    }
}
