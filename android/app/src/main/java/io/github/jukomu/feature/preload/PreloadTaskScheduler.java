package io.github.jukomu.feature.preload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 按页面会话和优先级调度图片任务；被抢占的任务保留原提交顺序重新排队。
 */
final class PreloadTaskScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreloadTaskScheduler.class);

    interface Action {
        void run(Attempt attempt);
    }

    interface OnDiscard {
        void run();
    }

    private final Object lock = new Object();
    private final int concurrency;
    private final AtomicLong sequence = new AtomicLong();
    private final PriorityQueue<Task> queued = new PriorityQueue<>(
        Comparator.comparingInt((Task task) -> task.priority).reversed()
            .thenComparingLong(task -> task.sequence));
    private final Set<Task> running = new LinkedHashSet<>();
    private final Set<String> cancelledSessions = new HashSet<>();
    private final AtomicLong detachedCount = new AtomicLong();

    PreloadTaskScheduler(int concurrency) {
        this.concurrency = concurrency;
    }

    void submit(String sessionId, String scopeKey, int priority, Action action,
                Runnable onComplete, OnDiscard onDiscard) {
        List<Runnable> discarded = new ArrayList<>();
        synchronized (lock) {
            if (cancelledSessions.contains(sessionId)) {
                discarded.add(onDiscard::run);
            } else {
                queued.add(new Task(sessionId, scopeKey, priority, sequence.incrementAndGet(), action,
                    onComplete, onDiscard));
                preemptForQueuedTasks(discarded);
                startAvailable();
            }
        }
        discarded.forEach(Runnable::run);
    }

    void submit(String sessionId, int priority, Action action,
                Runnable onComplete, OnDiscard onDiscard) {
        submit(sessionId, sessionId, priority, action, onComplete, onDiscard);
    }

    void cancelSession(String sessionId) {
        synchronized (lock) {
            cancelledSessions.add(sessionId);
        }
        cancelTasks(task -> task.sessionId.equals(sessionId));
    }

    void cancelScope(String scopeKey) {
        cancelTasks(task -> task.scopeKey.equals(scopeKey));
    }

    private void cancelTasks(Predicate<Task> matches) {
        List<Runnable> discarded = new ArrayList<>();
        synchronized (lock) {
            queued.removeIf(task -> {
                if (!matches.test(task)) return false;
                discarded.add(task.onDiscard::run);
                return true;
            });
            for (Task task : new ArrayList<>(running)) {
                if (!matches.test(task)) continue;
                Attempt attempt = task.attempt;
                task.attempt = null;
                running.remove(task);
                cancelAttempt(task, attempt);
                discarded.add(task.onDiscard::run);
            }
            startAvailable();
        }
        discarded.forEach(Runnable::run);
    }

    private void preemptForQueuedTasks(List<Runnable> discarded) {
        while (!queued.isEmpty() && running.size() >= concurrency) {
            Task next = queued.peek();
            Task lowest = running.stream()
                .min(Comparator.comparingInt((Task task) -> task.priority)
                    .thenComparingLong(task -> -task.sequence))
                .orElseThrow();
            if (next.priority <= lowest.priority) return;

            running.remove(lowest);
            Attempt attempt = lowest.attempt;
            lowest.attempt = null;
            cancelAttempt(lowest, attempt);
            if (cancelledSessions.contains(lowest.sessionId)) {
                discarded.add(lowest.onDiscard::run);
            } else {
                queued.add(lowest);
            }
        }
    }

    private void startAvailable() {
        while (running.size() < concurrency && !queued.isEmpty()) {
            Task task = queued.remove();
            if (cancelledSessions.contains(task.sessionId)) {
                task.onDiscard.run();
                continue;
            }
            Attempt attempt = new Attempt();
            task.attempt = attempt;
            running.add(task);
            attempt.thread = new Thread(() -> {
                try {
                    task.action.run(attempt);
                } finally {
                    complete(task, attempt);
                }
            }, "jq-viewer-image-preload-" + task.sequence);
            attempt.thread.start();
        }
    }

    private void complete(Task task, Attempt attempt) {
        synchronized (lock) {
            if (task.attempt != attempt) {
                long detachedAtNanos = attempt.detachedAtNanos;
                if (detachedAtNanos != 0L) {
                    long remaining = detachedCount.decrementAndGet();
                    LOGGER.info("图片预载 detached 线程结束: taskSequence={}, durationMs={}, detached={}",
                        task.sequence,
                        (System.nanoTime() - detachedAtNanos) / 1_000_000L,
                        remaining);
                }
                return;
            }
            task.attempt = null;
            running.remove(task);
            task.onComplete.run();
            startAvailable();
        }
    }

    private void cancelAttempt(Task task, Attempt attempt) {
        Thread worker = attempt.thread;
        if (worker != null && worker.isAlive() && attempt.markDetached()) {
            long count = detachedCount.incrementAndGet();
            LOGGER.info("图片预载线程被抢占或取消: taskSequence={}, detached={}",
                task.sequence, count);
        }
        attempt.cancel();
    }

    private static final class Task {
        private final String sessionId;
        private final String scopeKey;
        private final int priority;
        private final long sequence;
        private final Action action;
        private final Runnable onComplete;
        private final OnDiscard onDiscard;
        private Attempt attempt;

        private Task(String sessionId, String scopeKey, int priority, long sequence,
                     Action action, Runnable onComplete, OnDiscard onDiscard) {
            this.sessionId = sessionId;
            this.scopeKey = scopeKey;
            this.priority = priority;
            this.sequence = sequence;
            this.action = action;
            this.onComplete = onComplete;
            this.onDiscard = onDiscard;
        }
    }

    static final class Attempt {
        private boolean cancelled;
        private volatile Thread thread;
        private volatile NetworkLoadGate.Permit networkPermit;
        private volatile long detachedAtNanos;

        synchronized boolean isCancelled() {
            return cancelled;
        }

        void setNetworkPermit(NetworkLoadGate.Permit permit) {
            boolean close;
            synchronized (this) {
                networkPermit = permit;
                close = cancelled;
            }
            if (close) permit.close();
        }

        synchronized void runIfActive(Runnable action) {
            if (!cancelled) action.run();
        }

        synchronized boolean markDetached() {
            if (detachedAtNanos != 0L) return false;
            detachedAtNanos = System.nanoTime();
            return true;
        }

        private void cancel() {
            NetworkLoadGate.Permit permit;
            Thread worker;
            synchronized (this) {
                cancelled = true;
                permit = networkPermit;
                worker = thread;
            }
            if (permit != null) permit.close();
            if (worker != null) worker.interrupt();
        }
    }
}
