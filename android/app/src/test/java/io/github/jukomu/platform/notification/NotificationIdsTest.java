package io.github.jukomu.platform.notification;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class NotificationIdsTest {

    @Test
    public void fixedNotificationIdsDoNotOverlapTaskRanges() {
        Set<Integer> fixedIds = new HashSet<>();

        assertTrue(fixedIds.add(NotificationIds.EXPORT_FOREGROUND));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_QUEUE_SUMMARY));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_FOREGROUND));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_COMPLETED_SUMMARY));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_FAILED_SUMMARY));

        for (int fixedId : fixedIds) {
            assertFalse(NotificationIds.containsExportTask(fixedId));
            assertFalse(NotificationIds.containsDownloadTask(fixedId));
        }
    }

    @Test
    public void pdfTaskIdsUseDedicatedRange() {
        int span = NotificationIds.EXPORT_TASK_LIMIT - NotificationIds.EXPORT_TASK_BASE + 1;

        assertEquals(NotificationIds.EXPORT_TASK_BASE, NotificationIds.exportTask(0));
        assertEquals(NotificationIds.EXPORT_TASK_LIMIT, NotificationIds.exportTask(span - 1));
        assertEquals(NotificationIds.EXPORT_TASK_BASE, NotificationIds.exportTask(span));

        assertTrue(NotificationIds.containsExportTask(NotificationIds.exportTask(42)));
        assertFalse(NotificationIds.containsDownloadTask(NotificationIds.exportTask(42)));
    }

    @Test
    public void downloadTaskIdsUseDedicatedRange() {
        assertDownloadTaskRange(null);
        assertDownloadTaskRange("album-1_chapter-1");
        assertDownloadTaskRange("album-1_chapter-2");
        assertDownloadTaskRange("another-task");
    }

    private static void assertDownloadTaskRange(String taskId) {
        int notificationId = NotificationIds.downloadTask(taskId);

        assertTrue(NotificationIds.containsDownloadTask(notificationId));
        assertFalse(NotificationIds.containsExportTask(notificationId));
    }
}
