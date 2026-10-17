package io.github.jukomu.desktop.backend;

import io.github.jukomu.desktop.feature.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Desktop 后端各服务独立的无界执行器及其生命周期。
 */
final class ServiceExecutors implements AutoCloseable {
    private static final int API_THREADS = 12;
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceExecutors.class);

    private final ExecutorService api;
    private final ThreadPoolExecutor imagePreload;
    private final ExecutorService imageOnDemand;
    private final ExecutorService imageResource;
    private final ExecutorService imageCommand;
    private final ExecutorService downloadCommand;
    private final ExecutorService downloadPrepare;
    private final ExecutorService fileIo;
    private final ExecutorService fileDialog;
    private final ExecutorService relocation;
    private final ExecutorService pdfCommand;
    private final ExecutorService exportJobs;
    private final ExecutorService networkCommand;
    private final ExecutorService networkProbe;
    private final ExecutorService ocr;
    private final ExecutorService updateCommand;
    private final ExecutorService settings;
    private final ExecutorService history;
    private final ExecutorService offlineFavorite;
    private final ExecutorService diagnostics;
    private final List<ExecutorService> owned;

    ServiceExecutors() {
        this(create("jq-viewer-api", API_THREADS));
    }

    ServiceExecutors(ExecutorService api) {
        this.api = Objects.requireNonNull(api, "api");
        imagePreload = create("jq-viewer-image-preload", SettingsService.DEFAULT_CONCURRENCY);
        imageOnDemand = create("jq-viewer-image-demand", 2);
        imageResource = create("jq-viewer-image-resource", 2);
        imageCommand = create("jq-viewer-image-command", 1);
        downloadCommand = create("jq-viewer-download-command", 2);
        downloadPrepare = create("jq-viewer-download-prepare", 2);
        fileIo = create("jq-viewer-file-io", 2);
        fileDialog = create("jq-viewer-file-dialog", 1);
        relocation = create("jq-viewer-relocation", 1);
        pdfCommand = create("jq-viewer-pdf-command", 1);
        exportJobs = create("jq-viewer-file-export", 1);
        networkCommand = create("jq-viewer-network-command", 1);
        networkProbe = create("jq-viewer-network-probe", 1);
        ocr = create("jq-viewer-ocr", 1);
        updateCommand = create("jq-viewer-update-command", 1);
        settings = create("jq-viewer-settings", 1);
        history = create("jq-viewer-history", 1);
        offlineFavorite = create("jq-viewer-offline-favorite", 1);
        diagnostics = create("jq-viewer-diagnostics", 1);
        owned = List.of(
            api,
            imagePreload,
            imageOnDemand,
            imageResource,
            imageCommand,
            downloadCommand,
            downloadPrepare,
            fileIo,
            fileDialog,
            relocation,
            pdfCommand,
            exportJobs,
            networkCommand,
            networkProbe,
            ocr,
            updateCommand,
            settings,
            history,
            offlineFavorite,
            diagnostics
        );
        LOGGER.info("service_executors event=created pools={} apiThreads={} imagePreloadThreads={} status=ready",
            owned.size(), API_THREADS, imagePreload.getCorePoolSize());
    }

    ExecutorService api() {
        return api;
    }

    ExecutorService imagePreload() {
        return imagePreload;
    }

    ExecutorService imageOnDemand() {
        return imageOnDemand;
    }

    ExecutorService imageResource() {
        return imageResource;
    }

    ExecutorService imageCommand() {
        return imageCommand;
    }

    ExecutorService downloadCommand() {
        return downloadCommand;
    }

    ExecutorService downloadPrepare() {
        return downloadPrepare;
    }

    ExecutorService fileIo() {
        return fileIo;
    }

    ExecutorService fileDialog() {
        return fileDialog;
    }

    ExecutorService relocation() {
        return relocation;
    }

    ExecutorService pdfCommand() {
        return pdfCommand;
    }

    ExecutorService exportJobs() {
        return exportJobs;
    }

    ExecutorService networkCommand() {
        return networkCommand;
    }

    ExecutorService networkProbe() {
        return networkProbe;
    }

    ExecutorService ocr() {
        return ocr;
    }

    ExecutorService updateCommand() {
        return updateCommand;
    }

    ExecutorService settings() {
        return settings;
    }

    ExecutorService history() {
        return history;
    }

    ExecutorService offlineFavorite() {
        return offlineFavorite;
    }

    ExecutorService diagnostics() {
        return diagnostics;
    }

    void configureImagePreload(int concurrency) {
        int threads = Math.max(1, Math.min(12, concurrency));
        if (threads > imagePreload.getMaximumPoolSize()) {
            imagePreload.setMaximumPoolSize(threads);
            imagePreload.setCorePoolSize(threads);
        } else {
            imagePreload.setCorePoolSize(threads);
            imagePreload.setMaximumPoolSize(threads);
        }
        LOGGER.info("service_executors event=reconfigured pool=image-preload requestedThreads={} threads={} status=ready",
            concurrency, threads);
    }

    boolean allShutdown() {
        return owned.stream().allMatch(ExecutorService::isShutdown);
    }

    @Override
    public void close() {
        long started = System.nanoTime();
        LOGGER.info("service_executors event=close status=started pools={}", owned.size());
        for (ExecutorService executor : owned) {
            executor.shutdown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            for (int index = owned.size() - 1; index >= 0; index--) {
                ExecutorService executor = owned.get(index);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    LOGGER.warn("service_executors event=pool-close poolIndex={} status=timeout elapsedMs={}",
                        index, elapsedMs(started));
                    executor.shutdownNow();
                }
            }
        } catch (InterruptedException exception) {
            LOGGER.warn("service_executors event=close status=interrupted elapsedMs={} errorClass={}",
                elapsedMs(started), exception.getClass().getSimpleName());
            for (ExecutorService executor : owned) {
                executor.shutdownNow();
            }
            Thread.currentThread().interrupt();
        }
        LOGGER.info("service_executors event=close status=completed elapsedMs={} allShutdown={}",
            elapsedMs(started), allShutdown());
    }

    private static ThreadPoolExecutor create(String name, int threads) {
        return new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            namedFactory(name),
            rejectedHandler(name)
        );
    }

    private static RejectedExecutionHandler rejectedHandler(String name) {
        return (runnable, executor) -> {
            LOGGER.warn("service_executors event=task-rejected pool={} shutdown={} queueSize={} status=failed",
                name, executor.isShutdown(), executor.getQueue().size());
            new ThreadPoolExecutor.AbortPolicy().rejectedExecution(runnable, executor);
        };
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
