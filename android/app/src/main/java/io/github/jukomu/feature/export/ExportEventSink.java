package io.github.jukomu.feature.export;

import org.json.JSONObject;

/**
 * 文件导出进度事件出口。
 */
public interface ExportEventSink {
    void onExportProgress(JSONObject snapshot);
}
