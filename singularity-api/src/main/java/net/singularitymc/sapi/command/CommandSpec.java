package net.singularitymc.sapi.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a command is called and who may use it.
 *
 * <pre>{@code
 * CommandSpec.of("hello").aliases("hi").description("Says hello").usage("/hello [name]").permission("myplugin.hello")
 * }</pre>
 *
 * @param permission node needed to see and run it, or {@code null} for everyone
 */
public record CommandSpec(String name, List<String> aliases, String description, String usage, String permission) {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_\\-.]{1,64}");

    public CommandSpec {
        name = checkName(name);
        aliases = aliases == null ? List.of() : aliases.stream().map(CommandSpec::checkName).toList();
        description = description == null ? "" : description;
        usage = usage == null || usage.isBlank() ? "/" + name : usage;
        permission = permission == null || permission.isBlank() ? null : permission;
    }

    public static CommandSpec of(String name) {
        return new CommandSpec(name, List.of(), "", null, null);
    }

    public CommandSpec aliases(String... aliases) {
        return new CommandSpec(name, List.of(aliases), description, usage, permission);
    }

    public CommandSpec description(String description) {
        return new CommandSpec(name, aliases, description, usage, permission);
    }

    public CommandSpec usage(String usage) {
        return new CommandSpec(name, aliases, description, usage, permission);
    }

    public CommandSpec permission(String permission) {
        return new CommandSpec(name, aliases, description, usage, permission);
    }

    /** The name followed by the aliases. */
    public List<String> labels() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(name), aliases.stream()).toList();
    }

    private static String checkName(String n) {
        Objects.requireNonNull(n, "command name");
        String lower = n.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("/")) {
            lower = lower.substring(1);
        }
        if (!NAME.matcher(lower).matches()) {
            throw new IllegalArgumentException("'" + n + "' is not a valid command name (a-z, 0-9, _ - . only, no spaces or ':')");
        }
        return lower;
    }
}
