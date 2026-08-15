package io.github.jukomu.feature.pdf.export;

import org.json.JSONObject;

/**
 * PDF 导出进度事件出口。
 */
public interface PdfExportEventSink {
    void onExportProgress(JSONObject snapshot);
}
