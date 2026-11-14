package net.singularitymc.sapi.internal.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.event.CommandRegistrationEvent;
import net.singularitymc.sapi.internal.ApiRuntime;
import net.singularitymc.sapi.internal.CommandRegistryImpl;
import net.singularitymc.sapi.internal.CommandRegistryImpl.Entry;

/**
 * Puts the API commands into Minecraft's command tree: {@code /<name>} and {@code /<owner>:<name>} with one greedy
 * string argument. The tree is rebuilt on start and on {@code /reload} (the loader's {@link CommandRegistrationEvent});
 * commands registered while the server runs are added to the live tree and sent to online players.
 */
final class BrigadierBridge {
    /** Literal names this bridge added to each dispatcher (a dispatcher is replaced on /reload). */
    private static final Map<CommandDispatcher<CommandSourceStack>, Set<String>> OURS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    private BrigadierBridge() {}

    static void install(ApiRuntime runtime, McBackend backend) {
        EventBus.register(CommandRegistrationEvent.class, e -> {
            for (Entry entry : runtime.commands().entries()) {
                add(runtime.commands(), e.getDispatcher(), entry);
            }
        });
        runtime.commands().onAdd(entry -> {
            MinecraftServer server = backend.minecraft();
            if (server != null) {
                Runnable add = () -> {
                    add(runtime.commands(), server.getCommands().getDispatcher(), entry);
                    resend(server);
                };
                if (server.isSameThread()) {
                    add.run();
                } else {
                    server.execute(add);
                }
            }
        });
    }

    /** Makes sure the live tree has every command (some may have been registered between the tree build and the start). */
    static void sync(ApiRuntime runtime, MinecraftServer server) {
        CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
        for (Entry entry : runtime.commands().entries()) {
            add(runtime.commands(), d, entry);
        }
    }

    private static void add(CommandRegistryImpl registry, CommandDispatcher<CommandSourceStack> d, Entry entry) {
        Set<String> ours = OURS.computeIfAbsent(d, k -> Collections.synchronizedSet(new HashSet<>()));
        for (String label : entry.spec().labels()) {
            String prefixed = entry.owner() + ":" + label;
            if (registry.ownsPlainLabel(entry, label)) {
                if (d.getRoot().getChild(label) == null || ours.contains(label)) {
                    if (ours.add(label)) {
                        d.register(node(label, entry));
                    }
                } else if (WARNED.add(label)) {
                    ApiRuntime.get().log().warn("/{} of {} is already a Minecraft or mod command; use /{}", label, entry.owner(), prefixed);
                }
            }
            if (ours.add(prefixed)) {
                d.register(node(prefixed, entry));
            }
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> node(String label, Entry entry) {
        return Commands.literal(label)
                .requires(src -> CommandRegistryImpl.canUse(entry, SourceSender.of(src)))
                .executes(ctx -> CommandRegistryImpl.dispatch(entry, SourceSender.of(ctx.getSource()), label, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> suggest(entry, ctx.getSource(), label, builder))
                        .executes(ctx -> CommandRegistryImpl.dispatch(entry, SourceSender.of(ctx.getSource()), label,
                                StringArgumentType.getString(ctx, "args"))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(Entry entry,
            CommandSourceStack source, String label, SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        SuggestionsBuilder last = builder.createOffset(builder.getStart() + typed.lastIndexOf(' ') + 1);
        for (String s : CommandRegistryImpl.complete(entry, SourceSender.of(source), label, typed)) {
            last.suggest(s);
        }
        return last.buildFuture();
    }

    private static void resend(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(p);
        }
    }
}
