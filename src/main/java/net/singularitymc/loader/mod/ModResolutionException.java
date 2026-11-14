package net.singularitymc.loader.mod;

import java.util.List;

/** One or more mods cannot be loaded together. Carries every problem found, not just the first. */
public final class ModResolutionException extends Exception {
    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    public ModResolutionException(List<String> problems) {
        super(buildMessage(problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() {
        return problems;
    }

    private static String buildMessage(List<String> problems) {
        StringBuilder sb = new StringBuilder("Some mods could not be loaded:");
        for (String p : problems) {
            sb.append(System.lineSeparator()).append("  - ").append(p);
        }
        return sb.toString();
    }
}
