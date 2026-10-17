package io.github.jukomu.bridge;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginHandle;
import io.github.jukomu.MainActivity;
import io.github.jukomu.feature.export.ExportService;
import io.github.jukomu.feature.localfile.data.LegacyLocalFileDatabase;
import io.github.jukomu.feature.localfile.data.LocalFileStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class JqViewerStartupInstrumentedTest {
    private static final String DATABASE = "jq_pdf_import.db";
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resetLocalServices();
        context.deleteDatabase(DATABASE);
    }

    @After
    public void tearDown() throws Exception {
        resetLocalServices();
        context.deleteDatabase(DATABASE);
    }

    @Test
    public void freshInstallRegistersLocalPluginAndCoreMethods() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> assertLocalPluginWorks(activity, false));
        }
    }

    @Test
    public void legacyDatabasesLoadThroughRealBridge() throws Exception {
        for (int version : new int[]{8, 9}) {
            resetLocalServices();
            context.deleteDatabase(DATABASE);
            try (SQLiteDatabase legacy = LegacyLocalFileDatabase.createVersionNine(context)) {
                legacy.setVersion(version);
            }
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> assertLocalPluginWorks(activity, false));
                assertEquals(11, LocalFileStore.getInstance(context).getReadableDatabase().getVersion());
                assertTrue(LocalFileStore.getInstance(context).getManagementState()
                    .getJSONObject("databaseResetInfo").getBoolean("pending"));
            }
        }
    }

    @Test
    public void fileDatabaseFailureDoesNotDisableHistorySettingsOrReaderAfterRecreation() throws Exception {
        try (SQLiteDatabase legacy = LegacyLocalFileDatabase.createVersionNine(context)) {
            legacy.execSQL("DROP TABLE pdf_files");
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> assertLocalPluginWorks(activity, true));
            scenario.recreate();
            scenario.onActivity(activity -> assertLocalPluginWorks(activity, true));
        }
    }

    private static void assertLocalPluginWorks(MainActivity activity, boolean fileFailure) {
        PluginHandle handle = activity.getBridge().getPlugin("JqViewer");
        assertNotNull("JqViewer must be registered even if the file database fails", handle);
        assertNotNull(activity.getBridge().getPlugin("Jmcomic"));
        for (String method : new String[]{"getBrowseHistory", "getParseHistory",
            "getAllSettings", "getCacheCapacityInfo", "setReaderState"}) {
            JSObject data = new JSObject();
            if ("setReaderState".equals(method)) {
                data.put("isActive", false);
                data.put("isVertical", true);
            }
            RecordingPluginCall call = new RecordingPluginCall(method, data);
            invoke(handle, method, call);
            assertNull(method + ": " + call.rejectionMessage, call.rejectionMessage);
            assertEquals(method, 1, call.completionCount);
        }
        RecordingPluginCall files = new RecordingPluginCall("getLocalFileManagementState", new JSObject());
        invoke(handle, "getLocalFileManagementState", files);
        assertEquals(1, files.completionCount);
        if (fileFailure) {
            assertNotNull(files.rejectionMessage);
            assertTrue(files.rejectionMessage.contains("本地文件初始化失败"));
            assertNotNull(files.rejectionException);
        } else {
            assertNull(files.rejectionMessage);
            assertNotNull(files.resolvedData);
        }
    }

    private static void invoke(PluginHandle handle, String method, RecordingPluginCall call) {
        try {
            handle.invoke(method, call);
        } catch (Exception error) {
            throw new AssertionError(method, error);
        }
    }

    private static void resetLocalServices() throws Exception {
        Field instance = ExportService.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        LocalFileStore.clearInstanceForTest();
    }
}
