package io.github.jukomu.bridge;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import io.github.jukomu.bridge.handler.HistoryPluginHandler;
import io.github.jukomu.feature.history.data.HistoryStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class HistoryPluginContractInstrumentedTest {

    private IsolatedDatabaseContext context;
    private JmcomicPlugin plugin;

    @Before
    public void setUp() throws Exception {
        resetStoreSingleton();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(targetContext.getCacheDir(),
            "history-plugin-contract-" + System.nanoTime());
        context = new IsolatedDatabaseContext(targetContext, directory);
        plugin = new ContextPlugin(context);
        injectHistoryHandler(plugin, HistoryStore.getInstance(context));
    }

    @After
    public void tearDown() throws Exception {
        closeAndResetStoreSingleton();
        context.deleteTestDatabases();
    }

    @Test
    public void missingInputsPreserveHistoryDefaultsAndPayloads() throws Exception {
        RecordingPluginCall recordCall = call("recordBrowse");
        plugin.recordBrowse(recordCall);
        assertSuccess(recordCall);

        RecordingPluginCall browseCall = call("getBrowseHistory");
        plugin.getBrowseHistory(browseCall);
        JSONArray browseItems = browseCall.resolvedData.getJSONArray("items");
        assertEquals(1, browseItems.length());
        JSONObject browseItem = browseItems.getJSONObject(0);
        assertEquals("", browseItem.getString("albumId"));
        assertEquals("", browseItem.getString("albumTitle"));
        assertEquals("", browseItem.getString("coverUrl"));
        assertEquals("", browseItem.getString("authors"));
        assertEquals("", browseItem.getString("chapterId"));
        assertEquals("", browseItem.getString("chapterTitle"));

        RecordingPluginCall parseWrite = call("addParseHistory", "text", "keyword");
        plugin.addParseHistory(parseWrite);
        assertSuccess(parseWrite);

        RecordingPluginCall parseRead = call("getParseHistory");
        plugin.getParseHistory(parseRead);
        JSONArray parseItems = parseRead.resolvedData.getJSONArray("items");
        assertEquals(1, parseItems.length());
        assertEquals("single-mode", parseItems.getJSONObject(0).getString("mode"));

        assertNotKeptAlive(recordCall, browseCall, parseWrite, parseRead);
    }

    @Test
    public void deleteAndClearMethodsKeepTheirSuccessContract() throws Exception {
        plugin.recordBrowse(call("recordBrowse", "albumId", "album-1"));
        plugin.recordBrowse(call("recordBrowse", "albumId", "album-2"));
        RecordingPluginCall browseRead = call("getBrowseHistory");
        plugin.getBrowseHistory(browseRead);
        int browseId = browseRead.resolvedData.getJSONArray("items")
            .getJSONObject(0).getInt("id");

        RecordingPluginCall deleteBrowse = call("deleteBrowseItem", "id", browseId);
        plugin.deleteBrowseItem(deleteBrowse);
        assertSuccess(deleteBrowse);
        RecordingPluginCall browseAfterDelete = call("getBrowseHistory");
        plugin.getBrowseHistory(browseAfterDelete);
        assertEquals(1, browseAfterDelete.resolvedData.getJSONArray("items").length());

        RecordingPluginCall clearBrowse = call("clearBrowseHistory");
        plugin.clearBrowseHistory(clearBrowse);
        assertSuccess(clearBrowse);
        RecordingPluginCall browseAfterClear = call("getBrowseHistory");
        plugin.getBrowseHistory(browseAfterClear);
        assertEquals(0, browseAfterClear.resolvedData.getJSONArray("items").length());

        plugin.addParseHistory(call("addParseHistory", "text", "first"));
        plugin.addParseHistory(call("addParseHistory", "text", "second"));
        RecordingPluginCall parseRead = call("getParseHistory");
        plugin.getParseHistory(parseRead);
        int parseId = parseRead.resolvedData.getJSONArray("items")
            .getJSONObject(0).getInt("id");

        RecordingPluginCall deleteParse = call("deleteParseItem", "id", parseId);
        plugin.deleteParseItem(deleteParse);
        assertSuccess(deleteParse);
        RecordingPluginCall clearParse = call("clearParseHistory");
        plugin.clearParseHistory(clearParse);
        assertSuccess(clearParse);
        RecordingPluginCall parseAfterClear = call("getParseHistory");
        plugin.getParseHistory(parseAfterClear);
        assertEquals(0, parseAfterClear.resolvedData.getJSONArray("items").length());

        assertNotKeptAlive(deleteBrowse, clearBrowse, deleteParse, clearParse);
    }

    @Test
    public void historyWrapperRejectsUnexpectedInputFailureWithThrowable() {
        RecordingPluginCall call = new RecordingPluginCall(
            "getBrowseHistory", new JSObject()) {
            @Override
            public Integer getInt(String name, Integer defaultValue) {
                throw new IllegalStateException("input unavailable");
            }
        };

        plugin.getBrowseHistory(call);

        assertEquals("input unavailable", call.rejectionMessage);
        assertTrue(call.rejectionException instanceof IllegalStateException);
        assertEquals(1, call.completionCount);
        assertFalse(call.isKeptAlive());
    }

    @Test
    public void allHistoryMethodsRemainNonKeepAlive() {
        RecordingPluginCall getBrowse = call("getBrowseHistory");
        RecordingPluginCall record = call("recordBrowse");
        RecordingPluginCall clearBrowse = call("clearBrowseHistory");
        RecordingPluginCall deleteBrowse = call("deleteBrowseItem");
        RecordingPluginCall getParse = call("getParseHistory");
        RecordingPluginCall addParse = call("addParseHistory");
        RecordingPluginCall clearParse = call("clearParseHistory");
        RecordingPluginCall deleteParse = call("deleteParseItem");

        plugin.getBrowseHistory(getBrowse);
        plugin.recordBrowse(record);
        plugin.clearBrowseHistory(clearBrowse);
        plugin.deleteBrowseItem(deleteBrowse);
        plugin.getParseHistory(getParse);
        plugin.addParseHistory(addParse);
        plugin.clearParseHistory(clearParse);
        plugin.deleteParseItem(deleteParse);

        assertNotKeptAlive(getBrowse, record, clearBrowse, deleteBrowse,
            getParse, addParse, clearParse, deleteParse);
    }

    private static RecordingPluginCall call(String methodName, Object... entries) {
        JSObject data = new JSObject();
        for (int index = 0; index < entries.length; index += 2) {
            data.put((String) entries[index], entries[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static void assertSuccess(RecordingPluginCall call) throws Exception {
        assertNotNull(call.resolvedData);
        assertTrue(call.resolvedData.getBoolean("success"));
        assertEquals(1, call.completionCount);
        assertFalse(call.isKeptAlive());
    }

    private static void assertNotKeptAlive(RecordingPluginCall... calls) {
        for (RecordingPluginCall call : calls) {
            assertFalse(call.getMethodName(), call.isKeptAlive());
            assertEquals(call.getMethodName(), 1, call.completionCount);
        }
    }

    private static void resetStoreSingleton() throws Exception {
        Field field = HistoryStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static void closeAndResetStoreSingleton() throws Exception {
        Field field = HistoryStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        HistoryStore store = (HistoryStore) field.get(null);
        if (store != null) {
            store.close();
        }
        field.set(null, null);
    }

    private static void injectHistoryHandler(JmcomicPlugin plugin,
                                             HistoryStore historyStore) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("historyHandler");
        field.setAccessible(true);
        field.set(plugin, new HistoryPluginHandler(historyStore));
    }

    private static final class ContextPlugin extends JmcomicPlugin {

        private final Context context;

        private ContextPlugin(Context context) {
            this.context = context;
        }

        @Override
        public Context getContext() {
            return context;
        }
    }
}
