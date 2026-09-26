package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.Request;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.feature.ocr.OcrService;
import io.github.jukomu.desktop.feature.settings.model.BooleanSettingRequest;
import io.javalin.http.Context;

/**
 * 处理 Desktop OCR 设置、图片选择和识别请求。
 */
public final class OcrPluginHandler {
    private final RequestExecutor requests;
    private final OcrService ocr;

    public OcrPluginHandler(RequestExecutor requests, OcrService ocr) {
        this.requests = requests;
        this.ocr = ocr;
    }

    public void setOcrEnabled(Context context) {
        requests.run(context, BooleanSettingRequest.class,
            request -> ocr.setEnabled(Request.bool(request.enabled(), true)));
    }

    public void pickImageAndOcr(Context context) {
        requests.runLongOperation(context, ocr::pickImageAndOcr);
    }
}
