package io.github.jukomu.desktop;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 使用与生产环境相同约束的有界业务线程池。 */
final class BackendTestExecutor extends ThreadPoolExecutor {
    BackendTestExecutor() {
        super(6, 6, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    }
}
