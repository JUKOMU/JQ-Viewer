package io.github.jukomu.feature.download.notification;

import android.content.Intent;
import io.github.jukomu.feature.download.DownloadCommandPort;
import io.github.jukomu.feature.download.DownloadCommandRouter;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DownloadNotificationActionReceiverInstrumentedTest {

    @Test
    public void knownActionWithTaskIdIsForwarded() {
        DownloadCommandRouter router = new DownloadCommandRouter();
        RecordingPort port = new RecordingPort();
        router.attach(port);
        DownloadNotificationActionReceiver receiver =
            new DownloadNotificationActionReceiver(router);
        Intent intent = new Intent(DownloadNotificationActionReceiver.ACTION_PAUSE)
            .putExtra(DownloadNotificationActionReceiver.EXTRA_TASK_ID, "task");

        receiver.onReceive(null, intent);

        assertEquals(1, port.pauseCount);
    }

    @Test
    public void nullIntentAndMissingOrEmptyTaskIdAreIgnored() {
        DownloadCommandRouter router = new DownloadCommandRouter();
        RecordingPort port = new RecordingPort();
        router.attach(port);
        DownloadNotificationActionReceiver receiver =
            new DownloadNotificationActionReceiver(router);

        receiver.onReceive(null, null);
        receiver.onReceive(null,
            new Intent(DownloadNotificationActionReceiver.ACTION_PAUSE));
        receiver.onReceive(null,
            new Intent(DownloadNotificationActionReceiver.ACTION_PAUSE)
                .putExtra(DownloadNotificationActionReceiver.EXTRA_TASK_ID, ""));

        assertEquals(0, port.pauseCount);
    }

    @Test
    public void actionWithoutAttachedPortIsIgnored() {
        DownloadCommandRouter router = new DownloadCommandRouter();

        router.dispatch(DownloadNotificationActionReceiver.ACTION_PAUSE, "task");
    }

    @Test
    public void commandFailureIsCaught() {
        DownloadCommandRouter router = new DownloadCommandRouter();
        router.attach(new DownloadCommandPort() {
            @Override
            public void pauseDownload(String taskId) {
                throw new IllegalStateException("failed");
            }

            @Override
            public void resumeDownload(String taskId) {
            }

            @Override
            public void cancelDownload(String taskId) {
            }
        });

        router.dispatch(DownloadNotificationActionReceiver.ACTION_PAUSE, "task");
    }

    private static final class RecordingPort implements DownloadCommandPort {

        private int pauseCount;

        @Override
        public void pauseDownload(String taskId) {
            pauseCount++;
        }

        @Override
        public void resumeDownload(String taskId) {
        }

        @Override
        public void cancelDownload(String taskId) {
        }
    }
}
