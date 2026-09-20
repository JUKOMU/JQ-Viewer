package io.github.jukomu.bridge;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Tracks asynchronous bridge calls that belong to one plugin session.
 *
 * <p>Destroying the session rejects every unfinished call exactly once. Late task results are
 * ignored, so executor shutdown cannot leave a call unresolved or publish a second result.</p>
 */
public final class PluginCallSession implements AutoCloseable {

    public static final String SESSION_ENDED_MESSAGE = "插件会话已结束";
    private static final String SUBMISSION_FAILED_MESSAGE = "后台任务提交失败";

    private final Object lock = new Object();
    private final Set<GuardedPluginCall> activeCalls = new HashSet<>();
    private boolean closed;

    /** Registers an asynchronous call, or rejects it immediately if the session already ended. */
    public PluginCall register(PluginCall call) {
        if (call == null) {
            throw new IllegalArgumentException("call is required");
        }
        GuardedPluginCall guarded = new GuardedPluginCall(this, call);
        synchronized (lock) {
            if (!closed) {
                activeCalls.add(guarded);
                return guarded;
            }
        }
        guarded.rejectSessionEnded();
        return null;
    }

    /** Registers and dispatches a task while keeping its bridge call lifecycle-safe. */
    public void submit(Executor executor, PluginCall call, Consumer<PluginCall> task) {
        if (executor == null) {
            call.reject(SUBMISSION_FAILED_MESSAGE,
                new IllegalStateException("executor is required"));
            return;
        }
        if (task == null) {
            call.reject(SUBMISSION_FAILED_MESSAGE,
                new IllegalArgumentException("task is required"));
            return;
        }

        PluginCall guarded = register(call);
        if (guarded == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.accept(guarded);
                } catch (RuntimeException error) {
                    guarded.reject(error.getMessage() == null
                        ? SUBMISSION_FAILED_MESSAGE : error.getMessage(), error);
                }
            });
        } catch (RuntimeException error) {
            guarded.reject(isClosed() ? SESSION_ENDED_MESSAGE : SUBMISSION_FAILED_MESSAGE, error);
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    @Override
    public void close() {
        ArrayList<GuardedPluginCall> calls;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            calls = new ArrayList<>(activeCalls);
        }
        for (GuardedPluginCall call : calls) {
            call.rejectSessionEnded();
        }
    }

    private void remove(GuardedPluginCall call) {
        synchronized (lock) {
            activeCalls.remove(call);
        }
    }

    private static final class GuardedPluginCall extends PluginCall {
        private final PluginCallSession owner;
        private final PluginCall delegate;
        private final Object completionLock = new Object();
        private boolean completed;

        private GuardedPluginCall(PluginCallSession owner, PluginCall delegate) {
            super(null, delegate.getPluginId(), delegate.getCallbackId(),
                delegate.getMethodName(), delegate.getData());
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override
        public void resolve(JSObject data) {
            complete(() -> delegate.resolve(data));
        }

        @Override
        public void resolve() {
            complete(delegate::resolve);
        }

        @Override
        public void reject(String message, String code, Exception exception, JSObject data) {
            complete(() -> delegate.reject(message, code, exception, data));
        }

        @Override
        public void setKeepAlive(Boolean keepAlive) {
            synchronized (completionLock) {
                if (completed) {
                    return;
                }
                delegate.setKeepAlive(keepAlive);
            }
        }

        @Override
        public boolean isKeptAlive() {
            return delegate.isKeptAlive();
        }

        private void rejectSessionEnded() {
            complete(() -> {
                delegate.setKeepAlive(false);
                delegate.reject(SESSION_ENDED_MESSAGE);
            });
        }

        private void complete(Runnable terminalAction) {
            synchronized (completionLock) {
                if (completed) {
                    return;
                }
                completed = true;
            }
            owner.remove(this);
            terminalAction.run();
        }
    }
}
