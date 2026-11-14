package net.singularitymc.sapi.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.singularitymc.sapi.command.CommandException;
import net.singularitymc.sapi.command.CommandRegistry;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.command.CommandSpec;
import org.junit.jupiter.api.Test;

class CommandRegistryImplTest {
    private final CommandRegistryImpl impl = new CommandRegistryImpl();
    private final CommandRegistry mine = impl.forOwner("mine", new LogAdapter("mine"));

    /** A sender that records messages and has the given nodes. */
    static final class TestSender implements CommandSender {
        final List<String> messages = new ArrayList<>();
        final Set<String> nodes;

        TestSender(String... nodes) {
            this.nodes = Set.of(nodes);
        }

        @Override public String name() { return "Tester"; }
        @Override public void sendMessage(String message) { messages.add(message); }
        @Override public boolean hasPermission(String node) { return nodes.contains(node); }
        @Override public boolean isOp() { return false; }
        @Override public boolean isConsole() { return false; }
    }

    private CommandRegistryImpl.Entry entry(String name) {
        return impl.entries().stream().filter(e -> e.spec().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void splitsArguments() {
        assertArrayEquals(new String[] {}, Arguments.split(""));
        assertArrayEquals(new String[] {"a", "b"}, Arguments.split("  a   b "));
        assertArrayEquals(new String[] {""}, Arguments.splitForCompletion(""));
        assertArrayEquals(new String[] {"a", ""}, Arguments.splitForCompletion("a "));
        assertArrayEquals(new String[] {"a", "b"}, Arguments.splitForCompletion("a b"));
    }

    @Test
    void dispatchRunsWithArgsAndShowsUsage() {
        List<String> got = new ArrayList<>();
        mine.register(CommandSpec.of("hello").usage("/hello <name>"), (sender, label, args) -> {
            got.add(label + ":" + String.join(",", args));
            return args.length == 1;
        });
        TestSender s = new TestSender();
        assertEquals(1, CommandRegistryImpl.dispatch(entry("hello"), s, "hello", "Steve"));
        assertEquals(0, CommandRegistryImpl.dispatch(entry("hello"), s, "hi", ""));
        assertEquals(List.of("hello:Steve", "hi:"), got);
        assertEquals(List.of("§cUsage: /hello <name>"), s.messages);
    }

    @Test
    void permissionsErrorsAndDisabledOwners() {
        mine.register(CommandSpec.of("secret").permission("mine.secret"), (s, l, a) -> true);
        mine.register(CommandSpec.of("fail"), (s, l, a) -> {
            throw new CommandException("Nope.");
        });
        mine.register(CommandSpec.of("crash"), (s, l, a) -> {
            throw new IllegalStateException("bug (expected in this test)");
        });
        mine.register(CommandSpec.of("declared"), null);
        TestSender nobody = new TestSender();
        TestSender allowed = new TestSender("mine.secret");
        assertFalse(CommandRegistryImpl.canUse(entry("secret"), nobody));
        assertEquals(0, CommandRegistryImpl.dispatch(entry("secret"), nobody, "secret", ""));
        assertEquals(1, CommandRegistryImpl.dispatch(entry("secret"), allowed, "secret", ""));
        assertEquals(0, CommandRegistryImpl.dispatch(entry("fail"), nobody, "fail", ""));
        assertEquals(0, CommandRegistryImpl.dispatch(entry("crash"), nobody, "crash", ""));
        assertEquals(0, CommandRegistryImpl.dispatch(entry("declared"), nobody, "declared", ""));
        assertTrue(nobody.messages.get(0).contains("permission"));
        assertEquals("§cNope.", nobody.messages.get(1));
        assertTrue(nobody.messages.get(2).contains("internal error"));
        assertTrue(nobody.messages.get(3).contains("has not been set up"));

        impl.deactivateOwner("mine");
        assertFalse(CommandRegistryImpl.canUse(entry("secret"), allowed));
        assertEquals(0, CommandRegistryImpl.dispatch(entry("secret"), allowed, "secret", ""));
    }

    @Test
    void completionFiltersByWhatWasTyped() {
        mine.register(CommandSpec.of("give"), (s, l, a) -> true, (s, l, a) -> a.length == 1 ? List.of("Steve", "Alex", "sam") : List.of());
        TestSender s = new TestSender();
        assertEquals(List.of("Steve", "sam"), CommandRegistryImpl.complete(entry("give"), s, "give", "s"));
        assertEquals(List.of("Steve", "Alex", "sam"), CommandRegistryImpl.complete(entry("give"), s, "give", ""));
        assertEquals(List.of(), CommandRegistryImpl.complete(entry("give"), s, "give", "Steve "));
    }

    @Test
    void labelsBelongToTheFirstOwner() {
        CommandRegistry other = impl.forOwner("other", new LogAdapter("other"));
        mine.register(CommandSpec.of("home").aliases("h"), (s, l, a) -> true);
        other.register(CommandSpec.of("home"), (s, l, a) -> true);
        var first = impl.entries().get(0);
        var second = impl.entries().get(1);
        assertTrue(impl.ownsPlainLabel(first, "home"));
        assertTrue(impl.ownsPlainLabel(first, "h"));
        assertFalse(impl.ownsPlainLabel(second, "home"));
        assertThrows(IllegalArgumentException.class, () -> mine.register(CommandSpec.of("home"), (s, l, a) -> true));
        assertTrue(mine.get("h").isPresent());
        assertEquals(1, other.all().size());
    }

    @Test
    void specValidatesNames() {
        assertEquals("hello", CommandSpec.of("/Hello").name());
        assertEquals("/hello", CommandSpec.of("hello").usage());
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.of("two words"));
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.of("ns:cmd"));
    }
}
