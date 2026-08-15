package io.github.jukomu.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import io.github.jukomu.bridge.handler.SystemPluginHandler;
import io.github.jukomu.platform.permission.PermissionService;
import io.github.jukomu.platform.permission.PermissionState;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static org.junit.Assert.*;

public class SystemPluginContractInstrumentedTest {

    private Context context;
    private JmcomicPlugin plugin;
    private RecordingActivity activity;
    private FakePermissionService permissionService;
    private SystemPluginHandler systemHandler;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        plugin = new JmcomicPlugin();
        RecordingActivity[] activityHolder = new RecordingActivity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
            () -> activityHolder[0] = new RecordingActivity());
        activity = activityHolder[0];
        permissionService = new FakePermissionService();
        systemHandler = new SystemPluginHandler(
            context,
            () -> activity,
            permissionService,
            () -> null,
            event -> {
            },
            (permission, requestCode) -> {
                activity.requestedPermissions = new String[]{permission};
                activity.permissionRequestCode = requestCode;
            });
        injectSystemHandler(plugin, systemHandler);
    }

    @After
    public void tearDown() {
        if (systemHandler != null) {
            systemHandler.destroy();
        }
    }

    @Test
    public void unavailableClientUsesCurrentResultsAndMessages() {
        RecordingPluginCall domainStates = call("getDomainStates");
        RecordingPluginCall latency = call("measureLatency");
        RecordingPluginCall initStatus = call("getInitStatus");
        RecordingPluginCall reprobe = call("reprobeDomains");

        plugin.getDomainStates(domainStates);
        plugin.measureLatency(latency);
        plugin.getInitStatus(initStatus);
        plugin.reprobeDomains(reprobe);

        assertRejected(domainStates, "client 尚未初始化");
        assertRejected(latency, "client 尚未初始化");
        assertFalse(initStatus.resolvedData.getBool("complete"));
        assertTrue(reprobe.resolvedWithoutData);
        assertSynchronous(initStatus, reprobe);
    }

    @Test
    public void manageStorageOpensSettingsAndReturnsPermissionState() {
        permissionService.state = new PermissionState(false, "MANAGE_EXTERNAL_STORAGE", 31);
        RecordingPluginCall call = call("requestManageStorage");

        plugin.requestManageStorage(call);

        assertTrue(permissionService.openedSettings);
        assertFalse(call.resolvedData.getBool("granted"));
        assertEquals("MANAGE_EXTERNAL_STORAGE", call.resolvedData.getString("permissionType"));
        assertEquals(31, call.resolvedData.getInteger("apiLevel").intValue());
        assertSynchronous(call);
    }

    @Test
    public void manageStorageSettingsFailureUsesCurrentMessage() {
        permissionService.state = new PermissionState(false, "MANAGE_EXTERNAL_STORAGE", 31);
        permissionService.openFailure = new IllegalStateException("settings unavailable");
        RecordingPluginCall call = call("requestManageStorage");

        plugin.requestManageStorage(call);

        assertEquals("无法打开存储权限设置页: settings unavailable", call.rejectionMessage);
        assertNull(call.rejectionException);
        assertSynchronous(call);
    }

    @Test
    public void legacyStorageRequestKeepsOnlyOnePendingCall() {
        permissionService.state = new PermissionState(false, "WRITE_EXTERNAL_STORAGE", 23);
        permissionService.interpretedState = new PermissionState(
            true, "WRITE_EXTERNAL_STORAGE", 31);
        RecordingPluginCall first = call("requestManageStorage");
        RecordingPluginCall second = call("requestManageStorage");

        plugin.requestManageStorage(first);
        plugin.requestManageStorage(second);

        assertTrue(first.isKeptAlive());
        assertEquals(0, first.completionCount);
        assertEquals(PermissionService.REQUEST_WRITE_STORAGE, activity.permissionRequestCode);
        assertEquals(1, activity.requestedPermissions.length);
        assertEquals("android.permission.WRITE_EXTERNAL_STORAGE",
            activity.requestedPermissions[0]);
        assertRejected(second, "权限请求正在进行中，请先完成上一个请求。");

        plugin.handlePermissionResult(
            PermissionService.REQUEST_WRITE_STORAGE,
            activity.requestedPermissions,
            new int[]{PackageManager.PERMISSION_GRANTED});

        assertEquals(1, first.completionCount);
        assertTrue(first.resolvedData.getBool("granted"));
        assertEquals("WRITE_EXTERNAL_STORAGE",
            first.resolvedData.getString("permissionType"));
    }

    @Test
    public void notificationPermissionUsesPlatformBranch() {
        permissionService.notificationGranted = false;
        RecordingPluginCall checkDenied = call("checkNotificationPermission");
        RecordingPluginCall requestDenied = call("requestNotificationPermission");

        plugin.checkNotificationPermission(checkDenied);
        plugin.requestNotificationPermission(requestDenied);

        assertFalse(checkDenied.resolvedData.getBool("granted"));
        assertSynchronous(checkDenied);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertNull(requestDenied.resolvedData);
            assertTrue(requestDenied.isKeptAlive());
            assertArrayEquals(
                new String[]{"android.permission.POST_NOTIFICATIONS"},
                activity.requestedPermissions);
            assertEquals(
                PermissionService.REQUEST_POST_NOTIFICATIONS,
                activity.permissionRequestCode);
            plugin.handlePermissionResult(
                PermissionService.REQUEST_POST_NOTIFICATIONS,
                activity.requestedPermissions,
                new int[]{PackageManager.PERMISSION_DENIED});
            assertEquals(1, requestDenied.completionCount);
        } else {
            assertSynchronous(requestDenied);
        }
        assertFalse(requestDenied.resolvedData.getBool("granted"));

        permissionService.notificationGranted = true;
        RecordingPluginCall requestGranted = call("requestNotificationPermission");
        plugin.requestNotificationPermission(requestGranted);

        assertTrue(requestGranted.resolvedData.getBool("granted"));
        assertSynchronous(requestGranted);
    }

    @Test
    public void notificationPermissionResultCompletesPendingCallOnce() throws Exception {
        RecordingPluginCall pending = call("requestNotificationPermission");
        Field field = SystemPluginHandler.class.getDeclaredField(
            "pendingNotificationPermissionCall");
        field.setAccessible(true);
        field.set(systemHandler, pending);
        permissionService.notificationResult = true;

        plugin.handlePermissionResult(
            PermissionService.REQUEST_POST_NOTIFICATIONS,
            new String[]{"android.permission.POST_NOTIFICATIONS"},
            new int[]{PackageManager.PERMISSION_GRANTED});
        plugin.handlePermissionResult(
            PermissionService.REQUEST_POST_NOTIFICATIONS,
            new String[]{"android.permission.POST_NOTIFICATIONS"},
            new int[]{PackageManager.PERMISSION_GRANTED});

        assertEquals(1, pending.completionCount);
        assertTrue(pending.resolvedData.getBool("granted"));
    }

    @Test
    public void destroyCompletesPendingCallsAndRejectsNewSystemTasks() throws Exception {
        permissionService.state = new PermissionState(false, "WRITE_EXTERNAL_STORAGE", 23);
        RecordingPluginCall storagePermission = call("requestManageStorage");
        RecordingPluginCall notificationPermission = call(
            "requestNotificationPermission");
        RecordingPluginCall image = call("pickImageAndOcr");
        RecordingPluginCall folder = call("pickFolder");

        plugin.requestManageStorage(storagePermission);
        notificationPermission.setKeepAlive(true);
        Field notificationField = SystemPluginHandler.class.getDeclaredField(
            "pendingNotificationPermissionCall");
        notificationField.setAccessible(true);
        notificationField.set(systemHandler, notificationPermission);
        plugin.pickImageAndOcr(image);
        int imageRequestCode = activity.activityRequestCode;
        plugin.pickFolder(folder);
        int folderRequestCode = activity.activityRequestCode;

        systemHandler.destroy();

        assertRejected(storagePermission, "插件会话已结束");
        assertRejected(notificationPermission, "插件会话已结束");
        assertRejected(image, "插件会话已结束");
        assertRejected(folder, "插件会话已结束");

        plugin.handlePermissionResult(
            PermissionService.REQUEST_WRITE_STORAGE,
            new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"},
            new int[]{PackageManager.PERMISSION_GRANTED});
        plugin.handlePermissionResult(
            PermissionService.REQUEST_POST_NOTIFICATIONS,
            new String[]{"android.permission.POST_NOTIFICATIONS"},
            new int[]{PackageManager.PERMISSION_GRANTED});
        plugin.handleActivityResult(imageRequestCode, RESULT_CANCELED, null);
        plugin.handleActivityResult(folderRequestCode, RESULT_CANCELED, null);

        assertEquals(1, storagePermission.completionCount);
        assertEquals(1, notificationPermission.completionCount);
        assertEquals(1, image.completionCount);
        assertEquals(1, folder.completionCount);

        RecordingPluginCall nextStoragePermission = call("requestManageStorage");
        RecordingPluginCall nextNotificationPermission = call(
            "requestNotificationPermission");
        RecordingPluginCall nextImage = call("pickImageAndOcr");
        RecordingPluginCall nextFolder = call("pickFolder");
        plugin.requestManageStorage(nextStoragePermission);
        plugin.requestNotificationPermission(nextNotificationPermission);
        plugin.pickImageAndOcr(nextImage);
        plugin.pickFolder(nextFolder);

        assertRejected(nextStoragePermission, "插件会话已结束");
        assertRejected(nextNotificationPermission, "插件会话已结束");
        assertRejected(nextImage, "插件会话已结束");
        assertRejected(nextFolder, "插件会话已结束");
    }

    @Test
    public void imagePickerRejectsConcurrentCallAndResolvesCancellation() {
        RecordingPluginCall first = call("pickImageAndOcr");
        RecordingPluginCall second = call("pickImageAndOcr");

        plugin.pickImageAndOcr(first);
        int requestCode = activity.activityRequestCode;
        plugin.pickImageAndOcr(second);

        assertEquals(Intent.ACTION_PICK, activity.startedIntent.getAction());
        assertFalse(first.isKeptAlive());
        assertEquals(0, first.completionCount);
        assertRejected(second, "另一个 OCR 请求正在进行中");

        plugin.handleActivityResult(requestCode, RESULT_CANCELED, null);

        assertEquals("", first.resolvedData.getString("text"));
        assertEquals("", first.resolvedData.getString("error"));
        assertEquals(1, first.completionCount);
    }

    @Test
    public void folderPickerRejectsConcurrentCallAndResolvesCancellation() {
        RecordingPluginCall first = call("pickFolder");
        RecordingPluginCall second = call("pickFolder");

        plugin.pickFolder(first);
        int requestCode = activity.activityRequestCode;
        plugin.pickFolder(second);

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, activity.startedIntent.getAction());
        assertEquals(0, first.completionCount);
        assertRejected(second, "另一个文件夹选择请求正在进行中");

        plugin.handleActivityResult(requestCode, RESULT_CANCELED, null);

        assertTrue(first.resolvedData.getBool("cancelled"));
        assertEquals("", first.resolvedData.getString("path"));
        assertEquals(1, first.completionCount);
        assertFalse(first.isKeptAlive());
    }

    @Test
    public void folderPickerReturnsTreeUriAndPrimaryPath() {
        RecordingPluginCall call = call("pickFolder");
        plugin.pickFolder(call);
        int requestCode = activity.activityRequestCode;
        Uri treeUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FJQ-Viewer");
        Intent result = new Intent().setData(treeUri);

        plugin.handleActivityResult(requestCode, RESULT_OK, result);

        assertFalse(call.resolvedData.getBool("cancelled"));
        assertEquals(treeUri.toString(), call.resolvedData.getString("treeUri"));
        assertEquals("/storage/emulated/0/Download/JQ-Viewer",
            call.resolvedData.getString("path"));
        assertSynchronous(call);
    }

    @Test
    public void pickerLaunchFailureRejectsAndClearsPendingCall() {
        IllegalStateException failure = new IllegalStateException("picker unavailable");
        activity.startFailure = failure;
        RecordingPluginCall image = call("pickImageAndOcr");
        RecordingPluginCall folder = call("pickFolder");

        plugin.pickImageAndOcr(image);
        plugin.pickFolder(folder);

        assertEquals("picker unavailable", image.rejectionMessage);
        assertEquals(failure, image.rejectionException);
        assertEquals("picker unavailable", folder.rejectionMessage);
        assertEquals(failure, folder.rejectionException);

        activity.startFailure = null;
        RecordingPluginCall nextImage = call("pickImageAndOcr");
        RecordingPluginCall nextFolder = call("pickFolder");
        plugin.pickImageAndOcr(nextImage);
        plugin.handleActivityResult(activity.activityRequestCode, RESULT_CANCELED, null);
        plugin.pickFolder(nextFolder);
        plugin.handleActivityResult(activity.activityRequestCode, RESULT_CANCELED, null);

        assertEquals(1, nextImage.completionCount);
        assertEquals(1, nextFolder.completionCount);
    }

    @Test
    public void fileQueriesReturnOnlyExistingPaths() throws Exception {
        RecordingPluginCall missingInput = call("checkFilesExist");
        plugin.checkFilesExist(missingInput);
        assertRejected(missingInput, "paths is required");

        File existingFile = new File(context.getCacheDir(), "system-handler-contract.txt");
        assertTrue(existingFile.createNewFile() || existingFile.exists());
        try {
            JSArray paths = new JSArray();
            paths.put(existingFile.getAbsolutePath());
            paths.put(existingFile.getAbsolutePath() + ".missing");
            paths.put("content://io.github.jukomu.missing/item");
            RecordingPluginCall files = call("checkFilesExist", "paths", paths);
            RecordingPluginCall externalPath = call("getExternalStoragePath");

            plugin.checkFilesExist(files);
            plugin.getExternalStoragePath(externalPath);

            JSONArray existing = files.resolvedData.getJSONArray("existing");
            assertNotNull(existing);
            assertEquals(1, existing.length());
            assertEquals(existingFile.getAbsolutePath(), existing.getString(0));
            assertFalse(externalPath.resolvedData.getString("path").isEmpty());
            assertSynchronous(files, externalPath);
        } finally {
            assertTrue(existingFile.delete() || !existingFile.exists());
        }
    }

    private static RecordingPluginCall call(String methodName, Object... entries) {
        JSObject data = new JSObject();
        for (int index = 0; index < entries.length; index += 2) {
            data.put((String) entries[index], entries[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static void assertRejected(RecordingPluginCall call, String message) {
        assertEquals(message, call.rejectionMessage);
        assertSynchronous(call);
    }

    private static void assertSynchronous(RecordingPluginCall... calls) {
        for (RecordingPluginCall call : calls) {
            assertFalse(call.getMethodName(), call.isKeptAlive());
            assertEquals(call.getMethodName(), 1, call.completionCount);
        }
    }

    private static void injectSystemHandler(JmcomicPlugin plugin,
                                            SystemPluginHandler handler) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("systemHandler");
        field.setAccessible(true);
        field.set(plugin, handler);
    }

    private static final class RecordingActivity extends Activity {

        private Intent startedIntent;
        private int activityRequestCode;
        private String[] requestedPermissions;
        private int permissionRequestCode;
        private RuntimeException startFailure;

        @Override
        public void startActivityForResult(Intent intent, int requestCode) {
            if (startFailure != null) {
                throw startFailure;
            }
            startedIntent = intent;
            activityRequestCode = requestCode;
        }

    }

    private static final class FakePermissionService extends PermissionService {

        private PermissionState state = new PermissionState(true, "install_time", 22);
        private PermissionState interpretedState = state;
        private boolean notificationGranted = true;
        private boolean notificationResult;
        private boolean openedSettings;
        private Exception openFailure;

        @Override
        public PermissionState checkState(Context context) {
            return state;
        }

        @Override
        public void openSystemSettings(Context context) throws Exception {
            openedSettings = true;
            if (openFailure != null) {
                throw openFailure;
            }
        }

        @Override
        public PermissionState interpretResult(int[] grantResults) {
            return interpretedState;
        }

        @Override
        public boolean checkNotificationPermission(Context context) {
            return notificationGranted;
        }

        @Override
        public boolean interpretNotificationResult(int[] grantResults) {
            return notificationResult;
        }
    }
}
