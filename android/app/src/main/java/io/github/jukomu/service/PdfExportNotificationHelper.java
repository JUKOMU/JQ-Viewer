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
 */
public class PdfExportNotificationHelper {

    private static final String CHANNEL_ID = "pdf_export";
    private static final String CHANNEL_NAME = "PDF导出";
    private static final int ICON = R.mipmap.ic_launcher;

    private final Context context;
    private final NotificationManager manager;
    private int notificationId;

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

    /** 直接设置通知 ID */
    public void assignNotificationId(int id) {
        this.notificationId = id;
    }

    /** 导出开始时显示"准备中"通知，确保用户知道后台任务已启动 */
    public void showPreparing(String chapterTitle) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("准备导出 PDF...")
            .setContentText(chapterTitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
        manager.notify(notificationId, notification);
    }

    /** 所有页面已处理完，正在序列化写入磁盘 */
    public void showWriting(String chapterTitle) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("正在写入 PDF 文件...")
            .setContentText(chapterTitle)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
        manager.notify(notificationId, notification);
    }

    /** 显示进度通知（不定进度条，仅更新文字避免系统限流） */
    public void showProgress(String chapterTitle, int currentPage, int totalPages) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("正在导出 PDF")
            .setContentText(chapterTitle + " (" + currentPage + "/" + totalPages + ")")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
        manager.notify(notificationId, notification);
    }

    /** 显示完成通知（带点击打开文件） */
    public void showComplete(String chapterTitle, String fileName, String filePath) {
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
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.BigTextStyle textStyle = new NotificationCompat.BigTextStyle();
        textStyle.bigText(chapterTitle + "\n" + fileName);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("导出完成: " + chapterTitle)
            .setContentText(fileName)
            .setStyle(textStyle)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build();
        manager.notify(notificationId, notification);
    }

    /** 显示失败通知 */
    public void showError(String chapterTitle, String error) {
        String message = error != null && !error.isEmpty()
            ? error
            : "导出失败";

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("导出失败: " + chapterTitle)
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .build();
        manager.notify(notificationId, notification);
    }

    /** 取消通知 */
    public void cancel() {
        manager.cancel(notificationId);
    }

    /** 获取基础通知 ID（分卷时在此基础 + vol 偏移） */
    public int getBaseNotificationId() {
        return notificationId;
    }

    /** 获取当前通知 ID */
    public int getNotificationId() {
        return notificationId;
    }
}
