package net.singularitymc.sapi.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.singularitymc.api.version.Version;
import net.singularitymc.sapi.internal.PluginDiscovery.Candidate;

/**
 * Checks the plugins against each other and the API version and puts them in load order: hard and soft dependencies
 * first, {@code loadbefore} after; otherwise by id. A plugin with a problem is left out, with the reason, and so is
 * every plugin that depends on it.
 */
public final class PluginResolver {
    private PluginResolver() {}

    public record Result(List<Candidate> order, Map<Candidate, String> failed) {}

    public static Result resolve(List<Candidate> candidates, Version apiVersion) {
        Map<Candidate, String> failed = new LinkedHashMap<>();
        Map<String, Candidate> byId = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String id = c.descriptor().id();
            Candidate first = byId.get(id);
            if (first != null) {
                failed.put(c, "id '" + id + "' is also used by " + first.file().getFileName() + "; only one is loaded");
                continue;
            }
            byId.put(id, c);
            if (!c.descriptor().api().matches(apiVersion)) {
                failed.put(c, "needs Singularity API " + c.descriptor().api() + ", this is " + apiVersion);
            }
        }
        // missing or failed hard dependencies, until nothing changes
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Candidate c : byId.values()) {
                if (failed.containsKey(c)) {
                    continue;
                }
                for (String dep : c.descriptor().depends()) {
                    Candidate d = byId.get(dep);
                    if (d == null) {
                        failed.put(c, "needs plugin '" + dep + "', which is not installed");
                        changed = true;
                        break;
                    }
                    if (failed.containsKey(d)) {
                        failed.put(c, "needs plugin '" + dep + "', which could not be loaded");
                        changed = true;
                        break;
                    }
                }
            }
        }
        // order: edges before -> after
        Map<String, Set<String>> after = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        List<String> ok = new ArrayList<>();
        for (Candidate c : byId.values()) {
            if (!failed.containsKey(c)) {
                ok.add(c.descriptor().id());
            }
        }
        Set<String> okSet = new HashSet<>(ok);
        for (String id : ok) {
            incoming.putIfAbsent(id, 0);
            after.putIfAbsent(id, new HashSet<>());
        }
        for (String id : ok) {
            Candidate c = byId.get(id);
            for (String dep : concat(c.descriptor().depends(), c.descriptor().softDepends())) {
                if (okSet.contains(dep) && after.get(dep).add(id)) {
                    incoming.merge(id, 1, Integer::sum);
                }
            }
            for (String later : c.descriptor().loadBefore()) {
                if (okSet.contains(later) && after.get(id).add(later)) {
                    incoming.merge(later, 1, Integer::sum);
                }
            }
        }
        TreeSet<String> ready = new TreeSet<>();
        incoming.forEach((id, n) -> {
            if (n == 0) ready.add(id);
        });
        List<Candidate> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.pollFirst();
            order.add(byId.get(id));
            for (String next : after.get(id)) {
                if (incoming.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (order.size() < ok.size()) {
            Set<String> placed = new HashSet<>();
            order.forEach(c -> placed.add(c.descriptor().id()));
            List<String> cycle = ok.stream().filter(id -> !placed.contains(id)).sorted().toList();
            for (String id : cycle) {
                failed.put(byId.get(id), "dependency cycle between " + String.join(", ", cycle));
            }
        }
        return new Result(order, failed);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
