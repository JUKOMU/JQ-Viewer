package io.github.jukomu.feature.download;

import io.github.jukomu.feature.download.notification.DownloadNotificationActionReceiver;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DownloadCommandRouterTest {

    @Test
    public void routesPauseResumeAndCancel() {
        DownloadCommandRouter router = new DownloadCommandRouter();
        RecordingPort port = new RecordingPort();
        router.attach(port);

        router.dispatch(DownloadNotificationActionReceiver.ACTION_PAUSE, "pause-task");
        router.dispatch(DownloadNotificationActionReceiver.ACTION_RESUME, "resume-task");
        router.dispatch(DownloadNotificationActionReceiver.ACTION_CANCEL, "cancel-task");

        assertEquals("pause-task", port.pausedTaskId);
        assertEquals("resume-task", port.resumedTaskId);
        assertEquals("cancel-task", port.cancelledTaskId);
    }

    @Test
    public void unknownActionIsIgnored() {
        DownloadCommandRouter router = new DownloadCommandRouter();
        RecordingPort port = new RecordingPort();
        router.attach(port);

        router.dispatch("unknown", "task");

        assertEquals(0, port.commandCount);
    }

    @Test
    public void staleDetachCannotClearReboundPort() {
        DownloadCommandRouter router = new DownloadCommandRouter();
        RecordingPort first = new RecordingPort();
        RecordingPort second = new RecordingPort();

        router.attach(first);
        router.attach(second);
        router.detach(first);
        router.dispatch(DownloadNotificationActionReceiver.ACTION_PAUSE, "task");

        assertEquals(0, first.commandCount);
        assertEquals(1, second.commandCount);
    }

    private static final class RecordingPort implements DownloadCommandPort {

        private int commandCount;
        private String pausedTaskId;
        private String resumedTaskId;
        private String cancelledTaskId;

        @Override
        public void pauseDownload(String taskId) {
            commandCount++;
            pausedTaskId = taskId;
        }

        @Override
        public void resumeDownload(String taskId) {
            commandCount++;
            resumedTaskId = taskId;
        }

        @Override
        public void cancelDownload(String taskId) {
            commandCount++;
            cancelledTaskId = taskId;
        }
    }
}
