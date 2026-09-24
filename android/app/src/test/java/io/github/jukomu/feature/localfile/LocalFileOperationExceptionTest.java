package io.github.jukomu.feature.pdf;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LocalFileOperationExceptionTest {

    @Test
    public void stableReasonsDoNotDependOnDisplayMessage() {
        LocalFileOperationException missing = LocalFileOperationException.notFound(
            "PDF 文件记录不存在");
        LocalFileOperationException permission = LocalFileOperationException.permissionDenied(
            "PDF_INACCESSIBLE: 没有权限删除 PDF", new SecurityException("provider"));
        LocalFileOperationException conflict = LocalFileOperationException.conflict(
            "当前任务状态不能重试");

        assertEquals(LocalFileOperationException.NOT_FOUND, missing.code);
        assertEquals("PDF 文件记录不存在", missing.getMessage());
        assertEquals(LocalFileOperationException.PERMISSION_DENIED, permission.code);
        assertEquals("PDF_INACCESSIBLE: 没有权限删除 PDF", permission.getMessage());
        assertEquals(LocalFileOperationException.CONFLICT, conflict.code);
        assertEquals("当前任务状态不能重试", conflict.getMessage());
    }
}
