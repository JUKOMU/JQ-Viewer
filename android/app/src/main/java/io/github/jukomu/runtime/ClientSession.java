package io.github.jukomu.runtime;

import java.util.concurrent.CompletableFuture;

/**
 * Coordinates one asynchronously-created client across changing network environments.
 */
final class ClientSession<T> {

    interface Factory<T> {
        CompletableFuture<T> create();
    }

    interface Observer<T> {
        void onStateChanged(Snapshot snapshot);

        void onReady(T client);

        void onFailure(Throwable error);
    }

    record Snapshot(String state, String reason, long timestamp) {
    }

    private final Factory<T> factory;
    private final Observer<T> observer;

    private T client;
    private Snapshot snapshot = snapshot("unavailable", "no_network");
    private String lastAttemptEnvironment;
    private String pendingEnvironment;
    private boolean attemptInFlight;

    ClientSession(Factory<T> factory, Observer<T> observer) {
        this.factory = factory;
        this.observer = observer;
    }

    synchronized T getClient() {
        return client;
    }

    synchronized Snapshot getSnapshot() {
        return snapshot;
    }

    void updateEnvironment(String environment, boolean available) {
        CompletableFuture<T> attempt = null;
        Snapshot changed = null;
        synchronized (this) {
            if (client != null) {
                return;
            }
            if (!available) {
                if (!attemptInFlight) {
                    changed = setSnapshot("unavailable", "no_network");
                }
            } else if (attemptInFlight) {
                if (!environment.equals(lastAttemptEnvironment)) {
                    pendingEnvironment = environment;
                }
            } else if (!environment.equals(lastAttemptEnvironment)) {
                lastAttemptEnvironment = environment;
                attemptInFlight = true;
                changed = setSnapshot("initializing", null);
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
        publish(changed);
        if (attempt != null) {
            attempt.whenComplete(this::completeAttempt);
        }
    }

    private void completeAttempt(T value, Throwable error) {
        Snapshot changed;
        String nextEnvironment;
        synchronized (this) {
            attemptInFlight = false;
            if (error == null && value != null) {
                client = value;
                changed = setSnapshot("ready", null);
                nextEnvironment = null;
            } else {
                changed = setSnapshot("unavailable", "initialization_failed");
                nextEnvironment = pendingEnvironment;
            }
            pendingEnvironment = null;
        }

        publish(changed);
        if (error == null && value != null) {
            observer.onReady(value);
        } else {
            observer.onFailure(error != null
                ? error
                : new IllegalStateException("Client factory completed without a client."));
            if (nextEnvironment != null) {
                updateEnvironment(nextEnvironment, true);
            }
        }
    }

    private Snapshot setSnapshot(String state, String reason) {
        if (state.equals(snapshot.state())
            && java.util.Objects.equals(reason, snapshot.reason())) {
            return null;
        }
        snapshot = snapshot(state, reason);
        return snapshot;
    }

    private void publish(Snapshot changed) {
        if (changed != null) {
            observer.onStateChanged(changed);
        }
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
