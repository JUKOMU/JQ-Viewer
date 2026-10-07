package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.ocr.model.OcrResponse;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Desktop OCR 用例：图片选择、开关持久化、串行识别和生命周期清理。
 */
public final class OcrService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcrService.class);
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
        SuccessResponse response = settings.setOcrEnabled(enabled);
        LOGGER.info("ocr_settings operation=setEnabled status=success enabled={}", enabled);
        return response;
    }

    public OcrResponse pickImageAndOcr() {
        ensureOpen();
        long startedNanos = System.nanoTime();
        if (!activeRequest.compareAndSet(false, true)) {
            LOGGER.warn("ocr_request operation=pick status=failed error=already_active elapsedMs={}",
                elapsedMs(startedNanos));
            throw ApiException.conflict("另一个 OCR 请求正在进行中");
        }
        LOGGER.info("ocr_request operation=pick status=started elapsedMs=0");
        try {
            Path image = imagePicker.pickImage();
            if (image == null) {
                LOGGER.info("ocr_request operation=pick status=cancelled elapsedMs={}",
                    elapsedMs(startedNanos));
                return OcrResponse.cancelled();
            }
            OcrResponse response = engine.recognize(image);
            if (response.error() != null && !response.error().isBlank()) {
                LOGGER.warn("ocr_request operation=pick status=failed error={} elapsedMs={}",
                    errorCategory(response.error()), elapsedMs(startedNanos));
            } else {
                LOGGER.info("ocr_request operation=pick status=success textLength={} elapsedMs={}",
                    response.text() == null ? 0 : response.text().length(), elapsedMs(startedNanos));
            }
            return response;
        } catch (ApiException exception) {
            if ("cancelled".equals(exception.code())) {
                LOGGER.info("ocr_request operation=pick status=cancelled elapsedMs={}",
                    elapsedMs(startedNanos));
            } else {
                LOGGER.warn("ocr_request operation=pick status=failed error={} errorClass={} elapsedMs={}",
                    exception.code(), exception.getClass().getSimpleName(), elapsedMs(startedNanos));
            }
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn("ocr_request operation=pick status=failed error=runtime errorClass={} elapsedMs={}",
                exception.getClass().getSimpleName(), elapsedMs(startedNanos));
            throw exception;
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
        LOGGER.info("ocr_service operation=close status=success");
    }

    private static String errorCategory(String error) {
        if (error == null || error.isBlank()) return "unknown";
        if (error.contains("过大")) return "image_too_large";
        if (error.contains("超时")) return "timeout";
        if (error.contains("文字")) return "empty_result";
        return "recognition_failed";
    }

    private static long elapsedMs(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - startedNanos);
    }
}
