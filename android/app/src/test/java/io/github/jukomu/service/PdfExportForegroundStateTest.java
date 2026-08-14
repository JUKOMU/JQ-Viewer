package io.github.jukomu.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PdfExportForegroundStateTest {

    @Test
    public void newlyQueuedTasksDoNotReplaceRunningTaskIdentity() {
        PdfExportForegroundState state = new PdfExportForegroundState();
        state.updateRunning("running", 7, "正在导出的本子", "正在导出", 12, 80, 1, 2);

        PdfExportForegroundState.Snapshot snapshot = state.snapshot(21);

        assertEquals("running", snapshot.exportId);
        assertEquals(7, snapshot.sessionId);
        assertEquals("正在导出的本子", snapshot.title);
        assertEquals(21, snapshot.activeCount);
        assertEquals(20, snapshot.queueRemaining);
        assertEquals(12, snapshot.currentPage);
    }

    @Test
    public void queuedOnlySnapshotHasNoCancelTarget() {
        PdfExportForegroundState state = new PdfExportForegroundState();

        PdfExportForegroundState.Snapshot snapshot = state.snapshot(20);

        assertEquals("", snapshot.exportId);
        assertEquals("排队中", snapshot.phase);
        assertEquals(20, snapshot.queueRemaining);
    }

    @Test
    public void staleCompletionCannotClearNewRunningTask() {
        PdfExportForegroundState state = new PdfExportForegroundState();
        state.updateRunning("old", 1, "旧任务", "正在导出", 1, 10, 1, 1);
        state.updateRunning("new", 2, "新任务", "准备导出", 0, 0, 0, 0);

        state.clearRunning("old");

        assertEquals("new", state.snapshot(1).exportId);
    }
}
