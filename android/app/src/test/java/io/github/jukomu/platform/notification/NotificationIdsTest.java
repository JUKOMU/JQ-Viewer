package io.github.jukomu.platform.notification;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class NotificationIdsTest {

    @Test
    public void fixedNotificationIdsDoNotOverlapTaskRanges() {
        Set<Integer> fixedIds = new HashSet<>();

        assertTrue(fixedIds.add(NotificationIds.PDF_FOREGROUND));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_QUEUE_SUMMARY));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_FOREGROUND));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_COMPLETED_SUMMARY));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_FAILED_SUMMARY));

        for (int fixedId : fixedIds) {
            assertFalse(NotificationIds.containsPdfTask(fixedId));
            assertFalse(NotificationIds.containsDownloadTask(fixedId));
        }
    }

    @Test
    public void pdfTaskIdsUseDedicatedRange() {
        int span = NotificationIds.PDF_TASK_LIMIT - NotificationIds.PDF_TASK_BASE + 1;

        assertEquals(NotificationIds.PDF_TASK_BASE, NotificationIds.pdfTask(0));
        assertEquals(NotificationIds.PDF_TASK_LIMIT, NotificationIds.pdfTask(span - 1));
        assertEquals(NotificationIds.PDF_TASK_BASE, NotificationIds.pdfTask(span));

        assertTrue(NotificationIds.containsPdfTask(NotificationIds.pdfTask(42)));
        assertFalse(NotificationIds.containsDownloadTask(NotificationIds.pdfTask(42)));
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
        assertFalse(NotificationIds.containsPdfTask(notificationId));
    }
}
