package io.github.jukomu.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import io.github.jukomu.R;
import java.io.File;

/**
 * PDF 导出系统通知辅助类。
 * 所有方法显式传入 notificationId，无内部可变状态，线程安全。
 */
public class PdfExportNotificationHelper {

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    public void showQueued(int notificationId, String chapterTitle) {
        manager.notify(notificationId, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("排队中")
            .setContentText(chapterTitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build());
    }

    public void showPreparing(int notificationId, String chapterTitle) {
        manager.notify(notificationId, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("准备导出 PDF...")
            .setContentText(chapterTitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build());
    }

    public void showProgress(int notificationId, String chapterTitle, int currentPage, int totalPages) {
        manager.notify(notificationId, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("正在导出 PDF")
            .setContentText(chapterTitle + " (" + currentPage + "/" + totalPages + ")")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build());
    }

    public void showWriting(int notificationId, String chapterTitle) {
        manager.notify(notificationId, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("正在写入 PDF 文件...")
            .setContentText(chapterTitle)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build());
    }

    public void showComplete(int notificationId, String chapterTitle, String fileName, String filePath) {
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        Uri uri = FileProvider.getUriForFile(
            context,
            context.getPackageName() + ".fileprovider",
            new File(filePath)
        );
        openIntent.setDataAndType(uri, "application/pdf");
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.BigTextStyle textStyle = new NotificationCompat.BigTextStyle();
        textStyle.bigText(chapterTitle + "\n" + fileName);

        manager.notify(notificationId, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("导出完成: " + chapterTitle)
            .setContentText(fileName)
            .setStyle(textStyle)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build());
    }

    public void showError(int notificationId, String chapterTitle, String error) {
        String message = error != null && !error.isEmpty() ? error : "导出失败";
        manager.notify(notificationId, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("导出失败: " + chapterTitle)
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .build());
    }

    public void cancel(int notificationId) {
        manager.cancel(notificationId);
    }
}
