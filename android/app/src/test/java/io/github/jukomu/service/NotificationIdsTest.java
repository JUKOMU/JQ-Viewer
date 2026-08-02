package io.github.jukomu.service;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotificationIdsTest {

    @Test
    public void fixedNotificationIdsDoNotOverlapTaskRanges() {
        Set<Integer> fixedIds = new HashSet<>();

        assertTrue(fixedIds.add(NotificationIds.PDF_FOREGROUND));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_QUEUE_SUMMARY));
        assertTrue(fixedIds.add(NotificationIds.DOWNLOAD_FOREGROUND));

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

    @Test
    public void foregroundServicesRejectOnlyOlderRevisions() {
        assertTrue(PdfExportForegroundService.isStaleRevision(4, 5));
        assertFalse(PdfExportForegroundService.isStaleRevision(5, 5));
        assertFalse(PdfExportForegroundService.isStaleRevision(6, 5));

        assertTrue(DownloadForegroundService.isStaleRevision(4, 5));
        assertFalse(DownloadForegroundService.isStaleRevision(5, 5));
        assertFalse(DownloadForegroundService.isStaleRevision(6, 5));
    }

    private static void assertDownloadTaskRange(String taskId) {
        int notificationId = NotificationIds.downloadTask(taskId);

        assertTrue(NotificationIds.containsDownloadTask(notificationId));
        assertFalse(NotificationIds.containsPdfTask(notificationId));
    }
}
