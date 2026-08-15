package io.github.jukomu.bridge;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import io.github.jukomu.bridge.handler.CachePluginHandler;
import io.github.jukomu.feature.cache.CacheCapacityPolicy;
import io.github.jukomu.feature.preload.PreloadService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class CachePluginContractInstrumentedTest {

    private JmcomicPlugin plugin;
    private FakePreloadService preloadService;

    @Before
    public void setUp() throws Exception {
        plugin = new JmcomicPlugin();
        preloadService = new FakePreloadService();
        injectCacheHandler(plugin, preloadService);
    }

    @Test
    public void preloadImagesForwardsDefaultsAndSkipsInvalidEntries() throws Exception {
        RecordingPluginCall emptyCall = call("preloadImages");

        plugin.preloadImages(emptyCall);

        assertNull(preloadService.photoId);
        assertEquals("image", preloadService.type);
        assertFalse(preloadService.replacePending);
        assertEquals(0, preloadService.images.length());
        assertEquals(0, emptyCall.resolvedData.getJSONArray("cached").length());

        JSArray images = new JSArray();
        images.put(new JSONObject().put("sortOrder", 7));
        images.put("invalid");
        RecordingPluginCall populatedCall = call(
            "preloadImages",
            "photoId", "photo-1",
            "type", "thumb",
            "replacePending", true,
            "images", images);

        plugin.preloadImages(populatedCall);

        assertEquals("photo-1", preloadService.photoId);
        assertEquals("thumb", preloadService.type);
        assertTrue(preloadService.replacePending);
        assertEquals(1, preloadService.images.length());
        assertEquals(7, preloadService.images.getJSONObject(0).getInt("sortOrder"));
        assertEquals(1, populatedCall.resolvedData.getJSONArray("pending").length());
        assertSynchronous(emptyCall, populatedCall);
    }

    @Test
    public void capacityValidationAndPayloadRemainStable() throws Exception {
        RecordingPluginCall missing = call("setCacheCapacity");
        RecordingPluginCall belowMinimum = call("setCacheCapacity", "mb", 63);
        RecordingPluginCall aboveMaximum = call("setCacheCapacity", "mb", 1025);

        plugin.setCacheCapacity(missing);
        plugin.setCacheCapacity(belowMinimum);
        plugin.setCacheCapacity(aboveMaximum);

        assertRejected(missing, "mb must be between 64 and 1024");
        assertRejected(belowMinimum, "mb must be between 64 and 1024");
        assertRejected(aboveMaximum, "mb must be between 64 and 1024");

        RecordingPluginCall valid = call("setCacheCapacity", "mb", 256);
        plugin.setCacheCapacity(valid);

        assertEquals(256L, preloadService.capacityMb);
        assertTrue(valid.resolvedData.getBool("success"));
        assertEquals(256, valid.resolvedData.getInteger("requestedMb").intValue());
        assertEquals(192, valid.resolvedData.getInteger("effectiveMb").intValue());
        assertSynchronous(valid);
    }

    @Test
    public void capacityContentsAndClearUseServicePayloads() throws Exception {
        RecordingPluginCall capacity = call("getCacheCapacityInfo");
        RecordingPluginCall contents = call("getImageCacheContents");
        RecordingPluginCall clear = call("clearImageCache");

        plugin.getCacheCapacityInfo(capacity);
        plugin.getImageCacheContents(contents);
        plugin.clearImageCache(clear);

        assertEquals(256, capacity.resolvedData.getInteger("requestedMb").intValue());
        assertEquals(1, contents.resolvedData.getJSONArray("entries").length());
        assertTrue(clear.resolvedData.getBool("success"));
        assertTrue(preloadService.cleared);
        assertSynchronous(capacity, contents, clear);
    }

    @Test
    public void serviceErrorsPreserveMessageAndThrowable() {
        IllegalStateException failure = new IllegalStateException("cache failed");
        preloadService.failure = failure;
        RecordingPluginCall call = call("getCacheCapacityInfo");

        plugin.getCacheCapacityInfo(call);

        assertEquals("cache failed", call.rejectionMessage);
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

    private static void injectCacheHandler(JmcomicPlugin plugin,
                                           PreloadService preloadService) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("cacheHandler");
        field.setAccessible(true);
        field.set(plugin, new CachePluginHandler(preloadService));
    }

    private static final class FakePreloadService extends PreloadService {

        private String photoId;
        private String type;
        private JSONArray images;
        private boolean replacePending;
        private long capacityMb;
        private boolean cleared;
        private RuntimeException failure;

        private FakePreloadService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public JSONObject preloadImages(String photoId, String type, JSONArray images,
                                        boolean replacePending) {
            this.photoId = photoId;
            this.type = type;
            this.images = images;
            this.replacePending = replacePending;
            return jsonObject(
                "cached", new JSONArray(),
                "pending", new JSONArray().put(7));
        }

        @Override
        public CacheCapacityPolicy.Result setCacheCapacity(long capacityMb) {
            this.capacityMb = capacityMb;
            return null;
        }

        @Override
        public JSONObject getCacheCapacityInfo() {
            if (failure != null) {
                throw failure;
            }
            return jsonObject(
                "capacityMb", 192,
                "requestedMb", 256,
                "effectiveMb", 192);
        }

        @Override
        public JSONObject getImageCacheContents() {
            return jsonObject(
                "entries",
                new JSONArray().put(jsonObject(
                    "photoId", "photo-1",
                    "sortOrder", 7,
                    "type", "image")));
        }

        @Override
        public void clearImageCache() {
            cleared = true;
        }
    }
}
