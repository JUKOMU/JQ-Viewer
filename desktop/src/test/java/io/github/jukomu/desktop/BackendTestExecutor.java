package io.github.jukomu.desktop;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 跳过客户端构造时的后台初始化，保持本地后端测试不依赖外网。 */
final class BackendTestExecutor extends ThreadPoolExecutor {
    private final AtomicBoolean skipInitialization = new AtomicBoolean(true);

    BackendTestExecutor() {
        super(6, 6, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    }

    @Override
    public void execute(Runnable command) {
        if (skipInitialization.compareAndSet(true, false)) return;
        super.execute(command);
    }
}
