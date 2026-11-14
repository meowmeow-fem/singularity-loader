package net.singularitymc.sapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.singularitymc.sapi.scheduler.Scheduler;
import net.singularitymc.sapi.scheduler.Task;
import org.junit.jupiter.api.Test;

class SchedulerImplTest {
    private final SchedulerImpl impl = new SchedulerImpl();
    private final Scheduler s = impl.forOwner("test", new LogAdapter("test"));

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            impl.tick();
        }
    }

    @Test
    void delaysAndTimersCountTicks() {
        List<String> log = new ArrayList<>();
        s.runTask(() -> log.add("next@" + impl.currentTick()));
        s.runLater(0, () -> log.add("zero@" + impl.currentTick()));
        s.runLater(5, () -> log.add("five@" + impl.currentTick()));
        Task timer = s.runTimer(2, 3, () -> log.add("timer@" + impl.currentTick()));
        ticks(9);
        timer.cancel();
        ticks(5);
        assertEquals(List.of("next@1", "zero@1", "timer@2", "five@5", "timer@5", "timer@8"), log);
        assertTrue(timer.isCancelled());
    }

    @Test
    void aFailingTaskDoesNotStopItsTimer() {
        int[] runs = {0};
        s.runTimer(1, 1, () -> {
            runs[0]++;
            throw new IllegalStateException("boom (expected in this test)");
        });
        ticks(3);
        assertEquals(3, runs[0]);
    }

    @Test
    void cancelOwnerStopsEverythingOfThatOwnerOnly() {
        Scheduler other = impl.forOwner("other", new LogAdapter("other"));
        int[] mine = {0};
        int[] theirs = {0};
        s.runTimer(1, 1, () -> mine[0]++);
        other.runTimer(1, 1, () -> theirs[0]++);
        CompletableFuture<String> pending = s.callSync(() -> "never");
        impl.cancelOwner("test");
        ticks(3);
        assertEquals(0, mine[0]);
        assertEquals(3, theirs[0]);
        assertTrue(pending.isCancelled());
        assertEquals(0, impl.pending("test"));
    }

    @Test
    void asyncAndCallSync() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        String[] thread = new String[1];
        s.runAsync(() -> {
            thread[0] = Thread.currentThread().getName();
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(thread[0].startsWith("Singularity-Async-"), thread[0]);

        CompletableFuture<Integer> f = s.callSync(() -> 6 * 7);
        ticks(1);
        assertEquals(42, f.get(1, TimeUnit.SECONDS));
        impl.shutdown();
    }
}
