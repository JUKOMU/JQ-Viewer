package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.feature.ocr.model.OcrResponse;

import java.nio.file.Path;

/** OCR 引擎边界，避免 bridge 与 native 实现耦合。 */
public interface OcrEngine extends AutoCloseable {
    OcrResponse recognize(Path image);

    @Override
    void close();
}
