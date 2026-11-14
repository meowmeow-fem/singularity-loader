package net.singularitymc.sapi.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.singularitymc.sapi.Logger;
import net.singularitymc.sapi.command.CommandException;
import net.singularitymc.sapi.command.CommandExecutor;
import net.singularitymc.sapi.command.CommandRegistry;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.command.CommandSpec;
import net.singularitymc.sapi.command.RegisteredCommand;
import net.singularitymc.sapi.command.TabCompleter;

/**
 * Every API command of the server, without Minecraft types: dispatching, permissions and completion happen here; the
 * Brigadier side ({@code internal.mc.BrigadierBridge}) only forwards. Owners see their own commands through
 * {@link #forOwner}.
 */
public final class CommandRegistryImpl {
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    /** Plain labels (names and aliases) to the entry that got them first. */
    private final Map<String, Entry> plain = new ConcurrentHashMap<>();
    private final List<Consumer<Entry>> addListeners = new CopyOnWriteArrayList<>();

    /** Called for every command registered from now on (the Brigadier bridge adds it to the live tree). */
    public void onAdd(Consumer<Entry> listener) {
        addListeners.add(listener);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Whether {@code label} (a name or an alias) of {@code entry} gets the plain, unprefixed form. */
    public boolean ownsPlainLabel(Entry entry, String label) {
        return plain.get(label) == entry;
    }

    public CommandRegistry forOwner(String owner, Logger logger) {
        return new OwnerRegistry(owner, logger);
    }

    /** Hides and disables every command of {@code owner} (Brigadier nodes cannot be removed; they stay but refuse). */
    public void deactivateOwner(String owner) {
        for (Entry e : entries) {
            if (e.owner.equals(owner)) {
                e.active = false;
            }
        }
    }

    Entry add(String owner, Logger logger, CommandSpec spec, CommandExecutor executor, TabCompleter completer) {
        Objects.requireNonNull(spec, "spec");
        for (Entry e : entries) {
            if (e.owner.equals(owner) && e.spec.name().equals(spec.name())) {
                throw new IllegalArgumentException(owner + " already has a command named '" + spec.name() + "'");
            }
        }
        Entry e = new Entry(owner, logger, spec, executor, completer);
        entries.add(e);
        for (String label : spec.labels()) {
            Entry first = plain.putIfAbsent(label, e);
            if (first != null && first != e) {
                logger.warn("/{} of {} is already used by {}; it is only available as /{}:{}", label, owner, first.owner,
                        owner, label);
            }
        }
        for (Consumer<Entry> l : addListeners) {
            l.accept(e);
        }
        return e;
    }

    /** Whether {@code sender} may see and use the command. */
    public static boolean canUse(Entry e, CommandSender sender) {
        return e.active && (e.spec.permission() == null || sender.hasPermission(e.spec.permission()));
    }

    /**
     * Runs a command.
     *
     * @param argLine everything after the label ({@code ""} if nothing)
     * @return 1 on success, 0 otherwise (the sender has been told why)
     */
    public static int dispatch(Entry e, CommandSender sender, String label, String argLine) {
        if (!e.active) {
            sender.sendMessage("§cThis command is not available: " + e.owner + " is disabled.");
            return 0;
        }
        if (e.spec.permission() != null && !sender.hasPermission(e.spec.permission())) {
            sender.sendMessage("§cYou do not have permission to use /" + label + ".");
            return 0;
        }
        CommandExecutor exec = e.executor;
        if (exec == null) {
            sender.sendMessage("§c/" + label + " has not been set up by " + e.owner + ".");
            return 0;
        }
        try {
            if (exec.execute(sender, label, Arguments.split(argLine))) {
                return 1;
            }
            sender.sendMessage("§cUsage: " + e.spec.usage());
            return 0;
        } catch (CommandException ce) {
            sender.sendMessage("§c" + ce.getMessage());
            return 0;
        } catch (Throwable t) {
            e.logger.error("/" + label + " of " + e.owner + " failed (sent by " + sender.name() + ")", t);
            sender.sendMessage("§cAn internal error occurred while running /" + label + ".");
            return 0;
        }
    }

    /** Suggestions for the last word of {@code argLine}, filtered by what was typed (case-insensitive). */
    public static List<String> complete(Entry e, CommandSender sender, String label, String argLine) {
        TabCompleter c = e.completer;
        if (c == null || !canUse(e, sender)) {
            return List.of();
        }
        String[] args = Arguments.splitForCompletion(argLine);
        List<String> raw;
        try {
            raw = c.complete(sender, label, args);
        } catch (Throwable t) {
            e.logger.error("Completion of /" + label + " of " + e.owner + " failed", t);
            return List.of();
        }
        if (raw == null) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s != null && s.toLowerCase(Locale.ROOT).startsWith(typed)) {
                out.add(s);
            }
        }
        return out;
    }

    /** One registered command. */
    public static final class Entry implements RegisteredCommand {
        final String owner;
        final Logger logger;
        final CommandSpec spec;
        volatile CommandExecutor executor;
        volatile TabCompleter completer;
        volatile boolean active = true;

        Entry(String owner, Logger logger, CommandSpec spec, CommandExecutor executor, TabCompleter completer) {
            this.owner = owner;
            this.logger = logger;
            this.spec = spec;
            this.executor = executor;
            this.completer = completer;
        }

        @Override public CommandSpec spec() { return spec; }
        @Override public String owner() { return owner; }
        @Override public void setExecutor(CommandExecutor executor) { this.executor = executor; }
        @Override public void setCompleter(TabCompleter completer) { this.completer = completer; }

        public boolean isActive() {
            return active;
        }
    }

    private final class OwnerRegistry implements CommandRegistry {
        private final String owner;
        private final Logger logger;

        OwnerRegistry(String owner, Logger logger) {
            this.owner = owner;
            this.logger = logger;
        }

        @Override
        public RegisteredCommand register(CommandSpec spec, CommandExecutor executor) {
            return register(spec, executor, null);
        }

        @Override
        public RegisteredCommand register(CommandSpec spec, CommandExecutor executor, TabCompleter completer) {
            return add(owner, logger, spec, executor, completer);
        }

        @Override
        public Optional<RegisteredCommand> get(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            for (Entry e : entries) {
                if (e.owner.equals(owner) && e.spec.labels().contains(n)) {
                    return Optional.of(e);
                }
            }
            return Optional.empty();
        }

        @Override
        public Collection<RegisteredCommand> all() {
            List<RegisteredCommand> out = new ArrayList<>();
            for (Entry e : entries) {
                if (e.owner.equals(owner)) {
                    out.add(e);
                }
            }
            return out;
        }
    }
}
