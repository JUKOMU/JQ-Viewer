package io.github.jukomu.service;

final class NotificationIds {

    static final int PDF_FOREGROUND = 202001;
    static final int DOWNLOAD_QUEUE_SUMMARY = 203000;
    static final int DOWNLOAD_FOREGROUND = 203001;

    static final int PDF_TASK_BASE = 210000;
    static final int PDF_TASK_LIMIT = 219999;
    static final int DOWNLOAD_TASK_BASE = 230000;
    static final int DOWNLOAD_TASK_LIMIT = 329999;

    private static final int PDF_TASK_SPAN = PDF_TASK_LIMIT - PDF_TASK_BASE + 1;
    private static final int DOWNLOAD_TASK_SPAN = DOWNLOAD_TASK_LIMIT - DOWNLOAD_TASK_BASE + 1;

    private NotificationIds() {
    }

    static int pdfTask(int sequence) {
        return PDF_TASK_BASE + Math.floorMod(sequence, PDF_TASK_SPAN);
    }

    static int downloadTask(String taskId) {
        int hash = taskId == null ? 0 : taskId.hashCode();
        return DOWNLOAD_TASK_BASE + ((hash & 0x7fffffff) % DOWNLOAD_TASK_SPAN);
    }

    static boolean containsPdfTask(int notificationId) {
        return notificationId >= PDF_TASK_BASE && notificationId <= PDF_TASK_LIMIT;
    }

    static boolean containsDownloadTask(int notificationId) {
        return notificationId >= DOWNLOAD_TASK_BASE && notificationId <= DOWNLOAD_TASK_LIMIT;
    }
}
