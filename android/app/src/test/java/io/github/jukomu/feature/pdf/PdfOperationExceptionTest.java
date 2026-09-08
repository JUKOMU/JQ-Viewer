package io.github.jukomu.feature.pdf;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PdfOperationExceptionTest {

    @Test
    public void stableReasonsDoNotDependOnDisplayMessage() {
        PdfOperationException missing = PdfOperationException.notFound(
            "PDF 文件记录不存在");
        PdfOperationException permission = PdfOperationException.permissionDenied(
            "PDF_INACCESSIBLE: 没有权限删除 PDF", new SecurityException("provider"));
        PdfOperationException conflict = PdfOperationException.conflict(
            "当前任务状态不能重试");

        assertEquals(PdfOperationException.NOT_FOUND, missing.code);
        assertEquals("PDF 文件记录不存在", missing.getMessage());
        assertEquals(PdfOperationException.PERMISSION_DENIED, permission.code);
        assertEquals("PDF_INACCESSIBLE: 没有权限删除 PDF", permission.getMessage());
        assertEquals(PdfOperationException.CONFLICT, conflict.code);
        assertEquals("当前任务状态不能重试", conflict.getMessage());
    }
}
