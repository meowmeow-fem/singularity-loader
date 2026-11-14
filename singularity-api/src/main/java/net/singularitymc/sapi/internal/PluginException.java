package net.singularitymc.sapi.internal;

import java.util.List;

/** One or more problems with a plugin. */
public final class PluginException extends Exception {
    private final List<String> problems;

    public PluginException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
