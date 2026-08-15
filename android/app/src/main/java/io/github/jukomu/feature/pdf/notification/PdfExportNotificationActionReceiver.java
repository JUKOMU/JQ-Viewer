package io.github.jukomu.feature.pdf.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import io.github.jukomu.feature.pdf.export.PdfExportCommandRouter;

/**
 * Handles the explicit cancel action shown on the ongoing PDF notification.
 */
public class PdfExportNotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_CANCEL = "io.github.jukomu.PDF_EXPORT_NOTIFICATION_CANCEL";
    public static final String EXTRA_EXPORT_ID = "export_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            String exportId = intent.getStringExtra(EXTRA_EXPORT_ID);
            if (exportId == null || exportId.isEmpty()) return;
            PdfExportCommandRouter.getInstance().cancelExport(exportId);
        }
    }
}
