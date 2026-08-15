package io.github.jukomu.bridge.handler;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.NonNull;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;
import io.github.jukomu.platform.permission.PermissionService;
import io.github.jukomu.platform.permission.PermissionState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static android.app.Activity.RESULT_OK;

/**
 * 负责网络探活、系统权限、文件选择、OCR 和文件状态查询的 Bridge 调用。
 *
 * <p>网络监听和 OCR 线程属于当前插件会话，调用 {@link #destroy()} 后停止接收新任务。
 */
public final class SystemPluginHandler {

    private static final String TAG = "SystemPluginHandler";
    private static final long PROBE_DEBOUNCE_MS = 2000;
    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_PICK_FOLDER = 1002;
    private static final String SESSION_ENDED_MESSAGE = "插件会话已结束";

    private final Context context;
    private final Supplier<Activity> activitySupplier;
    private final PermissionService permissionService;
    private final Supplier<JmApiClient> clientSupplier;
    private final Consumer<JSObject> networkProbeConsumer;
    private final BiConsumer<String, Integer> permissionRequester;
    private final ExecutorService ocrExecutor;
    private final Object probeLock = new Object();
    private final Object permissionLock = new Object();
    private final Object ocrLock = new Object();
    private final Object folderLock = new Object();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ScheduledExecutorService domainProbeExecutor;
    private ScheduledFuture<?> pendingProbe;
    private PluginCall pendingPermissionCall;
    private PluginCall pendingNotificationPermissionCall;
    private volatile PluginCall pendingOcrCall;
    private volatile PluginCall pendingFolderCall;
    private volatile boolean destroyed;

    public SystemPluginHandler(Context context, Supplier<Activity> activitySupplier,
                               PermissionService permissionService,
                               Supplier<JmApiClient> clientSupplier,
                               Consumer<JSObject> networkProbeConsumer,
                               BiConsumer<String, Integer> permissionRequester) {
        this.context = context;
        this.activitySupplier = activitySupplier;
        this.permissionService = permissionService;
        this.clientSupplier = clientSupplier;
        this.networkProbeConsumer = networkProbeConsumer;
        this.permissionRequester = permissionRequester;
        this.ocrExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * 注册默认网络变化监听，并创建域名探活调度器。
     */
    public void registerNetworkCallback() {
        synchronized (probeLock) {
            if (destroyed) {
                return;
            }
        }
        connectivityManager = (ConnectivityManager) context.getSystemService(
            Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            Log.w(TAG, "ConnectivityManager 不可用，跳过网络监听");
            return;
        }

        domainProbeExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "domain-probe-debounce");
            thread.setDaemon(true);
            return thread;
        });

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                JSObject event = new JSObject();
                event.put("phase", "network_changed");
                event.put("message", "网络已切换，即将重新探活");
                event.put("timestamp", System.currentTimeMillis());
                publishNetworkEvent(event);
                scheduleDomainProbe();
            }

            @Override
            public void onLost(@NonNull Network network) {
                JSObject event = new JSObject();
                event.put("phase", "network_lost");
                event.put("message", "网络已断开");
                event.put("timestamp", System.currentTimeMillis());
                publishNetworkEvent(event);
            }
        };

        synchronized (probeLock) {
            if (destroyed) {
                shutdownGracefully(domainProbeExecutor);
                return;
            }
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            Log.i(TAG, "网络变化监听已注册");
        }
    }

    /**
     * 取消网络监听和待执行探活任务，并关闭会话线程。
     */
    public void destroy() {
        synchronized (probeLock) {
            if (destroyed) {
                return;
            }
            destroyed = true;
            if (pendingProbe != null) {
                pendingProbe.cancel(false);
                pendingProbe = null;
            }
        }

        PluginCall permissionCall;
        PluginCall notificationPermissionCall;
        synchronized (permissionLock) {
            permissionCall = pendingPermissionCall;
            pendingPermissionCall = null;
            notificationPermissionCall = pendingNotificationPermissionCall;
            pendingNotificationPermissionCall = null;
        }
        PluginCall ocrCall;
        synchronized (ocrLock) {
            ocrCall = pendingOcrCall;
            pendingOcrCall = null;
        }
        PluginCall folderCall;
        synchronized (folderLock) {
            folderCall = pendingFolderCall;
            pendingFolderCall = null;
        }
        rejectPendingCall(permissionCall);
        rejectPendingCall(notificationPermissionCall);
        rejectPendingCall(ocrCall);
        rejectPendingCall(folderCall);

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException error) {
                Log.d(TAG, "取消注册网络回调失败", error);
            }
        }
        shutdownGracefully(domainProbeExecutor);
        shutdownGracefully(ocrExecutor);
    }

    /**
     * 返回当前客户端记录的域名可达状态。
     */
    public void getDomainStates(PluginCall call) {
        try {
            JmApiClient client = clientSupplier.get();
            if (client == null) {
                call.reject("client 尚未初始化");
                return;
            }
            Map<String, Integer> states = client.getDomainStates();

            JSObject result = new JSObject();
            int alive = 0;
            JSONArray domains = new JSONArray();
            for (Map.Entry<String, Integer> entry : states.entrySet()) {
                JSONObject domain = new JSONObject();
                domain.put("domain", entry.getKey());
                boolean reachable = entry.getValue() < (Integer.MAX_VALUE / 2);
                domain.put("reachable", reachable);
                if (reachable) {
                    alive++;
                }
                domains.put(domain);
            }
            boolean allDeadFallback = !states.isEmpty()
                && states.values().stream().allMatch(value -> value == -1);

            result.put("domains", domains);
            result.put("alive", alive);
            result.put("total", states.size());
            result.put("allDeadFallback", allDeadFallback);
            call.resolve(result);
        } catch (Exception error) {
            Log.e(TAG, "获取域名状态失败", error);
            call.reject("获取域名状态失败，请稍后重试");
        }
    }

    /**
     * 将一次域名重新探活任务加入去抖调度队列。
     */
    public void reprobeDomains(PluginCall call) {
        scheduleDomainProbe();
        call.resolve();
    }

    /**
     * 返回当前客户端记录的域名延迟结果。
     */
    public void measureLatency(PluginCall call) {
        try {
            JmApiClient client = clientSupplier.get();
            if (client == null) {
                call.reject("client 尚未初始化");
                return;
            }
            Map<String, Integer> latency = client.getDomainLatency();

            JSObject result = new JSObject();
            JSONArray items = new JSONArray();
            for (Map.Entry<String, Integer> entry : latency.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("domain", entry.getKey());
                int latencyMs = entry.getValue();
                item.put("latencyMs", latencyMs == -1 ? 0 : latencyMs);
                item.put("timedOut", latencyMs == -1);
                items.put(item);
            }
            result.put("results", items);
            call.resolve(result);
        } catch (Exception error) {
            Log.e(TAG, "测速失败", error);
            call.reject("测速失败，请稍后重试");
        }
    }

    /**
     * 返回网络客户端是否已经初始化。
     */
    public void getInitStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("complete", clientSupplier.get() != null);
        call.resolve(result);
    }

    /**
     * 根据 Android API 版本检查或请求公开存储所需权限。
     */
    public void requestManageStorage(PluginCall call) {
        if (destroyed) {
            call.reject(SESSION_ENDED_MESSAGE);
            return;
        }
        PermissionState state = permissionService.checkState(context);
        JSObject result = permissionResult(state);

        if (state.apiLevel >= Build.VERSION_CODES.R) {
            if (!state.granted) {
                try {
                    permissionService.openSystemSettings(context);
                } catch (Exception error) {
                    call.reject("无法打开存储权限设置页: " + error.getMessage());
                    return;
                }
            }
            call.resolve(result);
        } else if (state.apiLevel >= Build.VERSION_CODES.Q) {
            call.resolve(result);
        } else if (state.apiLevel >= Build.VERSION_CODES.M) {
            if (!state.granted) {
                synchronized (permissionLock) {
                    if (destroyed) {
                        call.reject(SESSION_ENDED_MESSAGE);
                        return;
                    }
                    if (pendingPermissionCall != null) {
                        call.reject("权限请求正在进行中，请先完成上一个请求。");
                        return;
                    }
                    pendingPermissionCall = call;
                    call.setKeepAlive(true);
                    permissionRequester.accept(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        PermissionService.REQUEST_WRITE_STORAGE);
                }
                return;
            }
            call.resolve(result);
        } else {
            call.resolve(result);
        }
    }

    /**
     * 处理存储权限和通知权限请求结果。
     */
    public void handlePermissionResult(int requestCode, String[] permissions,
                                       int[] grantResults) {
        if (destroyed) {
            return;
        }
        if (requestCode == PermissionService.REQUEST_WRITE_STORAGE) {
            PluginCall call;
            synchronized (permissionLock) {
                call = pendingPermissionCall;
                pendingPermissionCall = null;
            }
            if (call != null) {
                PermissionState state = permissionService.interpretResult(grantResults);
                call.resolve(permissionResult(state));
            } else {
                boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                Log.i(TAG, "启动时存储权限请求结果: granted=" + granted);
            }
        } else if (requestCode == PermissionService.REQUEST_POST_NOTIFICATIONS) {
            boolean granted = permissionService.interpretNotificationResult(grantResults);
            PluginCall call;
            synchronized (permissionLock) {
                call = pendingNotificationPermissionCall;
                pendingNotificationPermissionCall = null;
            }
            if (call != null) {
                JSObject result = new JSObject();
                result.put("granted", granted);
                call.resolve(result);
            } else {
                Log.i(TAG, "通知权限请求结果: granted=" + granted);
            }
        }
    }

    /**
     * 启动系统图片选择器，并在 Activity Result 到达后执行 OCR。
     */
    public void pickImageAndOcr(PluginCall call) {
        synchronized (ocrLock) {
            if (destroyed) {
                call.reject(SESSION_ENDED_MESSAGE);
                return;
            }
            if (pendingOcrCall != null) {
                call.reject("另一个 OCR 请求正在进行中");
                return;
            }
            pendingOcrCall = call;
            Intent intent = new Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            try {
                activitySupplier.get().startActivityForResult(intent, REQUEST_PICK_IMAGE);
            } catch (Exception error) {
                pendingOcrCall = null;
                call.reject(error.getMessage(), error);
            }
        }
    }

    /**
     * 启动系统目录选择器，并请求持久化读写 URI 权限。
     */
    public void pickFolder(PluginCall call) {
        synchronized (folderLock) {
            if (destroyed) {
                call.reject(SESSION_ENDED_MESSAGE);
                return;
            }
            if (pendingFolderCall != null) {
                call.reject("另一个文件夹选择请求正在进行中");
                return;
            }
            pendingFolderCall = call;
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                activitySupplier.get().startActivityForResult(intent, REQUEST_PICK_FOLDER);
            } catch (Exception error) {
                pendingFolderCall = null;
                call.reject(error.getMessage(), error);
            }
        }
    }

    /**
     * 处理图片选择和目录选择的 Activity Result。
     */
    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_FOLDER) {
            handleFolderResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_PICK_IMAGE) {
            return;
        }

        PluginCall call;
        synchronized (ocrLock) {
            if (pendingOcrCall == null) {
                return;
            }
            call = pendingOcrCall;
            pendingOcrCall = null;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            JSObject result = new JSObject();
            result.put("text", "");
            result.put("error", "");
            call.resolve(result);
            return;
        }

        try {
            ocrExecutor.execute(() -> recognizeText(call, data));
        } catch (RejectedExecutionException error) {
            call.reject(SESSION_ENDED_MESSAGE);
        }
    }

    /**
     * 返回通知权限当前是否可用。
     */
    public void checkNotificationPermission(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", permissionService.checkNotificationPermission(context));
        call.resolve(result);
    }

    /**
     * 在 Android 13 及以上请求通知权限。
     */
    public void requestNotificationPermission(PluginCall call) {
        if (destroyed) {
            call.reject(SESSION_ENDED_MESSAGE);
            return;
        }
        if (permissionService.checkNotificationPermission(context)) {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            JSObject result = new JSObject();
            result.put("granted", false);
            call.resolve(result);
            return;
        }

        synchronized (permissionLock) {
            if (destroyed) {
                call.reject(SESSION_ENDED_MESSAGE);
                return;
            }
            if (pendingNotificationPermissionCall != null) {
                call.reject("通知权限请求正在进行中，请先完成上一个请求。");
                return;
            }

            pendingNotificationPermissionCall = call;
            call.setKeepAlive(true);
            permissionRequester.accept(
                Manifest.permission.POST_NOTIFICATIONS,
                PermissionService.REQUEST_POST_NOTIFICATIONS);
        }
    }

    /**
     * 返回给定文件路径和 content URI 中当前可访问的条目。
     */
    public void checkFilesExist(PluginCall call) {
        try {
            JSArray paths = call.getArray("paths");
            if (paths == null) {
                call.reject("paths is required");
                return;
            }
            ContentResolver resolver = context.getContentResolver();
            File externalRoot = Environment.getExternalStorageDirectory();
            JSArray existing = new JSArray();
            for (int index = 0; index < paths.length(); index++) {
                String path = paths.getString(index);
                if (path == null) {
                    continue;
                }
                boolean exists;
                if (path.startsWith("content://")) {
                    exists = checkContentUriExists(resolver, path);
                } else if (path.startsWith("/")) {
                    exists = new File(path).exists();
                } else {
                    exists = new File(externalRoot, path).exists();
                }
                if (exists) {
                    existing.put(path);
                }
            }
            JSObject result = new JSObject();
            result.put("existing", existing);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 返回外部存储根目录的绝对路径。
     */
    public void getExternalStoragePath(PluginCall call) {
        JSObject result = new JSObject();
        result.put("path", Environment.getExternalStorageDirectory().getAbsolutePath());
        call.resolve(result);
    }

    private void scheduleDomainProbe() {
        synchronized (probeLock) {
            if (destroyed || domainProbeExecutor == null) {
                return;
            }
            JmApiClient client = clientSupplier.get();
            if (client == null) {
                return;
            }
            if (pendingProbe != null) {
                pendingProbe.cancel(false);
            }
            pendingProbe = domainProbeExecutor.schedule(
                () -> probeDomains(client),
                PROBE_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS);
        }
    }

    private void probeDomains(JmApiClient client) {
        try {
            JSObject startEvent = new JSObject();
            startEvent.put("phase", "probing");
            startEvent.put("message", "正在探测域名连通性...");
            startEvent.put("timestamp", System.currentTimeMillis());
            publishNetworkEvent(startEvent);

            Log.i(TAG, "重新探活域名...");
            client.reprobeDomains();
            Log.i(TAG, "域名重新探活完成");

            publishDomainProbeResult(client);
        } catch (Exception error) {
            Log.w(TAG, "域名重新探活失败", error);
            JSObject errorEvent = new JSObject();
            errorEvent.put("phase", "error");
            String message = error.getMessage();
            errorEvent.put("message", "探活异常" + (message != null ? " · " + message : ""));
            errorEvent.put("timestamp", System.currentTimeMillis());
            publishNetworkEvent(errorEvent);
        }
    }

    private void publishDomainProbeResult(JmApiClient client) {
        try {
            Map<String, Integer> states = client.getDomainStates();
            JSONObject result = new JSONObject();
            result.put("phase", "result");
            int alive = 0;
            JSONArray domains = new JSONArray();
            for (Map.Entry<String, Integer> entry : states.entrySet()) {
                JSONObject domain = new JSONObject();
                domain.put("domain", entry.getKey());
                domain.put("reachable", entry.getValue() < (Integer.MAX_VALUE / 2));
                if (domain.getBoolean("reachable")) {
                    alive++;
                }
                domains.put(domain);
            }
            boolean allDeadFallback = !states.isEmpty()
                && states.values().stream().allMatch(value -> value == -1);
            result.put("allDeadFallback", allDeadFallback);
            result.put("domains", domains);
            result.put("alive", alive);
            result.put("total", states.size());
            result.put("timestamp", System.currentTimeMillis());
            result.put(
                "message",
                allDeadFallback
                    ? "探活完成 · 全部不可达"
                    : "探活完成 · " + alive + "/" + states.size() + " 可达");
            publishNetworkEvent(JSObject.fromJSONObject(result));
        } catch (Exception ignored) {
            JSObject result = new JSObject();
            result.put("phase", "result");
            result.put("message", "探活完成");
            result.put("timestamp", System.currentTimeMillis());
            publishNetworkEvent(result);
        }
    }

    private void publishNetworkEvent(JSObject event) {
        synchronized (probeLock) {
            if (!destroyed) {
                networkProbeConsumer.accept(event);
            }
        }
    }

    private void recognizeText(PluginCall call, Intent data) {
        try {
            InputImage inputImage = InputImage.fromFilePath(context, data.getData());
            TextRecognizer recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());

            CountDownLatch latch = new CountDownLatch(1);
            Text[] resultHolder = new Text[1];
            Exception[] errorHolder = new Exception[1];

            recognizer.process(inputImage)
                .addOnSuccessListener(text -> {
                    resultHolder[0] = text;
                    latch.countDown();
                })
                .addOnFailureListener(error -> {
                    errorHolder[0] = error;
                    latch.countDown();
                });

            boolean completed = latch.await(30, TimeUnit.SECONDS);
            recognizer.close();

            JSObject result = new JSObject();
            if (!completed) {
                result.put("text", "");
                result.put("error", "识别超时，请重试");
            } else if (errorHolder[0] != null) {
                result.put("text", "");
                Log.e(TAG, "OCR识别失败", errorHolder[0]);
                result.put("error", "识别失败，请重试");
            } else if (resultHolder[0] != null) {
                result.put("text", resultHolder[0].getText());
                result.put("error", "");
            } else {
                result.put("text", "");
                result.put("error", "未识别到文字");
            }
            call.resolve(result);
        } catch (Exception error) {
            JSObject result = new JSObject();
            result.put("text", "");
            Log.e(TAG, "OCR调用失败", error);
            result.put("error", "识别失败，请重试");
            call.resolve(result);
        }
    }

    private void handleFolderResult(int resultCode, Intent data) {
        PluginCall call;
        synchronized (folderLock) {
            if (pendingFolderCall == null) {
                return;
            }
            call = pendingFolderCall;
            pendingFolderCall = null;
        }

        JSObject result = new JSObject();
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            result.put("path", "");
            result.put("cancelled", true);
            call.resolve(result);
            return;
        }

        Uri treeUri = data.getData();
        try {
            context.getContentResolver().takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception error) {
            Log.w(TAG, "takePersistableUriPermission failed", error);
        }

        String path = treeUriToPath(treeUri);
        result.put("path", path != null ? path : "");
        result.put("treeUri", treeUri.toString());
        result.put("cancelled", false);
        call.resolve(result);
    }

    private static String treeUriToPath(Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception error) {
            String uriPath = treeUri.getPath();
            if (uriPath == null) {
                return null;
            }
            String[] parts = uriPath.split("/tree/", 2);
            if (parts.length < 2) {
                return null;
            }
            documentId = parts[1];
        }
        if (documentId == null) {
            return null;
        }

        String[] parts = documentId.split(":", 2);
        if (parts.length < 2) {
            return null;
        }
        String volume = parts[0];
        String subPath = parts[1];
        if (subPath.startsWith("/")) {
            subPath = subPath.substring(1);
        }

        if ("primary".equals(volume)) {
            return Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/" + subPath;
        }
        return "/storage/" + volume + "/" + subPath;
    }

    private static boolean checkContentUriExists(ContentResolver resolver, String uriValue) {
        Uri uri = Uri.parse(uriValue);
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            return cursor != null && cursor.getCount() > 0;
        } catch (Exception error) {
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static JSObject permissionResult(PermissionState state) {
        JSObject result = new JSObject();
        result.put("apiLevel", state.apiLevel);
        result.put("permissionType", state.permissionType);
        result.put("granted", state.granted);
        return result;
    }

    private static void rejectPendingCall(PluginCall call) {
        if (call == null) {
            return;
        }
        call.setKeepAlive(false);
        call.reject(SESSION_ENDED_MESSAGE);
    }

    private static void shutdownGracefully(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException error) {
            executor.shutdownNow();
        }
    }
}
