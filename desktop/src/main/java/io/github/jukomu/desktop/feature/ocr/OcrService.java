package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.ocr.model.OcrResponse;
import io.github.jukomu.desktop.feature.settings.SettingsService;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Desktop OCR 用例：图片选择、开关持久化、串行识别和生命周期清理。
 */
public final class OcrService implements AutoCloseable {
    private final SettingsService settings;
    private final ImagePicker imagePicker;
    private final OcrEngine engine;
    private final AtomicBoolean activeRequest = new AtomicBoolean();
    private volatile boolean closed;

    public OcrService(SettingsService settings, ImagePicker imagePicker, OcrEngine engine) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.imagePicker = Objects.requireNonNull(imagePicker, "imagePicker");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public static OcrService createDefault(SettingsService settings, Path ocrDirectory) {
        return new OcrService(settings, new SystemImagePicker(), new TesseractOcrEngine(ocrDirectory));
    }

    public SuccessResponse setEnabled(boolean enabled) {
        ensureOpen();
        return settings.setOcrEnabled(enabled);
    }

    public OcrResponse pickImageAndOcr() {
        ensureOpen();
        if (!activeRequest.compareAndSet(false, true)) {
            throw ApiException.conflict("另一个 OCR 请求正在进行中");
        }
        try {
            Path image = imagePicker.pickImage();
            return image == null ? OcrResponse.cancelled() : engine.recognize(image);
        } finally {
            activeRequest.set(false);
        }
    }

    private void ensureOpen() {
        if (closed) throw ApiException.unavailable("OCR 服务已关闭");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        engine.close();
    }
}
