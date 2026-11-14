package net.singularitymc.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventBusTest {
    static final class Ping extends CancellableEvent {}

    static final class SubPing extends CancellableEvent {}

    @Test
    void runsListenersInPriorityOrderAndSkipsCancelledExceptMonitor() {
        List<String> seen = new ArrayList<>();
        EventBus.register(Ping.class, EventPriority.MONITOR, e -> seen.add("monitor"));
        EventBus.register(Ping.class, EventPriority.HIGH, e -> seen.add("high"));
        EventBus.register(Ping.class, EventPriority.LOW, e -> {
            seen.add("low");
            e.setCancelled(true);
        });
        Ping p = EventBus.post(new Ping());
        assertTrue(p.isCancelled());
        assertEquals(List.of("low", "monitor"), seen);
    }

    @Test
    void aFailingListenerDoesNotStopTheOthers() {
        class Boom extends CancellableEvent {}
        List<String> seen = new ArrayList<>();
        EventBus.register(Boom.class, EventPriority.LOWEST, e -> {
            throw new IllegalStateException("boom");
        });
        EventBus.register(Boom.class, EventPriority.HIGHEST, e -> seen.add("ran"));
        EventBus.post(new Boom());
        assertEquals(List.of("ran"), seen);
    }

    @Test
    void unregisterRemovesTheListener() {
        List<String> seen = new ArrayList<>();
        java.util.function.Consumer<SubPing> l = e -> seen.add("x");
        EventBus.register(SubPing.class, l);
        EventBus.unregister(l);
        EventBus.post(new SubPing());
        assertEquals(List.of(), seen);
    }
}
