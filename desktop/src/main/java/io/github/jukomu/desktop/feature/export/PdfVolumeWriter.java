package io.github.jukomu.desktop.feature.export;

import java.nio.file.Path;
import java.util.List;

/** 单个 PDF 分卷写入边界，便于稳定验证取消和失败状态。 */
@FunctionalInterface
public interface PdfVolumeWriter {
    void write(
            List<Path> images,
            Path temporaryFile,
            boolean useOriginal,
            double compressionRatio,
            Progress progress
    ) throws Exception;

    interface Progress {
        void pageWritten(int pageCount) throws Exception;
    }
}
