package io.github.jukomu.desktop.backend;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.client.JmDownloadClient;

import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;

/** 创建不访问网络的 Backend 测试实例。 */
public final class TestBackends {
    private TestBackends() {
    }

    public static Backend offline(
            Paths paths,
            Database database,
            ExecutorService executor
    ) {
        JmClient client = (JmClient) Proxy.newProxyInstance(
                JmClient.class.getClassLoader(),
                new Class<?>[]{JmClient.class, JmDownloadClient.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "OfflineTestJmClient";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new AssertionError(method.getName());
                        };
                    }
                    if ("close".equals(method.getName())) return null;
                    throw new AssertionError("未预期的客户端调用: " + method.getName());
                }
        );
        return new Backend(paths, database, executor, client, id -> "");
    }
}
