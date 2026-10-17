package io.github.jukomu.desktop;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 使用与生产环境相同约束的无界 API 线程池。 */
final class BackendTestExecutor extends ThreadPoolExecutor {
    BackendTestExecutor() {
        super(6, 6, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }
}
