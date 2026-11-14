package net.singularitymc.loader.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.singularitymc.loader.util.Log;

/** Creates and invokes the entrypoints declared in {@code singularity.mod.json}. */
public final class EntrypointManager {
    private static final Log LOG = Log.get("Singularity");

    private record Holder(ModContainerImpl mod, String className) {}

    private final ClassLoader loader;
    private final Map<String, List<Holder>> byKey = new LinkedHashMap<>();
    private final Map<String, Object> instances = new HashMap<>();

    /** @param mods the mods in load order */
    public EntrypointManager(ClassLoader loader, List<ModContainerImpl> mods) {
        this.loader = loader;
        for (ModContainerImpl mod : mods) {
            mod.getMetadataImpl().getEntrypoints().forEach((key, classes) -> {
                for (String className : classes) {
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new Holder(mod, className));
                }
            });
        }
    }

    /** Creates every entrypoint of {@code key} that implements {@code type}. */
    public <T> List<T> create(String key, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Holder holder : byKey.getOrDefault(key, List.of())) {
            out.add(instantiate(key, type, holder));
        }
        return out;
    }

    /** Creates each entrypoint of {@code key} and passes it to {@code action}, stopping at the first failure. */
    public <T> void invoke(String key, Class<T> type, Consumer<T> action) {
        for (Holder holder : byKey.getOrDefault(key, List.of())) {
            T instance = instantiate(key, type, holder);
            LOG.debug("Invoking '{}' entrypoint {} of mod '{}'", key, holder.className(), holder.mod().getId());
            try {
                action.accept(instance);
            } catch (Throwable t) {
                throw new EntrypointException(holder.mod().getId(), describe(key, holder) + " threw " + t, t);
            }
        }
    }

    public int count(String key) {
        return byKey.getOrDefault(key, List.of()).size();
    }

    private <T> T instantiate(String key, Class<T> type, Holder holder) {
        Object instance;
        synchronized (instances) {
            instance = instances.get(holder.className());
            if (instance == null) {
                try {
                    Class<?> c = Class.forName(holder.className(), true, loader);
                    instance = c.getDeclaredConstructor().newInstance();
                } catch (ClassNotFoundException e) {
                    throw new EntrypointException(holder.mod().getId(), describe(key, holder) + " was not found in the mod's jar", e);
                } catch (NoSuchMethodException e) {
                    throw new EntrypointException(holder.mod().getId(), describe(key, holder) + " needs a public constructor without parameters", e);
                } catch (ReflectiveOperationException | LinkageError e) {
                    throw new EntrypointException(holder.mod().getId(), describe(key, holder) + " could not be created: " + e, e);
                }
                instances.put(holder.className(), instance);
            }
        }
        if (!type.isInstance(instance)) {
            throw new EntrypointException(holder.mod().getId(),
                    describe(key, holder) + " must implement " + type.getName(), null);
        }
        return type.cast(instance);
    }

    private static String describe(String key, Holder holder) {
        return "Entrypoint '" + key + "' (" + holder.className() + ") of mod '" + holder.mod().getId() + "'";
    }
}
