package net.singularitymc.loader.mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import net.singularitymc.api.Environment;
import net.singularitymc.api.mod.ModDependency;
import net.singularitymc.loader.util.Log;

/**
 * Decides which mods are loaded, checks their relationships and computes the load order.
 *
 * <p>Rules: mods whose {@code environment} does not match are skipped; if the same id appears more
 * than once the highest version wins; every {@code depends} must be satisfied and no {@code breaks}
 * may match, otherwise resolution fails listing <em>all</em> problems; dependencies load before
 * their dependants.
 */
public final class ModResolver {
    private static final Log LOG = Log.get("Singularity");

    private ModResolver() {}

    /**
     * @param candidates mods found on disk
     * @param builtins   pseudo mods (loader, game, Java) that dependencies may refer to
     * @param env        the running environment
     * @return the mods to load, in load order (builtins are not included)
     * @throws ModResolutionException if the set of mods is not loadable
     */
    public static List<ModContainerImpl> resolve(List<ModCandidate> candidates, List<ModContainerImpl> builtins, Environment env)
            throws ModResolutionException {
        List<String> problems = new ArrayList<>();

        // 1. Environment filter and duplicate handling
        Map<String, ModCandidate> chosen = new TreeMap<>();
        for (ModCandidate c : candidates) {
            String id = c.metadata().getId();
            if (!c.metadata().getEnvironment().matches(env)) {
                LOG.info("Skipping {} ({}): it is for the {} only", c.metadata(), c.path().getFileName(), c.metadata().getEnvironment().name().toLowerCase(java.util.Locale.ROOT));
                continue;
            }
            ModCandidate existing = chosen.get(id);
            if (existing == null) {
                chosen.put(id, c);
            } else if (c.metadata().getVersion().compareTo(existing.metadata().getVersion()) > 0) {
                LOG.warn("Mod '{}' is installed twice ({} and {}); using the newer {}", id, existing.path().getFileName(), c.path().getFileName(), c.path().getFileName());
                chosen.put(id, c);
            } else {
                LOG.warn("Mod '{}' is installed twice ({} and {}); using {}", id, existing.path().getFileName(), c.path().getFileName(), existing.path().getFileName());
            }
        }

        Map<String, ModContainerImpl> all = new LinkedHashMap<>();
        for (ModContainerImpl b : builtins) {
            all.put(b.getId(), b);
        }
        Map<String, ModContainerImpl> mods = new TreeMap<>();
        for (ModCandidate c : chosen.values()) {
            ModContainerImpl container = new ModContainerImpl(c.metadata(), c.path(), false);
            if (all.containsKey(container.getId())) {
                problems.add("Mod file " + c.path().getFileName() + " uses the id '" + container.getId() + "', which is reserved for a built-in component.");
                continue;
            }
            mods.put(container.getId(), container);
            all.put(container.getId(), container);
        }

        // 2. Relationship checks
        for (ModContainerImpl mod : mods.values()) {
            String who = "Mod '" + mod.getId() + "' (" + mod.getMetadata().getVersion() + ")";
            for (ModDependency dep : mod.getMetadata().getDependencies()) {
                ModContainerImpl other = all.get(dep.modId());
                boolean present = other != null;
                boolean matches = present && dep.matches(other.getMetadata().getVersion());
                switch (dep.kind()) {
                    case DEPENDS -> {
                        if (!present) {
                            problems.add(who + " requires '" + dep.modId() + "' " + dep.range() + ", which is not installed.");
                        } else if (!matches) {
                            problems.add(who + " requires '" + dep.modId() + "' " + dep.range() + ", but version " + other.getMetadata().getVersion() + " is installed.");
                        }
                    }
                    case BREAKS -> {
                        if (matches) {
                            problems.add(who + " is incompatible with '" + dep.modId() + "' " + other.getMetadata().getVersion() + " (it breaks " + dep.range() + "). Remove or update one of them.");
                        }
                    }
                    case CONFLICTS -> {
                        if (matches) {
                            LOG.warn("{} may not work together with '{}' {} (conflicts with {}).", who, dep.modId(), other.getMetadata().getVersion(), dep.range());
                        }
                    }
                    case RECOMMENDS -> {
                        if (!matches) {
                            LOG.warn("{} recommends '{}' {}{}.", who, dep.modId(), dep.range(), present ? " (found " + other.getMetadata().getVersion() + ")" : " (not installed)");
                        }
                    }
                    case SUGGESTS -> {
                        if (!matches) {
                            LOG.debug("{} suggests '{}' {}.", who, dep.modId(), dep.range());
                        }
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ModResolutionException(problems);
        }

        // 3. Load order: dependencies (and present recommended/suggested mods) first; ties by id.
        Map<String, Integer> pending = new HashMap<>();
        Map<String, List<String>> dependants = new HashMap<>();
        for (ModContainerImpl mod : mods.values()) {
            pending.putIfAbsent(mod.getId(), 0);
            for (ModDependency dep : mod.getMetadata().getDependencies()) {
                boolean orders = dep.kind() == ModDependency.Kind.DEPENDS
                        || dep.kind() == ModDependency.Kind.RECOMMENDS
                        || dep.kind() == ModDependency.Kind.SUGGESTS;
                if (orders && mods.containsKey(dep.modId())) {
                    pending.merge(mod.getId(), 1, Integer::sum);
                    dependants.computeIfAbsent(dep.modId(), k -> new ArrayList<>()).add(mod.getId());
                }
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.naturalOrder());
        pending.forEach((id, n) -> {
            if (n == 0) {
                ready.add(id);
            }
        });
        List<ModContainerImpl> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            ordered.add(mods.get(id));
            for (String dependant : dependants.getOrDefault(id, List.of())) {
                if (pending.merge(dependant, -1, Integer::sum) == 0) {
                    ready.add(dependant);
                }
            }
        }
        if (ordered.size() != mods.size()) {
            List<String> cycle = new ArrayList<>();
            pending.forEach((id, n) -> {
                if (n > 0) {
                    cycle.add(id);
                }
            });
            cycle.sort(Comparator.naturalOrder());
            throw new ModResolutionException(List.of("These mods depend on each other in a cycle: " + String.join(", ", cycle)));
        }
        return ordered;
    }
}
