package io.github.jukomu.service;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PdfExportFailureInstrumentedTest {

    @Test
    public void releasesAcceptedLocksWhenLaterValidationFails() {
        RecordingPublisher publisher = new RecordingPublisher();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        PdfExportService service = service(executor, publisher, () -> null);
        PdfExportService.ExportJob valid = chapterJob("1");
        PdfExportService.ExportJob invalid = chapterJob("2");
        invalid.albumId = "";

        assertThrows(
            IllegalArgumentException.class,
            () -> service.submitExport(Arrays.asList(valid, invalid))
        );
        assertEquals(0, service.getActiveJobCount());

        assertThrows(RejectedExecutionException.class, () -> service.submitExport(
            Collections.singletonList(valid)
        ));
        assertEquals(0, service.getActiveJobCount());
        assertEquals(1, publisher.snapshots.get(publisher.snapshots.size() - 2).activeCount);
        assertEquals(0, publisher.snapshots.get(publisher.snapshots.size() - 1).activeCount);
    }

    @Test
    public void releasesAcceptedLocksWhenQueuedPublicationFails() {
        PdfExportService service = service(
            new QueuedExecutorService(),
            (context, snapshot) -> {
                throw new IllegalStateException("publish failed at revision " + snapshot.revision);
            },
            () -> null
        );

        RuntimeException actual = assertThrows(
            RuntimeException.class,
            () -> service.submitExport(Collections.singletonList(chapterJob("1")))
        );

        assertTrue(actual.getMessage().startsWith("publish failed at revision"));
        assertEquals(1, actual.getSuppressed().length);
        assertEquals(0, service.getActiveJobCount());
    }

    @Test
    public void releasesWholeBatchWhenWorkerFailsBeforeFirstJob() {
        RecordingPublisher publisher = new RecordingPublisher();
        QueuedExecutorService executor = new QueuedExecutorService();
        PdfExportService service = service(
            executor,
            publisher,
            () -> {
                throw new AssertionError("wake lock initialization crashed");
            }
        );

        service.submitExport(Arrays.asList(chapterJob("1"), chapterJob("2")));
        assertEquals(2, service.getActiveJobCount());

        executor.runAll();

        assertEquals(0, service.getActiveJobCount());
        assertEquals(0, publisher.snapshots.get(publisher.snapshots.size() - 1).activeCount);
    }

    @Test
    public void wakeLockRuntimeFailureDegradesAndReleasesPartialAcquisition() {
        FakeWakeLock wakeLock = new FakeWakeLock();
        wakeLock.failAcquire = true;
        PdfExportService service = service(
            new QueuedExecutorService(),
            new RecordingPublisher(),
            () -> wakeLock
        );

        assertNull(service.acquirePdfWakeLock(1, 1));

        assertTrue(wakeLock.referenceCountingDisabled);
        assertTrue(wakeLock.releaseCalled);
        assertFalse(wakeLock.held);
    }

    @Test
    public void wakeLockCreationRuntimeFailureDegradesWithoutStartingExport() {
        PdfExportService service = service(
            new QueuedExecutorService(),
            new RecordingPublisher(),
            () -> {
                throw new IllegalStateException("newWakeLock failed");
            }
        );

        assertNull(service.acquirePdfWakeLock(1, 1));
    }

    @Test
    public void wakeLockConfigurationRuntimeFailureDegradesWithoutAcquire() {
        FakeWakeLock wakeLock = new FakeWakeLock();
        wakeLock.failConfiguration = true;
        PdfExportService service = service(
            new QueuedExecutorService(),
            new RecordingPublisher(),
            () -> wakeLock
        );

        assertNull(service.acquirePdfWakeLock(1, 1));

        assertFalse(wakeLock.acquireCalled);
        assertFalse(wakeLock.releaseCalled);
    }

    @Test
    public void serializesPdfSnapshotRevisionWithActiveCount() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        QueuedExecutorService executor = new QueuedExecutorService();
        PdfExportService service = service(executor, publisher, () -> null);
        Thread first = new Thread(() -> service.submitExport(
            Collections.singletonList(chapterJob("1"))
        ));
        Thread second = new Thread(() -> service.submitExport(
            Collections.singletonList(chapterJob("2"))
        ));

        first.start();
        second.start();
        first.join();
        second.join();

        assertEquals(2, publisher.snapshots.size());
        assertEquals(1, publisher.snapshots.get(0).revision);
        assertEquals(1, publisher.snapshots.get(0).activeCount);
        assertEquals(2, publisher.snapshots.get(1).revision);
        assertEquals(2, publisher.snapshots.get(1).activeCount);

        executor.runAll();
        assertEquals(0, service.getActiveJobCount());
        assertRevisionsIncrease(publisher.snapshots);
    }

    private static PdfExportService service(ExecutorService executor,
                                            PdfExportService.ForegroundPublisher publisher,
                                            PdfExportService.WakeLockFactory wakeLockFactory) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new PdfExportService(context, executor, publisher, wakeLockFactory);
    }

    private static PdfExportService.ExportJob chapterJob(String chapterId) {
        PdfExportService.ExportJob job = new PdfExportService.ExportJob();
        job.mode = "chapter";
        job.albumId = "100";
        job.chapterId = chapterId;
        job.chapterTitle = "chapter-" + chapterId;
        job.savePath = "/path/that/does/not/exist/chapter-" + chapterId + ".pdf";
        job.useOriginal = true;
        job.compressionRatio = 1F;
        return job;
    }

    private static void assertRevisionsIncrease(List<PdfExportForegroundService.Snapshot> snapshots) {
        for (int i = 0; i < snapshots.size(); i++) {
            assertEquals(i + 1, snapshots.get(i).revision);
        }
    }

    private static final class RecordingPublisher implements PdfExportService.ForegroundPublisher {
        final List<PdfExportForegroundService.Snapshot> snapshots =
            Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(Context context, PdfExportForegroundService.Snapshot snapshot) {
            snapshots.add(snapshot);
        }
    }

    private static final class QueuedExecutorService extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("executor is shut down");
            }
            tasks.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private static final class FakeWakeLock implements PdfExportService.WakeLockHandle {
        boolean failAcquire;
        boolean failConfiguration;
        boolean acquireCalled;
        boolean held;
        boolean referenceCountingDisabled;
        boolean releaseCalled;

        @Override
        public void setReferenceCounted(boolean value) {
            if (failConfiguration) {
                throw new IllegalStateException("setReferenceCounted failed");
            }
            referenceCountingDisabled = !value;
        }

        @Override
        public void acquire() {
            acquireCalled = true;
            held = true;
            if (failAcquire) {
                throw new IllegalStateException("acquire failed");
            }
        }

        @Override
        public boolean isHeld() {
            return held;
        }

        @Override
        public void release() {
            releaseCalled = true;
            held = false;
        }
    }
}
