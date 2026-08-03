package io.github.jukomu.service;

import io.github.jukomu.data.CacheCapacityPolicy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 预载网络任务的动态并发门禁。
 *
 * <p>门禁只阻塞专用网络线程池，不占用本地图片处理或下载准备线程。</p>
 */
public final class NetworkLoadGate {

    private static final long WAIT_INTERVAL_MS = 200L;

    private final Object lock = new Object();
    private final int configuredConcurrency;
    private int activeLoads;
    private CacheCapacityPolicy.PressureLevel pressureLevel =
        CacheCapacityPolicy.PressureLevel.NORMAL;
    private boolean closed;

    public NetworkLoadGate(int configuredConcurrency) {
        this.configuredConcurrency = Math.max(1, configuredConcurrency);
    }

    /**
     * 等待一个网络加载名额。请求已过期时返回 null，不会留下占用的名额。
     */
    public Permit acquire(BooleanSupplier cancelled) throws InterruptedException {
        synchronized (lock) {
            while (!closed) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return null;
                }
                if (pressureLevel == CacheCapacityPolicy.PressureLevel.COMPLETE) {
                    return null;
                }
                int allowedLoads = getAllowedLoads();
                if (allowedLoads > activeLoads) {
                    activeLoads++;
                    return new Permit(this);
                }
                lock.wait(WAIT_INTERVAL_MS);
            }
            return null;
        }
    }

    public void setPressureLevel(CacheCapacityPolicy.PressureLevel level) {
        synchronized (lock) {
            pressureLevel = level == null
                ? CacheCapacityPolicy.PressureLevel.NORMAL : level;
            lock.notifyAll();
        }
    }

    public int getActiveLoads() {
        synchronized (lock) {
            return activeLoads;
        }
    }

    public boolean isCompletePressure() {
        synchronized (lock) {
            return pressureLevel == CacheCapacityPolicy.PressureLevel.COMPLETE;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    private int getAllowedLoads() {
        switch (pressureLevel) {
            case RUNNING_CRITICAL:
                return 1;
            case COMPLETE:
                return 0;
            case RUNNING_LOW:
            case UI_HIDDEN:
            case BACKGROUND:
            case MODERATE:
                return Math.max(1, (configuredConcurrency + 1) / 2);
            case NORMAL:
            case RUNNING_MODERATE:
            default:
                return configuredConcurrency;
        }
    }

    public static final class Permit implements AutoCloseable {
        private final NetworkLoadGate owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(NetworkLoadGate owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            synchronized (owner.lock) {
                if (owner.activeLoads > 0) {
                    owner.activeLoads--;
                }
                owner.lock.notifyAll();
            }
        }
    }
}
