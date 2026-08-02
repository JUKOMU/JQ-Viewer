package io.github.jukomu.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import io.github.jukomu.R;

import java.io.File;

/**
 * PDF 导出系统通知辅助类。
 * 每任务 notificationId 只用于完成或失败终态。
 */
public class PdfExportNotificationHelper {

    private static final String TAG = "PdfExportNotification";
    private static final String CHANNEL_ID = "pdf_export";
    private static final String CHANNEL_NAME = "PDF导出";
    private static final int ICON = R.mipmap.ic_launcher;

    private final Context context;
    private final NotificationManager manager;

    public PdfExportNotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("PDF导出进度通知");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    public void showComplete(int notificationId, String chapterTitle, String fileName, String filePath) {
        showComplete(notificationId, chapterTitle, fileName, filePath, null);
    }

    public void showComplete(int notificationId, String chapterTitle, String fileName, String filePath,
                             String detail) {
        PendingIntent pendingIntent = createPdfOpenIntent(notificationId, filePath);

        String message = fileName;
        if (detail != null && !detail.isEmpty()) {
            message = fileName + "\n" + detail;
        }
        NotificationCompat.BigTextStyle textStyle = new NotificationCompat.BigTextStyle();
        textStyle.bigText(chapterTitle + "\n" + message);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("导出完成: " + chapterTitle)
            .setContentText(fileName)
            .setStyle(textStyle)
            .setAutoCancel(true)
            .setOngoing(false);
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }
        notify(notificationId, builder.build());
    }

    private PendingIntent createPdfOpenIntent(int notificationId, String filePath) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        try {
            Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                new File(filePath)
            );
            openIntent.setDataAndType(uri, "application/pdf");
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE
            );
        } catch (RuntimeException e) {
            Log.d(TAG, "PDF 完成通知打开入口创建失败", e);
            return null;
        }
    }

    public void showError(int notificationId, String chapterTitle, String error) {
        String message = error != null && !error.isEmpty() ? error : "导出失败";
        NotificationCompat.BigTextStyle textStyle = new NotificationCompat.BigTextStyle();
        textStyle.bigText(message);

        notify(notificationId, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("导出失败: " + chapterTitle)
            .setContentText(message)
            .setStyle(textStyle)
            .setAutoCancel(true)
            .setOngoing(false)
            .build());
    }

    public void cancel(int notificationId) {
        if (manager == null) return;
        try {
            manager.cancel(notificationId);
        } catch (SecurityException e) {
            Log.d(TAG, "通知权限未授予，跳过取消 PDF 通知", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "取消 PDF 通知失败", e);
        }
    }

    private void notify(int notificationId, android.app.Notification notification) {
        if (manager == null) return;
        try {
            manager.notify(notificationId, notification);
        } catch (SecurityException e) {
            Log.d(TAG, "通知权限未授予，跳过 PDF 通知", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "发布 PDF 通知失败", e);
        }
    }
}
