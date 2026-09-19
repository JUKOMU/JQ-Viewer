package io.github.jukomu.desktop.backend;

import io.github.jukomu.desktop.feature.settings.SettingsService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Desktop 后端各服务独立的有界执行器及其生命周期。 */
final class ServiceExecutors implements AutoCloseable {
    private static final int API_THREADS = 12;

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
    private final ExecutorService pdfExport;
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
        this(create("jq-viewer-api", API_THREADS, 64));
    }

    ServiceExecutors(ExecutorService api) {
        this.api = Objects.requireNonNull(api, "api");
        imagePreload = create("jq-viewer-image-preload", SettingsService.DEFAULT_CONCURRENCY, 64);
        imageOnDemand = create("jq-viewer-image-demand", 2, 32);
        imageResource = create("jq-viewer-image-resource", 2, 32);
        imageCommand = create("jq-viewer-image-command", 1, 64);
        downloadCommand = create("jq-viewer-download-command", 2, 64);
        downloadPrepare = create("jq-viewer-download-prepare", 2, 64);
        fileIo = create("jq-viewer-file-io", 2, 64);
        fileDialog = create("jq-viewer-file-dialog", 1, 4);
        relocation = create("jq-viewer-relocation", 1, 4);
        pdfCommand = create("jq-viewer-pdf-command", 1, 64);
        pdfExport = create("jq-viewer-pdf-export", 1, 64);
        networkCommand = create("jq-viewer-network-command", 1, 16);
        networkProbe = create("jq-viewer-network-probe", 1, 4);
        ocr = create("jq-viewer-ocr", 1, 4);
        updateCommand = create("jq-viewer-update-command", 1, 16);
        settings = create("jq-viewer-settings", 1, 64);
        history = create("jq-viewer-history", 1, 64);
        offlineFavorite = create("jq-viewer-offline-favorite", 1, 64);
        diagnostics = create("jq-viewer-diagnostics", 1, 16);
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
                pdfExport,
                networkCommand,
                networkProbe,
                ocr,
                updateCommand,
                settings,
                history,
                offlineFavorite,
                diagnostics
        );
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

    ExecutorService pdfExport() {
        return pdfExport;
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
    }

    boolean allShutdown() {
        return owned.stream().allMatch(ExecutorService::isShutdown);
    }

    @Override
    public void close() {
        for (ExecutorService executor : owned) {
            executor.shutdown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            for (ExecutorService executor : owned.reversed()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                }
            }
        } catch (InterruptedException exception) {
            for (ExecutorService executor : owned) {
                executor.shutdownNow();
            }
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadPoolExecutor create(String name, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedFactory(name),
                new ThreadPoolExecutor.AbortPolicy()
        );
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
