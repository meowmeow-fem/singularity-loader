package net.singularitymc.sapi.internal;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.singularitymc.sapi.Logger;
import net.singularitymc.sapi.scheduler.Scheduler;
import net.singularitymc.sapi.scheduler.Task;

/**
 * The server's task scheduler. {@link #tick()} runs on the server thread at the start of every tick; tasks may be
 * scheduled from any thread. Owners get their own view ({@link #forOwner}) so their tasks can be cancelled together.
 */
public final class SchedulerImpl {
    private final ConcurrentLinkedQueue<TaskImpl> incoming = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<TaskImpl> queue = new PriorityQueue<>(Comparator.comparingLong((TaskImpl t) -> t.due).thenComparingLong(t -> t.seq));
    private final ConcurrentHashMap<String, Set<TaskImpl>> byOwner = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile long currentTick;
    private volatile ExecutorService async;
    private volatile boolean shutDown;

    /** Ticks run so far. */
    public long currentTick() {
        return currentTick;
    }

    public Scheduler forOwner(String owner, Logger logger) {
        return new OwnerScheduler(owner, logger);
    }

    /** Runs every task that is due. Call once per server tick, on the server thread. */
    public void tick() {
        long now = ++currentTick;
        for (TaskImpl t; (t = incoming.poll()) != null; ) {
            t.due = Math.max(now, t.requestedAt + t.delay);
            queue.add(t);
        }
        while (!queue.isEmpty() && queue.peek().due <= now) {
            TaskImpl t = queue.poll();
            if (t.cancelled) {
                continue;
            }
            t.run();
            if (t.period > 0 && !t.cancelled) {
                t.due = now + t.period;
                queue.add(t);
            } else {
                t.finish();
            }
        }
    }

    /** Cancels every task of {@code owner}. */
    public void cancelOwner(String owner) {
        Set<TaskImpl> tasks = byOwner.remove(owner);
        if (tasks != null) {
            for (TaskImpl t : tasks) {
                t.cancelSilently();
            }
        }
    }

    /** Cancels everything and stops the background threads (server stop). */
    public void shutdown() {
        shutDown = true;
        for (String owner : byOwner.keySet()) {
            cancelOwner(owner);
        }
        ExecutorService ex = async;
        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Tasks not yet finished or cancelled (for tests and /plugins). */
    public int pending(String owner) {
        Set<TaskImpl> s = byOwner.get(owner);
        return s == null ? 0 : (int) s.stream().filter(t -> !t.cancelled).count();
    }

    private ExecutorService asyncPool() {
        ExecutorService ex = async;
        if (ex == null) {
            synchronized (this) {
                ex = async;
                if (ex == null) {
                    AtomicInteger n = new AtomicInteger();
                    ex = Executors.newCachedThreadPool(r -> {
                        Thread th = new Thread(r, "Singularity-Async-" + n.incrementAndGet());
                        th.setDaemon(true);
                        return th;
                    });
                    async = ex;
                }
            }
        }
        return ex;
    }

    private TaskImpl schedule(String owner, Logger logger, long delay, long period, Runnable body, Runnable onCancel) {
        Objects.requireNonNull(body, "task");
        if (shutDown) {
            throw new IllegalStateException("The server is stopping; no new tasks can be scheduled");
        }
        TaskImpl t = new TaskImpl(owner, logger, Math.max(1, delay), period, body, onCancel);
        t.seq = seq.incrementAndGet();
        t.requestedAt = currentTick;
        track(t);
        incoming.add(t);
        return t;
    }

    private void track(TaskImpl t) {
        byOwner.computeIfAbsent(t.owner, k -> ConcurrentHashMap.newKeySet()).add(t);
    }

    private final class TaskImpl implements Task {
        final String owner;
        final Logger logger;
        final long delay;
        final long period;
        final Runnable body;
        final Runnable onCancel;
        long seq;
        long requestedAt;
        long due;
        volatile boolean cancelled;

        TaskImpl(String owner, Logger logger, long delay, long period, Runnable body, Runnable onCancel) {
            this.owner = owner;
            this.logger = logger;
            this.delay = delay;
            this.period = period;
            this.body = body;
            this.onCancel = onCancel;
        }

        void run() {
            try {
                body.run();
            } catch (Throwable e) {
                logger.error("A task of " + owner + " failed", e);
            }
        }

        void finish() {
            Set<TaskImpl> s = byOwner.get(owner);
            if (s != null) {
                s.remove(this);
            }
        }

        void cancelSilently() {
            if (!cancelled) {
                cancelled = true;
                if (onCancel != null) {
                    onCancel.run();
                }
            }
        }

        @Override
        public void cancel() {
            cancelSilently();
            finish();
        }

        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isRepeating() { return period > 0; }
        @Override public String owner() { return owner; }
    }

    private final class OwnerScheduler implements Scheduler {
        private final String owner;
        private final Logger logger;

        OwnerScheduler(String owner, Logger logger) {
            this.owner = owner;
            this.logger = logger;
        }

        @Override
        public Task runTask(Runnable task) {
            return schedule(owner, logger, 1, 0, task, null);
        }

        @Override
        public Task runLater(long delayTicks, Runnable task) {
            if (delayTicks < 0) throw new IllegalArgumentException("delay < 0");
            return schedule(owner, logger, delayTicks, 0, task, null);
        }

        @Override
        public Task runTimer(long delayTicks, long periodTicks, Runnable task) {
            if (delayTicks < 0) throw new IllegalArgumentException("delay < 0");
            if (periodTicks < 1) throw new IllegalArgumentException("period must be at least 1 tick");
            return schedule(owner, logger, delayTicks, periodTicks, task, null);
        }

        @Override
        public Task runAsync(Runnable task) {
            Objects.requireNonNull(task, "task");
            if (shutDown) {
                throw new IllegalStateException("The server is stopping; no new tasks can be scheduled");
            }
            TaskImpl t = new TaskImpl(owner, logger, 0, 0, task, null);
            track(t);
            asyncPool().execute(() -> {
                if (!t.cancelled) {
                    t.run();
                }
                t.finish();
            });
            return t;
        }

        @Override
        public <T> CompletableFuture<T> callSync(Callable<T> call) {
            Objects.requireNonNull(call, "call");
            CompletableFuture<T> f = new CompletableFuture<>();
            schedule(owner, logger, 1, 0, () -> {
                try {
                    f.complete(call.call());
                } catch (Throwable e) {
                    f.completeExceptionally(e);
                }
            }, () -> f.cancel(false));
            return f;
        }

        @Override
        public void cancelAll() {
            cancelOwner(owner);
        }
    }
}
