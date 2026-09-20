package io.github.jukomu.bridge;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginCallSessionInstrumentedTest {

    @Test
    public void closeWaitsForWinningCompletionAction() throws Exception {
        PluginCallSession session = new PluginCallSession();
        RecordingPluginCall call = new RecordingPluginCall("test", new JSObject());
        PluginCall trackedCall = session.register(call);
        CountDownLatch completionEntered = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);
        ExecutorService completionExecutor = Executors.newSingleThreadExecutor();
        Thread closeThread = new Thread(session::close, "plugin-call-session-close-test");
        try {
            Future<Boolean> completion = completionExecutor.submit(() ->
                session.completeIfActive(trackedCall, activeCall -> {
                    completionEntered.countDown();
                    await(releaseCompletion);
                    JSObject result = new JSObject();
                    result.put("winner", "completion");
                    activeCall.resolve(result);
                }));
            assertTrue(completionEntered.await(1, TimeUnit.SECONDS));

            closeThread.start();
            assertThreadBlocked(closeThread);
            releaseCompletion.countDown();

            assertTrue(completion.get(1, TimeUnit.SECONDS));
            closeThread.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(closeThread.isAlive());
            assertEquals(1, call.completionCount);
            assertEquals("completion", call.resolvedData.getString("winner"));
            assertNull(call.rejectionMessage);
        } finally {
            releaseCompletion.countDown();
            completionExecutor.shutdownNow();
            closeThread.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    private static void assertThreadBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (thread.getState() != Thread.State.BLOCKED
            && thread.getState() != Thread.State.TERMINATED
            && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
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
