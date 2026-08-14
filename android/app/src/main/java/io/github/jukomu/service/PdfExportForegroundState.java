package io.github.jukomu.service;

/**
 * Owns the foreground identity of the single running PDF export.
 */
final class PdfExportForegroundState {

    private RunningTask runningTask;

    synchronized void updateRunning(String exportId, int sessionId, String title, String phase,
            int currentPage, int totalPages, int volumeIndex, int totalVolumes) {
        runningTask = new RunningTask(
            exportId, sessionId, title, phase, currentPage, totalPages, volumeIndex, totalVolumes);
    }

    synchronized void clearRunning(String exportId) {
        if (runningTask != null && runningTask.exportId.equals(exportId)) {
            runningTask = null;
        }
    }

    synchronized Snapshot snapshot(int activeCount) {
        int normalizedActiveCount = Math.max(0, activeCount);
        if (runningTask == null) {
            return new Snapshot(
                "", 0, normalizedActiveCount, normalizedActiveCount,
                "PDF 导出", normalizedActiveCount > 0 ? "排队中" : "已结束",
                0, 0, 0, 0);
        }
        return new Snapshot(
            runningTask.exportId,
            runningTask.sessionId,
            normalizedActiveCount,
            Math.max(0, normalizedActiveCount - 1),
            runningTask.title,
            runningTask.phase,
            runningTask.currentPage,
            runningTask.totalPages,
            runningTask.volumeIndex,
            runningTask.totalVolumes
        );
    }

    static final class Snapshot {
        final String exportId;
        final int sessionId;
        final int activeCount;
        final int queueRemaining;
        final String title;
        final String phase;
        final int currentPage;
        final int totalPages;
        final int volumeIndex;
        final int totalVolumes;

        Snapshot(String exportId, int sessionId, int activeCount, int queueRemaining,
                String title, String phase, int currentPage, int totalPages,
                int volumeIndex, int totalVolumes) {
            this.exportId = exportId;
            this.sessionId = sessionId;
            this.activeCount = activeCount;
            this.queueRemaining = queueRemaining;
            this.title = title;
            this.phase = phase;
            this.currentPage = currentPage;
            this.totalPages = totalPages;
            this.volumeIndex = volumeIndex;
            this.totalVolumes = totalVolumes;
        }
    }

    private static final class RunningTask {
        final String exportId;
        final int sessionId;
        final String title;
        final String phase;
        final int currentPage;
        final int totalPages;
        final int volumeIndex;
        final int totalVolumes;

        RunningTask(String exportId, int sessionId, String title, String phase,
                int currentPage, int totalPages, int volumeIndex, int totalVolumes) {
            this.exportId = exportId;
            this.sessionId = sessionId;
            this.title = title;
            this.phase = phase;
            this.currentPage = currentPage;
            this.totalPages = totalPages;
            this.volumeIndex = volumeIndex;
            this.totalVolumes = totalVolumes;
        }
    }
}
