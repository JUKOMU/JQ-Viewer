package io.github.jukomu.desktop.lifecycle;

import org.slf4j.Logger;

import java.util.Objects;

/**
 * 按声明顺序释放资源；单项失败只记录，不阻断后续清理。
 */
public final class CloseSequence {
    private CloseSequence() {
    }

    public static void run(Logger logger, Step... steps) {
        Objects.requireNonNull(logger, "logger");
        for (Step step : steps) {
            if (step == null || step.action() == null) continue;
            try {
                step.action().run();
            } catch (RuntimeException exception) {
                logger.warn("无法正常关闭{}", step.name(), exception);
            }
        }
    }

    public record Step(String name, Runnable action) {
        public Step {
            name = name == null || name.isBlank() ? "资源" : name;
        }
    }
}
