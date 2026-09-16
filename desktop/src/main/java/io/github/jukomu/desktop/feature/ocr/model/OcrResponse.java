package io.github.jukomu.desktop.feature.ocr.model;

/** 与 Android OCR bridge 对齐的识别结果。取消时 text/error 均为空。 */
public record OcrResponse(String text, String error) {
    public static OcrResponse cancelled() {
        return new OcrResponse("", "");
    }

    public static OcrResponse failure(String message) {
        return new OcrResponse("", message);
    }
}
