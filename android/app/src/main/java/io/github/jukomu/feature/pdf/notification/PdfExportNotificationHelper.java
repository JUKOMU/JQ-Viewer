package io.github.jukomu.feature.pdf.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import io.github.jukomu.R;
import io.github.jukomu.MainActivity;
import io.github.jukomu.feature.pdf.data.PdfRef;
import io.github.jukomu.feature.pdf.data.PdfRefResolver;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 文件导出系统通知辅助类。
 * 每任务 notificationId 只用于完成或失败终态。
 */
public class PdfExportNotificationHelper {

    private static final String TAG = "PdfExportNotification";
    private static final String CHANNEL_ID = "pdf_export";
    private static final String CHANNEL_NAME = "文件导出";
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
            channel.setDescription("文件导出进度通知");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    public void showComplete(int notificationId, String chapterTitle, String fileName,
                             String outputFileRef) {
        showComplete(notificationId, "pdf", chapterTitle, fileName, outputFileRef, null);
    }

    public void showComplete(int notificationId, String chapterTitle, String fileName,
                             String outputFileRef,
                             String detail) {
        showComplete(notificationId, "pdf", chapterTitle, fileName, outputFileRef, detail);
    }

    public void showComplete(int notificationId, String format, String chapterTitle,
                             String fileName, String outputFileRef, String detail) {
        PendingIntent pendingIntent = createOpenIntent(notificationId, format, outputFileRef);

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

    private PendingIntent createOpenIntent(int notificationId, String format, String outputFileRef) {
        try {
            Intent openIntent;
            if ("zip".equals(format)) {
                Uri folderUri = resolveFolderUri(outputFileRef);
                openIntent = new Intent(Intent.ACTION_VIEW);
                openIntent.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
                openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                String reader = "cbz".equals(format) ? "/cbz-reader" : "/pdf-reader";
                String route = reader + "?fileRef=" + Uri.encode(outputFileRef);
                openIntent = new Intent(context, MainActivity.class);
                openIntent.setAction(MainActivity.ACTION_OPEN_ROUTE);
                openIntent.putExtra(MainActivity.EXTRA_ROUTE, route);
                openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
            return PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE
            );
        } catch (Exception e) {
            Log.d(TAG, "导出完成通知打开入口创建失败", e);
            return null;
        }
    }

    private Uri resolveFolderUri(String fileRef) throws Exception {
        PdfRef.Parsed parsed = PdfRef.parse(fileRef);
        if (parsed.kind != PdfRef.Kind.FILE) throw new IllegalArgumentException("需要文件引用");
        if (parsed.provider == PdfRef.Provider.SAF) {
            Uri fileUri = PdfRefResolver.uri(fileRef);
            String documentId = DocumentsContract.getDocumentId(fileUri);
            int separator = documentId.lastIndexOf('/');
            String parentDocumentId;
            if (separator >= 0) {
                parentDocumentId = documentId.substring(0, separator);
            } else {
                int volumeSeparator = documentId.indexOf(':');
                if (volumeSeparator < 0) throw new IllegalArgumentException("无法确定文档所在文件夹");
                parentDocumentId = documentId.substring(0, volumeSeparator + 1);
            }
            if (fileUri.getPath() != null && fileUri.getPath().contains("/tree/")) {
                return DocumentsContract.buildDocumentUriUsingTree(fileUri, parentDocumentId);
            }
            return DocumentsContract.buildDocumentUri(fileUri.getAuthority(), parentDocumentId);
        }

        File file = PdfRefResolver.pathFile(fileRef);
        File parent = file.getCanonicalFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new FileNotFoundException("Parent folder not found: " + fileRef);
        }
        String parentPath = parent.getCanonicalPath();
        String primaryPath = Environment.getExternalStorageDirectory().getCanonicalPath();
        if (parentPath.equals(primaryPath) || parentPath.startsWith(primaryPath + File.separator)) {
            String relativePath = parentPath.substring(primaryPath.length()).replace(
                File.separatorChar, '/');
            if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            String documentId = relativePath.isEmpty() ? "primary:" : "primary:" + relativePath;
            return DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", documentId);
        }
        return FileProvider.getUriForFile(
            context, context.getPackageName() + ".fileprovider", parent);
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
            Log.d(TAG, "通知权限未授予，跳过取消导出通知", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "取消导出通知失败", e);
        }
    }

    private void notify(int notificationId, android.app.Notification notification) {
        if (manager == null) return;
        try {
            manager.notify(notificationId, notification);
        } catch (SecurityException e) {
            Log.d(TAG, "通知权限未授予，跳过导出通知", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "发布导出通知失败", e);
        }
    }
}
