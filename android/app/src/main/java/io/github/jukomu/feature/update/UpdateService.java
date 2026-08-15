package io.github.jukomu.feature.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.getcapacitor.JSObject;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Android 原生应用内更新服务。
 */
public final class UpdateService {

    private static final String TAG = "UpdateService";
    private static final String GITHUB_MANIFEST_URL =
        "https://github.com/JUKOMU/JQ-Viewer/releases/latest/download/latest.json";
    // Gitee 没有稳定的 releases/latest/download/latest.json 路径，先取正式 Release API，
    // 再从其附件中定位 latest.json。
    private static final String GITEE_RELEASE_API_URL =
        "https://gitee.com/api/v5/repos/jukomu/jq-viewer/releases/latest";
    private static final String UPDATE_DIRECTORY = "update";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MANIFEST_MAX_BYTES = 1024 * 1024;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 300L * 1024L * 1024L;

    private final Context context;
    private final File updateDirectory;
    private final ThreadPoolExecutor executor;
    private final ThreadPoolExecutor manifestExecutor;
    private final Object stateLock = new Object();
    private final AtomicInteger revision = new AtomicInteger();
    private volatile Consumer<Snapshot> progressSink;
    private volatile Snapshot latestSnapshot = Snapshot.idle();
    private volatile UpdateManifest currentManifest;
    private volatile UpdateSession activeSession;
    private volatile File readyFile;
    private volatile UpdateManifest readyManifest;
    private volatile boolean installPermissionPending;

    public UpdateService(Context context) {
        this.context = context.getApplicationContext();
        this.updateDirectory = new File(this.context.getFilesDir(), UPDATE_DIRECTORY);
        this.executor = createExecutor(3, "app-update-");
        this.manifestExecutor = createExecutor(2, "app-update-manifest-");
    }

    /**
     * 设置当前 Bridge 会话的更新事件出口。
     */
    public void setProgressSink(Consumer<Snapshot> sink) {
        progressSink = sink;
    }

    /**
     * 独立获取 GitHub/Gitee 正式版 manifest，并按结果矩阵选择下载依据。
     */
    public void checkUpdate(Consumer<CheckResult> callback) {
        currentManifest = null;
        executor.execute(() -> {
            try {
                Future<ManifestAttempt> githubFuture = manifestExecutor.submit(
                    () -> fetchManifestAttempt(GITHUB_MANIFEST_URL, false));
                Future<ManifestAttempt> giteeFuture = manifestExecutor.submit(
                    () -> fetchManifestAttempt(GITEE_RELEASE_API_URL, true));
                ManifestResolution resolution = resolveManifests(
                    githubFuture.get(), giteeFuture.get());
                if (!resolution.success) {
                    publish("failed", "", 0L, 0L, 0L, resolution.error);
                    callback.accept(CheckResult.failure(resolution.error));
                    return;
                }
                UpdateManifest manifest = resolution.manifest;
                if (!isNewerThanInstalled(manifest)) {
                    currentManifest = manifest;
                    publish("up_to_date", "", 0L, 0L, manifest.getSizeBytes());
                    callback.accept(CheckResult.upToDate(manifest));
                    return;
                }
                currentManifest = manifest;
                publish("update_available", "", 0L, 0L, manifest.getSizeBytes());
                callback.accept(CheckResult.available(manifest));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                currentManifest = null;
                publish("failed", "", 0L, 0L, 0L, "更新检查已取消");
                callback.accept(CheckResult.failure("更新检查已取消"));
            } catch (ExecutionException error) {
                currentManifest = null;
                String message = userMessage(error);
                publish("failed", "", 0L, 0L, 0L, message);
                callback.accept(CheckResult.failure(message));
            } catch (Exception error) {
                currentManifest = null;
                String message = userMessage(error);
                publish("failed", "", 0L, 0L, 0L, message);
                callback.accept(CheckResult.failure(message));
            }
        });
    }

    /**
     * 使用最近一次成功检查的 manifest 开始双源竞速下载。
     */
    public boolean startUpdate() {
        synchronized (stateLock) {
            if (activeSession != null) {
                return false;
            }
            UpdateManifest manifest = currentManifest;
            if (manifest == null || !isNewerThanInstalled(manifest)) {
                return false;
            }
            if (!isNotificationAvailable()) {
                publish("failed", "", 0L, 0L, manifest.getSizeBytes(), "通知不可用");
                return false;
            }
            if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                publish("failed", "", 0L, 0L, manifest.getSizeBytes(), "无法创建更新目录");
                return false;
            }
            deleteFiles(updateDirectory);
            readyFile = null;
            readyManifest = null;
            UpdateSession session = new UpdateSession(manifest, updateDirectory,
                revision.incrementAndGet());
            activeSession = session;
            publish("racing", "racing", 0L, 0L, manifest.getSizeBytes());
            executor.execute(() -> downloadSource(
                session, UpdateRaceState.Source.GITHUB, manifest.getGithubUrl()));
            executor.execute(() -> downloadSource(
                session, UpdateRaceState.Source.GITEE, manifest.getGiteeUrl()));
            executor.execute(() -> finishSession(session));
            return true;
        }
    }

    /**
     * 取消当前更新并删除临时文件。
     */
    public void cancelUpdate() {
        UpdateSession session;
        synchronized (stateLock) {
            session = activeSession;
            if (session == null) {
                return;
            }
            session.raceState.cancel();
            session.cancelled = true;
        }
        closeConnections(session);
        publish("cancelled", "", session.githubBytes, session.giteeBytes,
            session.manifest.getSizeBytes());
        cleanupSession(session);
    }

    /**
     * 检查已校验 APK 是否可以直接交给系统安装器。
     *
     * <p>未知来源权限由前端先展示说明；本方法只返回需要权限，不会未经用户确认跳转设置页。
     */
    public InstallResult installUpdate(Activity activity) {
        File apkFile = readyFile;
        UpdateManifest manifest = readyManifest;
        if (apkFile == null || manifest == null || !apkFile.isFile()) {
            String message = "没有可安装的更新包";
            publish("failed", "", 0L, 0L, 0L, message);
            return InstallResult.failure(message);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !context.getPackageManager().canRequestPackageInstalls()) {
            installPermissionPending = false;
            publish("install_permission_required", "", apkFile.length(), apkFile.length(),
                manifest.getSizeBytes());
            return InstallResult.permissionRequired();
        }
        try {
            launchInstaller(activity, apkFile, manifest);
            return InstallResult.started();
        } catch (Exception error) {
            Log.w(TAG, "启动更新安装器失败", error);
            String message = userMessage(error);
            publish("failed", "", apkFile.length(), apkFile.length(), manifest.getSizeBytes(), message);
            return InstallResult.failure(message);
        }
    }

    /**
     * 用户确认说明后打开 Android 未知来源设置页。
     */
    public boolean requestInstallPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            || context.getPackageManager().canRequestPackageInstalls()) {
            return installUpdate(activity).started;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivityContext(activity).startActivity(intent);
            installPermissionPending = true;
            publish("install_permission_required", "", 0L, 0L,
                readyManifest == null ? 0L : readyManifest.getSizeBytes());
            return true;
        } catch (Exception error) {
            Log.w(TAG, "打开安装来源设置失败", error);
            publish("failed", "", 0L, 0L,
                readyManifest == null ? 0L : readyManifest.getSizeBytes(),
                "无法打开安装来源设置");
            return false;
        }
    }

    /**
     * Activity 回到前台后处理未知来源权限结果；拒绝时不重复打开设置页。
     */
    public void onHostResume(Activity activity) {
        if (!installPermissionPending) {
            return;
        }
        installPermissionPending = false;
        File apkFile = readyFile;
        UpdateManifest manifest = readyManifest;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !context.getPackageManager().canRequestPackageInstalls()) {
            publish("failed", "", 0L, 0L, manifest == null ? 0L : manifest.getSizeBytes(),
                "安装来源权限未授予");
            return;
        }
        if (apkFile == null || manifest == null || !apkFile.isFile()) {
            publish("failed", "", 0L, 0L, 0L, "没有可安装的更新包");
            return;
        }
        try {
            launchInstaller(activity, apkFile, manifest);
        } catch (Exception error) {
            Log.w(TAG, "权限返回后启动更新安装器失败", error);
            publish("failed", "", apkFile.length(), apkFile.length(), manifest.getSizeBytes(),
                userMessage(error));
        }
    }

    private void launchInstaller(Activity activity, File apkFile, UpdateManifest manifest) {
        Context activityContext = getActivityContext(activity);
        Uri apkUri = FileProvider.getUriForFile(
            activityContext,
            context.getPackageName() + ".fileprovider",
            apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(activityContext instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        activityContext.startActivity(intent);
        publish("installing", "", apkFile.length(), apkFile.length(), manifest.getSizeBytes());
        UpdateForegroundService.stop(context, revision.incrementAndGet());
    }

    private Context getActivityContext(Activity activity) {
        return activity == null ? context : activity;
    }

    /**
     * 返回最近一次更新状态。
     */
    public Snapshot getSnapshot() {
        return latestSnapshot;
    }

    /**
     * 关闭更新线程池。
     */
    public void destroy() {
        cancelUpdate();
        executor.shutdownNow();
        manifestExecutor.shutdownNow();
    }

    static ManifestResolution resolveManifests(ManifestAttempt github, ManifestAttempt gitee) {
        if (github.manifest != null && gitee.manifest != null) {
            if (!github.manifest.sameRelease(gitee.manifest)) {
                return ManifestResolution.failure("GitHub 与 Gitee 发布元数据不一致");
            }
            return ManifestResolution.success(github.manifest);
        }
        if (github.manifest != null) {
            return ManifestResolution.success(github.manifest);
        }
        if (gitee.manifest != null) {
            return ManifestResolution.success(gitee.manifest);
        }
        return ManifestResolution.failure("GitHub 与 Gitee 更新元数据均不可用。GitHub: "
            + formatAttemptError(github.error) + "；Gitee: " + formatAttemptError(gitee.error));
    }

    private static String formatAttemptError(String error) {
        return error == null || error.isEmpty() ? "未知错误" : error;
    }

    private ManifestAttempt fetchManifestAttempt(String endpoint, boolean gitee) {
        try {
            return ManifestAttempt.success(fetchManifest(endpoint, gitee));
        } catch (Exception error) {
            return ManifestAttempt.failure(userMessage(error));
        }
    }

    private UpdateManifest fetchManifest(String endpoint, boolean gitee) throws Exception {
        JSONObject payload = fetchJson(endpoint);
        if (!gitee) {
            return UpdateManifest.parse(payload);
        }
        String assetUrl = findGiteeAssetUrl(payload);
        if (assetUrl == null) {
            throw new UpdateManifest.UpdateException("Gitee 没有可用的 latest.json");
        }
        return UpdateManifest.parse(fetchJson(assetUrl));
    }

    private JSONObject fetchJson(String endpoint) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(endpoint);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            byte[] body = readLimited(connection.getInputStream(), MANIFEST_MAX_BYTES);
            return new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONArray fetchArray(String endpoint) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(endpoint);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            byte[] body = readLimited(connection.getInputStream(), MANIFEST_MAX_BYTES);
            return new JSONArray(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String findGiteeAssetUrl(JSONObject release) throws Exception {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null && release.has("id")) {
            JSONArray attachments = fetchArray("https://gitee.com/api/v5/repos/jukomu/jq-viewer/releases/"
                + release.getLong("id") + "/attach_files?per_page=100");
            return findAssetUrl(attachments);
        }
        return findAssetUrl(assets);
    }

    private String findAssetUrl(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset != null && "latest.json".equals(asset.optString("name"))) {
                String url = asset.optString("browser_download_url", "");
                return url.startsWith("https://") ? url : null;
            }
        }
        return null;
    }

    private boolean isNewerThanInstalled(UpdateManifest manifest) {
        PackageManager packageManager = context.getPackageManager();
        try {
            PackageInfo info = packageManager.getPackageInfo(context.getPackageName(), 0);
            long installedVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
            return manifest.getVersionCode() > installedVersionCode;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private boolean isNotificationAvailable() {
        android.app.NotificationManager manager =
            (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = manager.getNotificationChannel("app_update");
            return channel == null
                || channel.getImportance() != android.app.NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    private void downloadSource(UpdateSession session, UpdateRaceState.Source source,
                                String url) {
        File partFile = source == UpdateRaceState.Source.GITHUB
            ? session.githubPart : session.giteePart;
        HttpURLConnection connection = null;
        boolean successful = false;
        try {
            if (session.cancelled || session.raceState.isCancelled()
                || (session.raceState.getWinner() != null
                    && session.raceState.getWinner() != source)) {
                return;
            }
            connection = openConnection(url);
            setConnection(session, source, connection);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw new IOException("更新包过大");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(
                     new FileOutputStream(partFile))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                long downloaded = 0L;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || session.cancelled
                        || session.raceState.isCancelled()) {
                        throw new InterruptedException("更新已取消");
                    }
                    downloaded += count;
                    if (downloaded > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("更新包过大");
                    }
                    output.write(buffer, 0, count);
                    updateBytes(session, source, downloaded);
                    if (session.raceState.trySelectWinner(source, downloaded)) {
                        cancelOtherSource(session, source);
                        publish("selected", sourceName(source), session.githubBytes,
                            session.giteeBytes, session.manifest.getSizeBytes());
                    } else if (session.raceState.getWinner() != null
                        && session.raceState.getWinner() != source) {
                        throw new InterruptedException("另一个下载源已胜出");
                    }
                }
            }
            successful = true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            Log.w(TAG, sourceName(source) + " 更新下载失败", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            clearConnection(session, source, connection);
            session.raceState.sourceFinished(source, successful);
            if (successful && session.raceState.getWinner() == source) {
                cancelOtherSource(session, source);
            }
            session.finishedLatch.countDown();
        }
    }

    private void finishSession(UpdateSession session) {
        try {
            session.finishedLatch.await();
            if (session.cancelled || session.raceState.isCancelled()) {
                return;
            }
            UpdateRaceState.Source winner = session.raceState.getWinner();
            if (winner == null) {
                failSession(session, "两个下载源均未完成");
                return;
            }
            publish("verifying", sourceName(winner), session.githubBytes, session.giteeBytes,
                session.manifest.getSizeBytes());
            File winnerFile = winner == UpdateRaceState.Source.GITHUB
                ? session.githubPart : session.giteePart;
            validateApk(session.manifest, winnerFile);
            File finalFile = new File(updateDirectory, session.manifest.getApkName());
            if (!winnerFile.renameTo(finalFile)) {
                throw new IOException("无法保存更新包");
            }
            deleteFile(winner == UpdateRaceState.Source.GITHUB
                ? session.giteePart : session.githubPart);
            readyFile = finalFile;
            readyManifest = session.manifest;
            publish("ready_to_install", sourceName(winner), session.githubBytes,
                session.giteeBytes, session.manifest.getSizeBytes());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            failSession(session, userMessage(error));
        } finally {
            synchronized (stateLock) {
                if (activeSession == session) {
                    activeSession = null;
                }
            }
        }
    }

    private void validateApk(UpdateManifest manifest, File apkFile) throws Exception {
        if (!apkFile.isFile() || apkFile.length() != manifest.getSizeBytes()) {
            throw new IOException("更新包大小不一致");
        }
        String digest = sha256(apkFile);
        if (!manifest.getSha256().equalsIgnoreCase(digest)) {
            throw new IOException("更新包 SHA-256 不匹配");
        }
        PackageManager packageManager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_META_DATA | PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES;
        PackageInfo packageInfo = packageManager.getPackageArchiveInfo(apkFile.getPath(), flags);
        if (packageInfo == null || !manifest.getPackageName().equals(packageInfo.packageName)) {
            throw new IOException("更新包名不匹配");
        }
        long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
        if (versionCode != manifest.getVersionCode()
            || !manifest.getVersionName().equals(packageInfo.versionName)) {
            throw new IOException("更新版本号不匹配");
        }
        String certificate = readCertificateDigest(packageInfo);
        if (!manifest.getSigningCertificateSha256().equals(certificate)) {
            throw new IOException("更新签名证书不匹配");
        }
    }

    private String readCertificateDigest(PackageInfo packageInfo) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signatures = packageInfo.signingInfo == null
                ? null : packageInfo.signingInfo.getApkContentsSigners();
        } else {
            signatures = packageInfo.signatures;
        }
        if (signatures == null || signatures.length != 1) {
            throw new IOException("更新签名证书数量无效");
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(
            new java.io.ByteArrayInputStream(signatures[0].toByteArray()));
        return bytesToHex(MessageDigest.getInstance("SHA-256")
            .digest(certificate.getEncoded()));
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return bytesToHex(digest.digest());
    }

    private HttpURLConnection openConnection(String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/octet-stream");
        connection.setRequestProperty("User-Agent", "JQViewer-Update");
        return connection;
    }

    private byte[] readLimited(InputStream source, int maxBytes) throws IOException {
        try (InputStream input = source) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IOException("更新元数据过大");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void updateBytes(UpdateSession session, UpdateRaceState.Source source, long bytes) {
        if (source == UpdateRaceState.Source.GITHUB) {
            session.githubBytes = bytes;
        } else {
            session.giteeBytes = bytes;
        }
        publish("racing", session.raceState.getWinner() == null ? "racing"
                : sourceName(session.raceState.getWinner()), session.githubBytes,
            session.giteeBytes, session.manifest.getSizeBytes());
    }

    private void cancelOtherSource(UpdateSession session, UpdateRaceState.Source winner) {
        closeConnection(session, winner == UpdateRaceState.Source.GITHUB
            ? UpdateRaceState.Source.GITEE : UpdateRaceState.Source.GITHUB);
    }

    private void setConnection(UpdateSession session, UpdateRaceState.Source source,
                               HttpURLConnection connection) {
        if (source == UpdateRaceState.Source.GITHUB) {
            session.githubConnection = connection;
        } else {
            session.giteeConnection = connection;
        }
    }

    private void clearConnection(UpdateSession session, UpdateRaceState.Source source,
                                 HttpURLConnection connection) {
        if (source == UpdateRaceState.Source.GITHUB) {
            if (session.githubConnection == connection) session.githubConnection = null;
        } else if (session.giteeConnection == connection) {
            session.giteeConnection = null;
        }
    }

    private void closeConnection(UpdateSession session, UpdateRaceState.Source source) {
        HttpURLConnection connection = source == UpdateRaceState.Source.GITHUB
            ? session.githubConnection : session.giteeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    private void closeConnections(UpdateSession session) {
        closeConnection(session, UpdateRaceState.Source.GITHUB);
        closeConnection(session, UpdateRaceState.Source.GITEE);
    }

    private void failSession(UpdateSession session, String message) {
        File staleReadyFile = readyFile;
        readyFile = null;
        readyManifest = null;
        installPermissionPending = false;
        deleteFile(staleReadyFile);
        publish("failed", "", session.githubBytes, session.giteeBytes,
            session.manifest.getSizeBytes(), message);
        cleanupSession(session, false);
    }

    private void cleanupSession(UpdateSession session) {
        cleanupSession(session, true);
    }

    private void cleanupSession(UpdateSession session, boolean stopNotification) {
        deleteFile(session.githubPart);
        deleteFile(session.giteePart);
        if (stopNotification) {
            UpdateForegroundService.stop(context, revision.incrementAndGet());
        }
        synchronized (stateLock) {
            if (activeSession == session) {
                activeSession = null;
            }
        }
    }

    private void publish(String phase, String source, long githubBytes, long giteeBytes,
                         long totalBytes) {
        publish(phase, source, githubBytes, giteeBytes, totalBytes, "");
    }

    private void publish(String phase, String source, long githubBytes, long giteeBytes,
                         long totalBytes, String error) {
        Snapshot snapshot = new Snapshot(revision.incrementAndGet(), phase, source,
            githubBytes, giteeBytes, totalBytes, error);
        latestSnapshot = snapshot;
        UpdateForegroundService.Snapshot notification = new UpdateForegroundService.Snapshot(
            snapshot.revision, phase, source, githubBytes, giteeBytes, totalBytes,
            error);
        boolean terminalFailure = "failed".equals(phase) && activeSession != null;
        if (terminalFailure || (!"failed".equals(phase) && !"cancelled".equals(phase)
            && !"up_to_date".equals(phase) && !"update_available".equals(phase))) {
            UpdateForegroundService.update(context, notification);
        }
        Consumer<Snapshot> sink = progressSink;
        if (sink != null) {
            try {
                sink.accept(snapshot);
            } catch (RuntimeException sinkError) {
                Log.w(TAG, "发布更新进度失败", sinkError);
            }
        }
    }

    private String userMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "更新失败" : message;
    }

    private String sourceName(UpdateRaceState.Source source) {
        return source == UpdateRaceState.Source.GITHUB ? "GitHub" : "Gitee";
    }

    private void deleteFiles(File directory) {
        if (!directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && !file.delete()) {
                Log.w(TAG, "更新临时文件删除失败: " + file.getName());
            }
        }
    }

    private void deleteFile(File file) {
        if (file != null && file.isFile() && !file.delete()) {
            Log.w(TAG, "更新临时文件删除失败: " + file.getName());
        }
    }

    private static ThreadPoolExecutor createExecutor(int size, String threadNamePrefix) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                return new Thread(runnable, threadNamePrefix + sequence.incrementAndGet());
            }
        };
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(size * 4);
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS, queue, factory,
            new ThreadPoolExecutor.AbortPolicy());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02X", value));
        }
        return result.toString();
    }

    private static final class UpdateSession {
        private final UpdateManifest manifest;
        private final UpdateRaceState raceState = new UpdateRaceState();
        private final CountDownLatch finishedLatch = new CountDownLatch(2);
        private final File githubPart;
        private final File giteePart;
        private volatile HttpURLConnection githubConnection;
        private volatile HttpURLConnection giteeConnection;
        private volatile long githubBytes;
        private volatile long giteeBytes;
        private volatile boolean cancelled;

        private UpdateSession(UpdateManifest manifest, File updateDirectory, int sessionId) {
            this.manifest = manifest;
            this.githubPart = new File(updateDirectory, "github-" + sessionId + ".part");
            this.giteePart = new File(updateDirectory, "gitee-" + sessionId + ".part");
        }
    }

    /**
     * Bridge 更新进度快照。
     */
    public static final class Snapshot {
        public final int revision;
        public final String phase;
        public final String source;
        public final long githubBytes;
        public final long giteeBytes;
        public final long totalBytes;
        public final String error;

        private Snapshot(int revision, String phase, String source, long githubBytes,
                         long giteeBytes, long totalBytes, String error) {
            this.revision = revision;
            this.phase = phase;
            this.source = source;
            this.githubBytes = githubBytes;
            this.giteeBytes = giteeBytes;
            this.totalBytes = totalBytes;
            this.error = error == null ? "" : error;
        }

        private static Snapshot idle() {
            return new Snapshot(0, "idle", "", 0L, 0L, 0L, "");
        }

        public JSObject toJson() {
            JSObject result = new JSObject();
            result.put("revision", revision);
            result.put("phase", phase);
            result.put("source", source);
            result.put("githubBytes", githubBytes);
            result.put("giteeBytes", giteeBytes);
            result.put("totalBytes", totalBytes);
            result.put("error", error);
            return result;
        }
    }

    /**
     * 检查结果。
     */
    public static final class CheckResult {
        public final boolean success;
        public final boolean updateAvailable;
        public final UpdateManifest manifest;
        public final String error;

        private CheckResult(boolean success, boolean updateAvailable,
                            UpdateManifest manifest, String error) {
            this.success = success;
            this.updateAvailable = updateAvailable;
            this.manifest = manifest;
            this.error = error;
        }

        static CheckResult available(UpdateManifest manifest) {
            return new CheckResult(true, true, manifest, null);
        }

        static CheckResult upToDate(UpdateManifest manifest) {
            return new CheckResult(true, false, manifest, null);
        }

        static CheckResult failure(String message) {
            return new CheckResult(false, false, null, message);
        }
    }

    static final class ManifestAttempt {
        final UpdateManifest manifest;
        final String error;

        private ManifestAttempt(UpdateManifest manifest, String error) {
            this.manifest = manifest;
            this.error = error;
        }

        static ManifestAttempt success(UpdateManifest manifest) {
            return new ManifestAttempt(manifest, null);
        }

        static ManifestAttempt failure(String error) {
            return new ManifestAttempt(null, error);
        }
    }

    static final class ManifestResolution {
        final boolean success;
        final UpdateManifest manifest;
        final String error;

        private ManifestResolution(boolean success, UpdateManifest manifest, String error) {
            this.success = success;
            this.manifest = manifest;
            this.error = error;
        }

        static ManifestResolution success(UpdateManifest manifest) {
            return new ManifestResolution(true, manifest, null);
        }

        static ManifestResolution failure(String error) {
            return new ManifestResolution(false, null, error);
        }
    }

    /**
     * 原生安装入口结果，供 Bridge 转换为前端可识别的状态。
     */
    public static final class InstallResult {
        public final boolean started;
        public final boolean permissionRequired;
        public final String error;

        private InstallResult(boolean started, boolean permissionRequired, String error) {
            this.started = started;
            this.permissionRequired = permissionRequired;
            this.error = error;
        }

        static InstallResult started() {
            return new InstallResult(true, false, null);
        }

        static InstallResult permissionRequired() {
            return new InstallResult(false, true, null);
        }

        static InstallResult failure(String message) {
            return new InstallResult(false, false, message);
        }
    }
}
