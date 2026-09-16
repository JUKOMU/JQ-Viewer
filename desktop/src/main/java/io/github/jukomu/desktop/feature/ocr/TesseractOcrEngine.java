package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.ocr.model.OcrResponse;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.leptonica.PIX;
import org.bytedeco.leptonica.global.leptonica;
import org.bytedeco.tesseract.ETEXT_DESC;
import org.bytedeco.tesseract.TessBaseAPI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.bytedeco.tesseract.global.tesseract.OEM_LSTM_ONLY;
import static org.bytedeco.tesseract.global.tesseract.PSM_AUTO;

/** 基于 JavaCPP 原生绑定的 Tesseract fast OCR 实现。 */
public final class TesseractOcrEngine implements OcrEngine {
    private static final String LANGUAGES = "chi_sim+chi_sim_vert+eng";
    private static final int TIMEOUT_MILLIS = 30_000;

    private final Path ocrDirectory;
    private TessBaseAPI api;
    private boolean closed;

    public TesseractOcrEngine(Path ocrDirectory) {
        this.ocrDirectory = ocrDirectory.toAbsolutePath().normalize();
    }

    @Override
    public synchronized OcrResponse recognize(Path image) {
        if (closed) throw ApiException.unavailable("OCR 服务已关闭");
        if (image == null || !Files.isRegularFile(image)) {
            throw ApiException.notFound("图片文件不存在");
        }

        try {
            TessBaseAPI tesseract = api();
            byte[] bytes = Files.readAllBytes(image);
            try (PIX pix = leptonica.pixReadMem(bytes, bytes.length)) {
                if (pix == null || pix.isNull()) {
                    return OcrResponse.failure("无法读取图片");
                }
                tesseract.SetImage(pix);
                tesseract.SetPageSegMode(PSM_AUTO);
                try (ETEXT_DESC monitor = new ETEXT_DESC()) {
                    monitor.set_deadline_msecs(TIMEOUT_MILLIS);
                    int status = tesseract.Recognize(monitor);
                    if (monitor.deadline_exceeded()) {
                        return OcrResponse.failure("识别超时，请重试");
                    }
                    if (status != 0) {
                        return OcrResponse.failure("识别失败，请重试");
                    }
                    try (BytePointer text = tesseract.GetUTF8Text()) {
                        String value = text == null || text.isNull()
                                ? ""
                                : text.getString(StandardCharsets.UTF_8).trim();
                        return value.isEmpty()
                                ? OcrResponse.failure("未识别到文字")
                                : new OcrResponse(value, "");
                    }
                }
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            return OcrResponse.failure("识别失败，请重试");
        }
    }

    private TessBaseAPI api() {
        if (api != null) return api;
        Path dataPath = TesseractModelStore.prepare(ocrDirectory);
        TessBaseAPI created = new TessBaseAPI();
        int status = created.Init(dataPath.toString(), LANGUAGES, OEM_LSTM_ONLY);
        if (status != 0) {
            created.close();
            throw ApiException.unavailable("OCR 模型初始化失败");
        }
        api = created;
        return created;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (api != null) {
            api.End();
            api.close();
            api = null;
        }
    }
}
