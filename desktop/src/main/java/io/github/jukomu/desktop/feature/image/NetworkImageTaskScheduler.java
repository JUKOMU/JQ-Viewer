package io.github.jukomu.desktop.feature.image;

import io.github.jukomu.jmcomic.api.model.JmImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Desktop 图片网络请求合并器。已开始的物理请求继续完成，逻辑槽位可被高优先级任务复用。
 */
final class NetworkImageTaskScheduler {
    private final Object lock = new Object();
    private final int concurrency;
    private final PriorityQueue<Task> queued = new PriorityQueue<>(
        Comparator.comparingInt((Task task) -> task.priority).reversed()
            .thenComparingLong(task -> task.sequence));
    private final Set<Task> running = new HashSet<>();
    private final Set<Task> activeTasks = new HashSet<>();
    private final Map<JmImage, Task> tasks = new HashMap<>();
    private final Set<String> cancelledSessions = new HashSet<>();
    private long sequence;
    private boolean closed;

    NetworkImageTaskScheduler(int concurrency) {
        this.concurrency = Math.max(1, concurrency);
    }

    boolean submit(JmImage image, String demandId, String sessionId, int priority,
                   boolean replaceCache, Fetcher fetcher, NetworkFetcher networkFetcher,
                   Consumer<byte[]> onSuccess, Consumer<Exception> onFailure) {
        synchronized (lock) {
            if (closed || cancelledSessions.contains(sessionId)) return false;
            Task task = tasks.get(image);
            if (task != null && replaceCache && task.state != State.QUEUED
                && (task.fetchPhase == FetchPhase.CACHE_HIT
                    || task.fetchPhase == FetchPhase.RESULT_READY)) {
                tasks.remove(image, task);
                if (task.state == State.RUNNING) {
                    running.remove(task);
                    task.state = State.DETACHED;
                }
                task = null;
            }
            if (task == null) {
                task = new Task(image, ++sequence, fetcher, networkFetcher);
                tasks.put(image, task);
                activeTasks.add(task);
                queued.add(task);
            }
            task.replaceCache |= replaceCache;
            task.demands.put(demandId, new Demand(sessionId, priority, onSuccess, onFailure));
            if (task.state == State.QUEUED) {
                queued.remove(task);
                task.priority = task.maxPriority();
                queued.add(task);
            } else {
                task.priority = Math.max(task.priority, priority);
            }
            pumpLocked();
            return true;
        }
    }

    void cancelSession(String sessionId) {
        synchronized (lock) {
            cancelledSessions.add(sessionId);
            for (Task task : List.copyOf(activeTasks)) {
                task.demands.entrySet().removeIf(entry -> entry.getValue().sessionId.equals(sessionId));
                if (task.demands.isEmpty() && task.state == State.QUEUED) {
                    queued.remove(task);
                    tasks.remove(task.image, task);
                    activeTasks.remove(task);
                    task.state = State.CANCELLED;
                } else if (task.demands.isEmpty() && task.state == State.RUNNING) {
                    running.remove(task);
                    task.state = State.DETACHED;
                } else if (task.state == State.RUNNING || task.state == State.DETACHED) {
                    task.priority = task.maxPriority();
                } else if (task.state == State.QUEUED) {
                    queued.remove(task);
                    task.priority = task.maxPriority();
                    queued.add(task);
                }
            }
            pumpLocked();
        }
    }

    void close() {
        List<Consumer<Exception>> failures = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            queued.clear();
            for (Task task : activeTasks) {
                task.state = task.state == State.QUEUED ? State.CANCELLED : State.DETACHED;
                for (Demand demand : task.demands.values()) failures.add(demand.onFailure);
                task.demands.clear();
            }
            tasks.clear();
            running.clear();
            activeTasks.clear();
        }
        RejectedExecutionException error = new RejectedExecutionException("图片服务已关闭");
        failures.forEach(failure -> failure.accept(error));
    }

    private void pumpLocked() {
        while (!queued.isEmpty()) {
            if (running.size() < concurrency) {
                startLocked(queued.poll());
                continue;
            }
            Task next = queued.peek();
            Task lowest = running.stream()
                .min(Comparator.comparingInt((Task task) -> task.priority)
                    .thenComparingLong(task -> -task.sequence))
                .orElseThrow();
            if (next.priority <= lowest.priority) return;
            running.remove(lowest);
            lowest.state = State.DETACHED;
            startLocked(queued.poll());
        }
    }

    private void startLocked(Task task) {
        if (task.state != State.QUEUED) return;
        task.state = State.RUNNING;
        task.fetchPhase = FetchPhase.RESOLVING_CACHE;
        running.add(task);
        Thread worker = new Thread(() -> execute(task), "jq-viewer-network-image-" + task.sequence);
        worker.setDaemon(true);
        worker.start();
    }

    private void execute(Task task) {
        byte[] result = null;
        Exception failure = null;
        try {
            result = task.fetcher.fetch(task.image, () -> task.replaceCache,
                reader -> {
                    synchronized (lock) {
                        if (task.replaceCache) {
                            task.fetchPhase = FetchPhase.CACHE_MISS;
                            return null;
                        }
                        byte[] cached = reader.get();
                        task.fetchPhase = cached == null
                            ? FetchPhase.CACHE_MISS : FetchPhase.CACHE_HIT;
                        return cached;
                    }
                }, image -> {
                    synchronized (lock) {
                        task.fetchPhase = FetchPhase.NETWORK;
                    }
                    return task.networkFetcher.fetch(image);
                });
            synchronized (lock) {
                if (task.fetchPhase != FetchPhase.CACHE_HIT) {
                    task.fetchPhase = FetchPhase.RESULT_READY;
                }
            }
        } catch (Exception error) {
            failure = error;
        }
        synchronized (lock) {
            running.remove(task);
            activeTasks.remove(task);
            for (Demand demand : new ArrayList<>(task.demands.values())) {
                if (failure == null) demand.onSuccess.accept(result);
                else demand.onFailure.accept(failure);
            }
            tasks.remove(task.image, task);
            task.state = State.DONE;
            task.demands.clear();
            pumpLocked();
        }
    }

    @FunctionalInterface
    interface Fetcher {
        byte[] fetch(JmImage image, BooleanSupplier replaceCache,
                     CacheLookup cacheLookup, NetworkFetcher networkFetcher) throws Exception;
    }

    @FunctionalInterface
    interface CacheLookup {
        byte[] read(Supplier<byte[]> reader);
    }

    @FunctionalInterface
    interface NetworkFetcher {
        byte[] fetch(JmImage image) throws Exception;
    }

    private enum State { QUEUED, RUNNING, DETACHED, DONE, CANCELLED }

    private enum FetchPhase { QUEUED, RESOLVING_CACHE, CACHE_MISS, CACHE_HIT, NETWORK, RESULT_READY }

    private static final class Task {
        private final JmImage image;
        private final long sequence;
        private final Fetcher fetcher;
        private final NetworkFetcher networkFetcher;
        private final Map<String, Demand> demands = new LinkedHashMap<>();
        private State state = State.QUEUED;
        private boolean replaceCache;
        private FetchPhase fetchPhase = FetchPhase.QUEUED;
        private int priority;

        private Task(JmImage image, long sequence, Fetcher fetcher,
                     NetworkFetcher networkFetcher) {
            this.image = image;
            this.sequence = sequence;
            this.fetcher = fetcher;
            this.networkFetcher = networkFetcher;
        }

        private int maxPriority() {
            return demands.values().stream().mapToInt(demand -> demand.priority).max().orElse(0);
        }
    }

    private record Demand(
        String sessionId,
        int priority,
        Consumer<byte[]> onSuccess,
        Consumer<Exception> onFailure
    ) {
    }
}
