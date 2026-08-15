package io.github.jukomu.feature.pdf.export;

/**
 * PDF 通知动作命令入口。
 */
public interface PdfExportCommandPort {
    void cancelExport(String exportId);
}
