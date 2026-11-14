package net.singularitymc.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.util.AbortableIterationConsumer;

/**
 * Calls whose bytecode differs between the Minecraft versions Singularity supports (26.1 to 26.3), bound once to the
 * running game. Patches and loader code call these instead of the game method, so one compiled jar links against
 * every version.
 *
 * <p>Loaded by the game class loader (this package is not parent-first), so the game's classes are the ones it sees.
 */
public final class GameShims {
    private GameShims() {}

    /**
     * {@code consumer.accept(value).shouldAbort()}. {@code accept} returns {@code net.minecraft.util.Continuation} from
     * 26.3 on and {@code AbortableIterationConsumer.Continuation} in 26.1 and 26.2; both have {@code shouldAbort()}.
     */
    private static final MethodHandle ACCEPT_ABORTS;

    static {
        // Found by shape, not by name, so this also works where names are remapped (the 1.21.11 Fabric client build):
        // accept is the interface's only abstract method, shouldAbort the enum's only public boolean() instance method.
        Method accept = null;
        for (Method m : AbortableIterationConsumer.class.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) && m.getParameterCount() == 1) accept = m;
        }
        Method abort = null;
        if (accept != null) {
            for (Method m : accept.getReturnType().getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isPublic(mod) && !Modifier.isStatic(mod) && m.getParameterCount() == 0 && m.getReturnType() == boolean.class) abort = m;
            }
        }
        if (accept == null || abort == null) {
            throw new ExceptionInInitializerError("AbortableIterationConsumer.accept / shouldAbort not found in this Minecraft version");
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            ACCEPT_ABORTS = MethodHandles.filterReturnValue(lookup.unreflect(accept), lookup.unreflect(abort))
                .asType(MethodType.methodType(boolean.class, AbortableIterationConsumer.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** {@code consumer.accept(value).shouldAbort()} on any supported version. */
    public static boolean acceptAborts(AbortableIterationConsumer<?> consumer, Object value) {
        try {
            return (boolean) ACCEPT_ABORTS.invokeExact(consumer, value);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** True if the running game has the class (looked up without initialising it). */
    public static boolean hasClass(String name) {
        try {
            Class.forName(name, false, GameShims.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
