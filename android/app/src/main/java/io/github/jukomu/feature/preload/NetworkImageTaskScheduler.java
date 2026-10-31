package io.github.jukomu.feature.preload;

import io.github.jukomu.jmcomic.api.model.JmImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 合并相同图片的在途网络请求，并按需求优先级启动物理网络任务。
 * 已开始的网络任务不会被中断；被调度器让出逻辑槽位后仍可完成并写入缓存。
 */
final class NetworkImageTaskScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkImageTaskScheduler.class);

    @FunctionalInterface
    interface Fetcher {
        byte[] fetch(JmImage image, BooleanSupplier detached, BooleanSupplier replaceCache,
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

    /** 物理结果只缓存一次；即使所有页面需求已取消，也会完成结果缓存。 */
    @FunctionalInterface
    interface ResultHandler {
        void cache(JmImage image, byte[] result, boolean replaceCache) throws Exception;
    }

    private final Object lock = new Object();
    private final int concurrency;
    private final PriorityQueue<Task> queued = new PriorityQueue<>(
        Comparator.comparingInt((Task task) -> task.priority).reversed()
            .thenComparingLong(task -> task.sequence));
    private final Set<Task> running = new HashSet<>();
    private final Set<Task> activeTasks = new HashSet<>();
    private final Map<JmImage, Task> tasks = new HashMap<>();
    private final Set<String> cancelledSessions = new HashSet<>();
    private final long[] sequence = {0L};

    NetworkImageTaskScheduler(int concurrency) {
        this.concurrency = Math.max(1, concurrency);
    }

    boolean submit(JmImage image, String demandId, String sessionId, int priority,
                   boolean replaceCache, Fetcher fetcher, NetworkFetcher networkFetcher,
                   ResultHandler resultHandler,
                   Consumer<byte[]> onSuccess, Consumer<Exception> onFailure) {
        synchronized (lock) {
            if (cancelledSessions.contains(sessionId)) return false;
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
                task = new Task(image, ++sequence[0], fetcher, networkFetcher, resultHandler);
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
            LOGGER.info("网络图片任务让出逻辑槽位: sortOrder={}, priority={}, sequence={}",
                lowest.image.getSortOrder(), lowest.priority, lowest.sequence);
            startLocked(queued.poll());
        }
    }

    private void startLocked(Task task) {
        if (task.state != State.QUEUED) return;
        task.state = State.RUNNING;
        task.fetchPhase = FetchPhase.RESOLVING_CACHE;
        running.add(task);
        Thread worker = new Thread(() -> execute(task),
            "jq-viewer-network-image-" + task.sequence);
        worker.setDaemon(true);
        worker.start();
    }

    private void execute(Task task) {
        byte[] result = null;
        Exception failure = null;
        try {
            result = task.fetcher.fetch(task.image,
                () -> task.state == State.DETACHED, () -> task.replaceCache,
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
                },
                image -> {
                    synchronized (lock) {
                        task.fetchPhase = FetchPhase.NETWORK;
                    }
                    try {
                        return task.networkFetcher.fetch(image);
                    } finally {
                        synchronized (lock) {
                            task.fetchPhase = FetchPhase.RESULT_READY;
                        }
                    }
                });
            synchronized (lock) {
                if (task.fetchPhase != FetchPhase.CACHE_HIT) {
                    task.fetchPhase = FetchPhase.RESULT_READY;
                }
            }
            task.resultHandler.cache(task.image, result, task.replaceCache);
        } catch (Exception error) {
            failure = error;
        }

        synchronized (lock) {
            running.remove(task);
            activeTasks.remove(task);
            if (failure != null && task.demands.isEmpty()) {
                LOGGER.warn("无有效需求的网络图片任务失败: sortOrder={}",
                    task.image.getSortOrder(), failure);
            }
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

    private enum State { QUEUED, RUNNING, DETACHED, DONE, CANCELLED }

    private enum FetchPhase {
        QUEUED, RESOLVING_CACHE, CACHE_MISS, CACHE_HIT, NETWORK, RESULT_READY
    }

    private static final class Task {
        private final JmImage image;
        private final long sequence;
        private final Fetcher fetcher;
        private final NetworkFetcher networkFetcher;
        private final Map<String, Demand> demands = new HashMap<>();
        private volatile State state = State.QUEUED;
        private volatile boolean replaceCache;
        private FetchPhase fetchPhase = FetchPhase.QUEUED;
        private int priority;
        private final ResultHandler resultHandler;

        private Task(JmImage image, long sequence, Fetcher fetcher,
                     NetworkFetcher networkFetcher, ResultHandler resultHandler) {
            this.image = image;
            this.sequence = sequence;
            this.fetcher = fetcher;
            this.networkFetcher = networkFetcher;
            this.resultHandler = resultHandler;
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
