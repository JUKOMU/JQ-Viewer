package io.github.jukomu.feature.pdf.export;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.jukomu.feature.download.notification.DownloadForegroundService;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class ForegroundServiceRevisionInstrumentedTest {

    private static final long SERVICE_STATE_TIMEOUT_MS = 5_000L;

    @Test
    public void downloadStaleStopDoesNotCloseNewerUpdate() {
        Context context = targetContext();
        int revision = 1_000_000;
        try {
            DownloadForegroundService.update(context, 1, revision);
            waitForService(context, DownloadForegroundService.class, true);

            DownloadForegroundService.update(context, 2, revision + 2);
            DownloadForegroundService.update(context, 0, revision + 1);

            waitForService(context, DownloadForegroundService.class, true);
        } finally {
            DownloadForegroundService.update(context, 0, revision + 3);
            waitForService(context, DownloadForegroundService.class, false);
        }
    }

    @Test
    public void pdfStaleStopDoesNotCloseNewerUpdate() {
        Context context = targetContext();
        int revision = 2_000_000;
        try {
            PdfExportForegroundService.update(context, pdfSnapshot(revision, 1));
            waitForService(context, PdfExportForegroundService.class, true);

            PdfExportForegroundService.update(context, pdfSnapshot(revision + 2, 2));
            PdfExportForegroundService.update(context, pdfSnapshot(revision + 1, 0));

            waitForService(context, PdfExportForegroundService.class, true);
        } finally {
            PdfExportForegroundService.update(context, pdfSnapshot(revision + 3, 0));
            waitForService(context, PdfExportForegroundService.class, false);
        }
    }

    private static Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static PdfExportForegroundService.Snapshot pdfSnapshot(int revision, int activeCount) {
        return new PdfExportForegroundService.Snapshot(
            1,
            revision,
            activeCount,
            Math.max(0, activeCount - 1),
            "PDF test",
            "testing",
            1,
            2,
            1,
            1
        );
    }

    @SuppressWarnings("deprecation")
    private static void waitForService(Context context, Class<?> serviceClass, boolean expected) {
        long deadline = SystemClock.uptimeMillis() + SERVICE_STATE_TIMEOUT_MS;
        boolean running;
        do {
            running = isServiceRunning(context, serviceClass);
            if (running == expected) {
                return;
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);

        assertEquals(expected, running);
    }

    @SuppressWarnings("deprecation")
    private static boolean isServiceRunning(Context context, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
