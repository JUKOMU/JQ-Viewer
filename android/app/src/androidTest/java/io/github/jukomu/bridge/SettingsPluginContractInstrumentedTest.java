package io.github.jukomu.bridge;

import com.getcapacitor.JSObject;
import io.github.jukomu.bridge.handler.SettingsPluginHandler;
import io.github.jukomu.feature.settings.SettingsService;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class SettingsPluginContractInstrumentedTest {

    private JmcomicPlugin plugin;
    private FakeSettingsService settingsService;

    @Before
    public void setUp() throws Exception {
        plugin = new JmcomicPlugin();
        settingsService = new FakeSettingsService();
        injectSettingsHandler(plugin, settingsService);
    }

    @Test
    public void numericSettingsValidateBoundsBeforeCallingService() {
        RecordingPluginCall downloadMissing = call("setDownloadConcurrency");
        RecordingPluginCall downloadLow = call("setDownloadConcurrency", "n", 0);
        RecordingPluginCall preloadHigh = call("setPreloadConcurrency", "n", 13);
        RecordingPluginCall readerLow = call("setReaderPreloadPages", "n", 4);
        RecordingPluginCall readerHigh = call("setReaderPreloadPages", "n", 51);

        plugin.setDownloadConcurrency(downloadMissing);
        plugin.setDownloadConcurrency(downloadLow);
        plugin.setPreloadConcurrency(preloadHigh);
        plugin.setReaderPreloadPages(readerLow);
        plugin.setReaderPreloadPages(readerHigh);

        assertRejected(downloadMissing, "n must be between 1 and 12");
        assertRejected(downloadLow, "n must be between 1 and 12");
        assertRejected(preloadHigh, "n must be between 1 and 12");
        assertRejected(readerLow, "n must be between 5 and 50");
        assertRejected(readerHigh, "n must be between 5 and 50");
        assertEquals(0, settingsService.downloadConcurrency);
        assertEquals(0, settingsService.preloadConcurrency);
        assertEquals(0, settingsService.readerPreloadPages);
    }

    @Test
    public void synchronousSettingsResolveExpectedPayloads() throws Exception {
        RecordingPluginCall download = call("setDownloadConcurrency", "n", 1);
        RecordingPluginCall preload = call("setPreloadConcurrency", "n", 12);
        RecordingPluginCall reader = call("setReaderPreloadPages", "n", 5);
        RecordingPluginCall ocr = call("setOcrEnabled", "enabled", false);
        RecordingPluginCall getPublic = call("getDownloadPublic");
        RecordingPluginCall getAll = call("getAllSettings");

        plugin.setDownloadConcurrency(download);
        plugin.setPreloadConcurrency(preload);
        plugin.setReaderPreloadPages(reader);
        plugin.setOcrEnabled(ocr);
        plugin.getDownloadPublic(getPublic);
        plugin.getAllSettings(getAll);

        assertEquals(1, settingsService.downloadConcurrency);
        assertEquals(12, settingsService.preloadConcurrency);
        assertEquals(5, settingsService.readerPreloadPages);
        assertFalse(settingsService.ocrEnabled);
        assertTrue(download.resolvedData.getBool("success"));
        assertTrue(preload.resolvedData.getBool("success"));
        assertTrue(reader.resolvedData.getBool("success"));
        assertTrue(ocr.resolvedData.getBool("success"));
        assertTrue(getPublic.resolvedData.getBool("downloadPublic"));
        assertEquals(15, getAll.resolvedData.getInteger("readerPreloadPages").intValue());
        assertSynchronous(download, preload, reader, ocr, getPublic, getAll);
    }

    @Test
    public void ocrEnabledIsRequired() {
        RecordingPluginCall call = call("setOcrEnabled");

        plugin.setOcrEnabled(call);

        assertRejected(call, "enabled is required");
    }

    @Test
    public void downloadPublicValidationRejectsBeforeKeepAliveAndRelocation() {
        settingsService.validationError = "switch blocked";
        RecordingPluginCall call = call("setDownloadPublic", "open", true);

        plugin.setDownloadPublic(call);

        assertTrue(settingsService.validatedOpen);
        assertEquals("switch blocked", call.rejectionMessage);
        assertFalse(call.isKeptAlive());
        assertNull(settingsService.relocateCallback);
        assertEquals(1, call.completionCount);
    }

    @Test
    public void downloadPublicDefaultsToPrivateAndCompletesDelayedSuccess() throws Exception {
        RecordingPluginCall call = call("setDownloadPublic");

        plugin.setDownloadPublic(call);

        assertFalse(settingsService.validatedOpen);
        assertFalse(settingsService.relocatedOpen);
        assertTrue(call.isKeptAlive());
        assertEquals(0, call.completionCount);

        settingsService.completeRelocationSuccess();

        assertTrue(call.resolvedData.getBool("success"));
        assertFalse(call.resolvedData.getBool("downloadPublic"));
        assertEquals(3, call.resolvedData.getInteger("moved").intValue());
        assertEquals(1, call.completionCount);
    }

    @Test
    public void downloadPublicRelocationErrorsPreserveMessageAndThrowable() {
        RecordingPluginCall call = call("setDownloadPublic", "open", true);
        IllegalStateException failure = new IllegalStateException("relocation failed");

        plugin.setDownloadPublic(call);
        settingsService.completeRelocationError(failure);

        assertTrue(call.isKeptAlive());
        assertEquals("relocation failed", call.rejectionMessage);
        assertEquals(failure, call.rejectionException);
        assertEquals(1, call.completionCount);
    }

    @Test
    public void synchronousServiceErrorsPreserveMessageAndThrowable() {
        IllegalStateException failure = new IllegalStateException("save failed");
        settingsService.failure = failure;
        RecordingPluginCall call = call("setDownloadConcurrency", "n", 6);

        plugin.setDownloadConcurrency(call);

        assertEquals("save failed", call.rejectionMessage);
        assertEquals(failure, call.rejectionException);
        assertSynchronous(call);
    }

    private static RecordingPluginCall call(String methodName, Object... entries) {
        JSObject data = new JSObject();
        for (int index = 0; index < entries.length; index += 2) {
            data.put((String) entries[index], entries[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static JSONObject jsonObject(Object... entries) {
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index < entries.length; index += 2) {
                result.put((String) entries[index], entries[index + 1]);
            }
            return result;
        } catch (JSONException error) {
            throw new AssertionError(error);
        }
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

    private static void injectSettingsHandler(JmcomicPlugin plugin,
                                              SettingsService settingsService) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("settingsHandler");
        field.setAccessible(true);
        field.set(plugin, new SettingsPluginHandler(settingsService));
    }

    private static final class FakeSettingsService extends SettingsService {

        private int downloadConcurrency;
        private int preloadConcurrency;
        private int readerPreloadPages;
        private boolean ocrEnabled = true;
        private boolean validatedOpen;
        private boolean relocatedOpen;
        private String validationError;
        private RelocateCallback relocateCallback;
        private RuntimeException failure;

        private FakeSettingsService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void setDownloadConcurrency(int concurrency) {
            if (failure != null) {
                throw failure;
            }
            downloadConcurrency = concurrency;
        }

        @Override
        public void setPreloadConcurrency(int concurrency) {
            preloadConcurrency = concurrency;
        }

        @Override
        public String validateSwitch(boolean open) {
            validatedOpen = open;
            return validationError;
        }

        @Override
        public void relocate(boolean open, RelocateCallback callback) {
            relocatedOpen = open;
            relocateCallback = callback;
        }

        @Override
        public boolean getDownloadPublic() {
            return true;
        }

        @Override
        public JSONObject getAllSettings() {
            return jsonObject(
                "readerPreloadPages", 15,
                "downloadConcurrency", 6,
                "preloadConcurrency", 6);
        }

        @Override
        public void setReaderPreloadPages(int pages) {
            readerPreloadPages = pages;
        }

        @Override
        public void setOcrEnabled(boolean enabled) {
            ocrEnabled = enabled;
        }

        private void completeRelocationSuccess() {
            relocateCallback.onSuccess(jsonObject(
                "success", true,
                "downloadPublic", relocatedOpen,
                "moved", 3));
        }

        private void completeRelocationError(Exception error) {
            relocateCallback.onError(error.getMessage(), error);
        }
    }
}
