package io.github.jukomu.feature.update;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class UpdateDownloadDeadlineTest {

    @Test
    public void absoluteDeadlineKeepsTheChineseMessageAndCause() {
        SocketTimeoutException cause = new SocketTimeoutException("Read timed out");
        IOException failure = UpdateDownloadRules.readFailure(true, cause, "下载总时限", "下载空闲超时");
        assertEquals("下载总时限", failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    public void idleTimeoutKeepsTheChineseMessageAndCause() {
        SocketTimeoutException cause = new SocketTimeoutException("Read timed out");
        IOException failure = UpdateDownloadRules.readFailure(false, cause, "下载总时限", "下载空闲超时");
        assertEquals("下载空闲超时", failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    public void otherIoErrorStaysUnchangedBeforeTheDeadline() {
        IOException cause = new IOException("HTTP 404");
        assertSame(cause, UpdateDownloadRules.readFailure(false, cause, "下载总时限", "下载空闲超时"));
    }

    @Test
    public void bothSourcesFailedNamesEachReason() {
        assertEquals("GitHub 与 Gitee 更新包均下载失败。GitHub: HTTP 404；Gitee: 下载空闲超时",
                UpdateDownloadRules.failureIfDownloadIncomplete(null, false, "HTTP 404", "下载空闲超时"));
    }

    @Test
    public void selectedSourceFailureReportsTheNetworkError() {
        assertEquals("下载总时限", UpdateDownloadRules.failureIfDownloadIncomplete(
                UpdateRaceState.Source.GITHUB, false, "下载总时限", "未知错误"));
    }

    @Test
    public void completedWinnerContinuesToVerification() {
        assertNull(UpdateDownloadRules.failureIfDownloadIncomplete(
                UpdateRaceState.Source.GITEE, true, "HTTP 404", "未知错误"));
    }

    @Test
    public void missingReasonsUseUnknown() {
        String message = UpdateDownloadRules.failureIfDownloadIncomplete(null, false, "", null);
        assertTrue(message.contains("GitHub: 未知错误"));
        assertTrue(message.contains("Gitee: 未知错误"));
    }
}
