package io.github.jukomu.feature.pdf.export;

import android.util.Log;

/**
 * 进程级 PDF 通知动作路由。
 */
public final class PdfExportCommandRouter {
    private static final String TAG = "PdfExportCommandRouter";
    private static final PdfExportCommandRouter INSTANCE = new PdfExportCommandRouter();

    private volatile PdfExportCommandPort delegate;

    private PdfExportCommandRouter() {
    }

    public static PdfExportCommandRouter getInstance() {
        return INSTANCE;
    }

    public synchronized void attach(PdfExportCommandPort commandPort) {
        delegate = commandPort;
    }

    public synchronized void detach(PdfExportCommandPort expected) {
        if (delegate == expected) {
            delegate = null;
        }
    }

    public void cancelExport(String exportId) {
        if (exportId == null || exportId.isEmpty()) {
            return;
        }
        PdfExportCommandPort current = delegate;
        if (current == null) {
            Log.w(TAG, "PDF 通知取消到达时命令入口未绑定: " + exportId);
            return;
        }
        try {
            current.cancelExport(exportId);
        } catch (RuntimeException error) {
            Log.w(TAG, "处理 PDF 通知取消失败: " + exportId, error);
        }
    }
}
