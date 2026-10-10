package io.github.jukomu.desktop.lifecycle;

import org.slf4j.Logger;

import java.util.Objects;

/**
 * 按声明顺序释放资源；单项失败只记录，不阻断后续清理。
 */
public final class CloseSequence {
    private static final long STEP_TIMEOUT_MILLIS = 2_000;

    private CloseSequence() {
    }

    public static void run(Logger logger, Step... steps) {
        Objects.requireNonNull(logger, "logger");
        long started = System.nanoTime();
        logger.info("lifecycle_close event=sequence status=started stepCount={}", steps == null ? 0 : steps.length);
        if (steps == null) {
            logger.info("lifecycle_close event=sequence status=completed elapsedMs=0");
            return;
        }
        for (Step step : steps) {
            if (step == null || step.action() == null) continue;
            long stepStarted = System.nanoTime();
            try {
                step.action().run();
                long elapsedMs = elapsedMs(stepStarted);
                if (elapsedMs >= STEP_TIMEOUT_MILLIS) {
                    logger.warn("lifecycle_close event=step status=timeout step={} elapsedMs={} thresholdMs={}",
                        step.name(), elapsedMs, STEP_TIMEOUT_MILLIS);
                } else {
                    logger.info("lifecycle_close event=step status=completed step={} elapsedMs={}",
                        step.name(), elapsedMs);
                }
            } catch (RuntimeException exception) {
                logger.warn("lifecycle_close event=step status=failed step={} elapsedMs={} errorClass={}",
                    step.name(), elapsedMs(stepStarted), exception.getClass().getSimpleName(), exception);
            }
        }
        logger.info("lifecycle_close event=sequence status=completed elapsedMs={}", elapsedMs(started));
    }

    public record Step(String name, Runnable action) {
        public Step {
            name = name == null || name.isBlank() ? "资源" : name;
        }
    }

    private static long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
