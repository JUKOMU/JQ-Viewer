package io.github.jukomu.bridge;

import android.content.Intent;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JmcomicPluginTest {

    @Test
    public void pdfCommandDoesNotBlockCallingPluginThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch commandStarted = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        CountDownLatch pluginCallReturned = new CountDownLatch(1);
        try {
            Thread pluginThread = new Thread(() -> {
                JmcomicPlugin.dispatchPdfCommand(executor, () -> {
                    commandStarted.countDown();
                    try {
                        releaseCommand.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
                pluginCallReturned.countDown();
            }, "plugin-call-test");
            pluginThread.start();

            assertTrue(commandStarted.await(1, TimeUnit.SECONDS));
            assertTrue(pluginCallReturned.await(1, TimeUnit.SECONDS));
        } finally {
            releaseCommand.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void pdfFileOperationDoesNotBlockCallingPluginThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch pluginCallReturned = new CountDownLatch(1);
        try {
            Thread pluginThread = new Thread(() -> {
                JmcomicPlugin.dispatchPdfFileOperation(executor, () -> {
                    operationStarted.countDown();
                    await(releaseOperation);
                });
                pluginCallReturned.countDown();
            }, "pdf-file-operation-test");
            pluginThread.start();

            assertTrue(operationStarted.await(1, TimeUnit.SECONDS));
            assertTrue(pluginCallReturned.await(1, TimeUnit.SECONDS));
        } finally {
            releaseOperation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void pdfCommandAcceptsMoreThanSixteenQueuedCommandsAndRunsSerially() throws Exception {
        ExecutorService executor = JmcomicPlugin.createPdfCommandExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(21);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        try {
            JmcomicPlugin.dispatchPdfCommand(executor, () -> {
                int current = running.incrementAndGet();
                maxRunning.accumulateAndGet(current, Math::max);
                firstStarted.countDown();
                await(releaseFirst);
                running.decrementAndGet();
                completed.countDown();
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            for (int index = 0; index < 20; index++) {
                JmcomicPlugin.dispatchPdfCommand(executor, () -> {
                    int current = running.incrementAndGet();
                    maxRunning.accumulateAndGet(current, Math::max);
                    running.decrementAndGet();
                    completed.countDown();
                });
            }

            releaseFirst.countDown();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertEquals(1, maxRunning.get());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void exportedPdfFolderDoesNotGrantSyntheticDocumentUri() {
        assertEquals(0, JmcomicPlugin.pdfFolderGrantFlags(false));
    }

    @Test
    public void importedPdfFolderKeepsReadAndPrefixGrantFlags() {
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            JmcomicPlugin.pdfFolderGrantFlags(true));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
