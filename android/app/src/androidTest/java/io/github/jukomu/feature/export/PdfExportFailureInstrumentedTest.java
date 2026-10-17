package io.github.jukomu.feature.export;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfExportFailureInstrumentedTest {

    @Test
    public void wakeLockRuntimeFailureDegradesAndReleasesPartialAcquisition() {
        FakeWakeLock wakeLock = new FakeWakeLock();
        wakeLock.failAcquire = true;
        ExportService service = service(
            ExportService.createExecutor(),
            (context, snapshot) -> {
            },
            () -> wakeLock
        );

        assertNull(service.acquireExportWakeLock(1, 1));

        assertTrue(wakeLock.referenceCountingDisabled);
        assertTrue(wakeLock.releaseCalled);
        assertFalse(wakeLock.held);
    }

    @Test
    public void wakeLockCreationRuntimeFailureDegradesWithoutStartingExport() {
        ExportService service = service(
            ExportService.createExecutor(),
            (context, snapshot) -> {
            },
            () -> {
                throw new IllegalStateException("newWakeLock failed");
            }
        );

        assertNull(service.acquireExportWakeLock(1, 1));
    }

    @Test
    public void wakeLockConfigurationRuntimeFailureDegradesWithoutAcquire() {
        FakeWakeLock wakeLock = new FakeWakeLock();
        wakeLock.failConfiguration = true;
        ExportService service = service(
            ExportService.createExecutor(),
            (context, snapshot) -> {
            },
            () -> wakeLock
        );

        assertNull(service.acquireExportWakeLock(1, 1));

        assertFalse(wakeLock.acquireCalled);
        assertFalse(wakeLock.releaseCalled);
    }

    private static ExportService service(ExecutorService executor,
                                            ExportService.ForegroundPublisher publisher,
                                            ExportService.WakeLockFactory wakeLockFactory) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ExportService(context, executor, publisher, wakeLockFactory);
    }

    private static final class FakeWakeLock implements ExportService.WakeLockHandle {
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
