package io.github.jukomu.platform.notification;

/**
 * Centralizes fixed notification IDs and disjoint ranges for background operations.
 */
public final class NotificationIds {

    public static final int PDF_FOREGROUND = 202001;
    public static final int DOWNLOAD_QUEUE_SUMMARY = 203000;
    public static final int DOWNLOAD_FOREGROUND = 203001;
    public static final int DOWNLOAD_COMPLETED_SUMMARY = 203002;
    public static final int DOWNLOAD_FAILED_SUMMARY = 203003;

    public static final int PDF_TASK_BASE = 210000;
    public static final int PDF_TASK_LIMIT = 219999;
    public static final int DOWNLOAD_TASK_BASE = 230000;
    public static final int DOWNLOAD_TASK_LIMIT = 329999;

    private static final int PDF_TASK_SPAN = PDF_TASK_LIMIT - PDF_TASK_BASE + 1;
    private static final int DOWNLOAD_TASK_SPAN = DOWNLOAD_TASK_LIMIT - DOWNLOAD_TASK_BASE + 1;

    private NotificationIds() {
    }

    public static int pdfTask(int sequence) {
        return PDF_TASK_BASE + Math.floorMod(sequence, PDF_TASK_SPAN);
    }

    public static int downloadTask(String taskId) {
        int hash = taskId == null ? 0 : taskId.hashCode();
        return DOWNLOAD_TASK_BASE + ((hash & 0x7fffffff) % DOWNLOAD_TASK_SPAN);
    }

    public static boolean containsPdfTask(int notificationId) {
        return notificationId >= PDF_TASK_BASE && notificationId <= PDF_TASK_LIMIT;
    }

    public static boolean containsDownloadTask(int notificationId) {
        return notificationId >= DOWNLOAD_TASK_BASE && notificationId <= DOWNLOAD_TASK_LIMIT;
    }
}
