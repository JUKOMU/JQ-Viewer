package io.github.jukomu.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 协调异步创建的客户端在动态网络环境中运行。
 */
final class ClientSession<T> {

    interface Factory<T> {
        CompletableFuture<T> create();
    }

    interface Observer<T> {
        void onStateChanged(Snapshot snapshot, T client);

        void onReady(T client);

        void onFailure(Throwable error);

        default void onDiscarded(T client) {
        }
    }

    record Snapshot(String state, String reason, long timestamp) {
    }

    private final Factory<T> factory;
    private final Observer<T> observer;
    private final ScheduledExecutorService retryScheduler;

    private T client;
    private Snapshot snapshot = snapshot("unavailable", "no_network");
    private String lastAttemptEnvironment;
    private boolean attemptInFlight;
    private long generation;
    private int retryAttempt;
    private ScheduledFuture<?> retryFuture;
    private long retryGeneration;

    ClientSession(Factory<T> factory, Observer<T> observer) {
        this(factory, observer, null);
    }

    ClientSession(Factory<T> factory, Observer<T> observer,
                  ScheduledExecutorService retryScheduler) {
        this.factory = factory;
        this.observer = observer;
        this.retryScheduler = retryScheduler;
    }

    synchronized T getClient() {
        return client;
    }

    synchronized Snapshot getSnapshot() {
        return snapshot;
    }

    void updateEnvironment(String environment, boolean available) {
        CompletableFuture<T> attempt = null;
        T discarded = null;
        long attemptGeneration = 0;
        synchronized (this) {
            if (!available) {
                if (client != null || attemptInFlight) generation++;
                cancelRetryLocked();
                discarded = client;
                client = null;
                attemptInFlight = false;
                lastAttemptEnvironment = null;
                retryAttempt = 0;
                publish(setSnapshot("unavailable", "no_network"));
            } else if (!environment.equals(lastAttemptEnvironment)) {
                cancelRetryLocked();
                discarded = client;
                client = null;
                lastAttemptEnvironment = environment;
                retryAttempt = 0;
                attemptInFlight = true;
                attemptGeneration = ++generation;
                publish(setSnapshot("initializing", null));
                try {
                    attempt = factory.create();
                    if (attempt == null) {
                        attempt = failedFuture(
                            new IllegalStateException("Client factory returned null future."));
                    }
                } catch (Throwable error) {
                    attempt = failedFuture(error);
                }
            }
        }
        discard(discarded);
        if (attempt != null) {
            long expectedGeneration = attemptGeneration;
            attempt.whenComplete(
                (value, error) -> completeAttempt(expectedGeneration, value, error));
        }
    }

    private void retryCurrentEnvironment() {
        CompletableFuture<T> attempt = null;
        long attemptGeneration = 0;
        synchronized (this) {
            if (lastAttemptEnvironment == null || attemptInFlight || client != null) return;
            attemptInFlight = true;
            attemptGeneration = ++generation;
            publish(setSnapshot("initializing", null));
            try {
                attempt = factory.create();
                if (attempt == null) {
                    attempt = failedFuture(
                        new IllegalStateException("Client factory returned null future."));
                }
            } catch (Throwable error) {
                attempt = failedFuture(error);
            }
        }
        long expectedGeneration = attemptGeneration;
        attempt.whenComplete((value, error) -> completeAttempt(expectedGeneration, value, error));
    }

    private void completeAttempt(long expectedGeneration, T value, Throwable error) {
        T discarded = null;
        boolean ready = false;
        boolean failed = false;
        synchronized (this) {
            if (!attemptInFlight || expectedGeneration != generation) {
                discarded = value;
            } else if (error == null && value != null) {
                attemptInFlight = false;
                client = value;
                retryAttempt = 0;
                cancelRetryLocked();
                publish(setSnapshot("ready", null));
                ready = true;
            } else {
                attemptInFlight = false;
                publish(setSnapshot("unavailable", "initialization_failed"));
                scheduleRetryLocked();
                failed = true;
            }
        }

        discard(discarded);
        if (ready) {
            observer.onReady(value);
        } else if (failed) {
            observer.onFailure(error != null
                ? error
                : new IllegalStateException("Client factory completed without a client."));
        }
    }

    private void scheduleRetryLocked() {
        if (retryScheduler == null || lastAttemptEnvironment == null || retryFuture != null) return;
        long delaySeconds = retryAttempt < 5 ? 1L << retryAttempt : 30L;
        retryAttempt++;
        long expectedRetryGeneration = ++retryGeneration;
        retryFuture = retryScheduler.schedule(() -> {
            synchronized (ClientSession.this) {
                if (expectedRetryGeneration != retryGeneration || retryFuture == null) return;
                retryFuture = null;
                if (lastAttemptEnvironment == null || attemptInFlight || client != null) return;
            }
            retryCurrentEnvironment();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void cancelRetryLocked() {
        retryGeneration++;
        if (retryFuture != null) {
            retryFuture.cancel(false);
            retryFuture = null;
        }
    }

    private Snapshot setSnapshot(String state, String reason) {
        if (state.equals(snapshot.state())
            && java.util.Objects.equals(reason, snapshot.reason())) {
            return null;
        }
        long timestamp = Math.max(System.currentTimeMillis(), snapshot.timestamp() + 1);
        snapshot = new Snapshot(state, reason, timestamp);
        return snapshot;
    }

    private void publish(Snapshot changed) {
        if (changed != null) {
            observer.onStateChanged(changed, client);
        }
    }

    private void discard(T value) {
        if (value != null) observer.onDiscarded(value);
    }

    private static Snapshot snapshot(String state, String reason) {
        return new Snapshot(state, reason, System.currentTimeMillis());
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
