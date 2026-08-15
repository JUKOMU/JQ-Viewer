package io.github.jukomu.bridge;

import android.content.pm.ActivityInfo;
import android.view.KeyEvent;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import io.github.jukomu.MainActivity;
import io.github.jukomu.bridge.handler.ReaderPluginHandler;
import io.github.jukomu.feature.settings.SettingsService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class ReaderPluginContractInstrumentedTest {

    private JmcomicPlugin plugin;
    private FakeSettingsService settingsService;
    private ReaderPluginHandler readerHandler;
    private JSObject volumeEvent;
    private JSObject launchRouteEvent;

    @Before
    public void setUp() throws Exception {
        plugin = new JmcomicPlugin();
        settingsService = new FakeSettingsService();
        readerHandler = new ReaderPluginHandler(
            () -> null,
            () -> null,
            () -> settingsService,
            event -> volumeEvent = event,
            event -> launchRouteEvent = event);
        injectReaderHandler(plugin, readerHandler);
        ReaderPluginHandler.setPendingLaunchRoute(null);
    }

    @After
    public void tearDown() {
        if (readerHandler != null) {
            readerHandler.destroy();
        }
        ReaderPluginHandler.setPendingLaunchRoute(null);
    }

    @Test
    public void requiredArgumentsUseCurrentMessages() {
        RecordingPluginCall display = call("setReaderDisplayMode", "mode", "paged");
        RecordingPluginCall orientation = call("setReaderScreenOrientation");
        RecordingPluginCall brightness = call("setReaderBrightness");
        RecordingPluginCall keepScreenOn = call("setReaderKeepScreenOn");
        RecordingPluginCall volume = call("setReaderVolumeNavigation");
        RecordingPluginCall toolbar = call("setReaderAutoShowToolbarAtEnd");
        RecordingPluginCall fullscreen = call("setReaderFullscreen");
        RecordingPluginCall state = call("setReaderState", "isActive", true);

        plugin.setReaderDisplayMode(display);
        plugin.setReaderScreenOrientation(orientation);
        plugin.setReaderBrightness(brightness);
        plugin.setReaderKeepScreenOn(keepScreenOn);
        plugin.setReaderVolumeNavigation(volume);
        plugin.setReaderAutoShowToolbarAtEnd(toolbar);
        plugin.setReaderFullscreen(fullscreen);
        plugin.setReaderState(state);

        assertRejected(display, "mode must be vertical or horizontal");
        assertRejected(orientation, "orientation is required");
        assertRejected(brightness, "brightness is required");
        assertRejected(keepScreenOn, "enabled is required");
        assertRejected(volume, "enabled is required");
        assertRejected(toolbar, "enabled is required");
        assertRejected(fullscreen, "enabled is required");
        assertRejected(state, "isActive and isVertical are required");
    }

    @Test
    public void settingsCallsResolveSuccessPayloads() {
        RecordingPluginCall display = call("setReaderDisplayMode", "mode", "horizontal");
        RecordingPluginCall orientation = call(
            "setReaderScreenOrientation", "orientation", "landscape");
        RecordingPluginCall brightness = call("setReaderBrightness", "brightness", 0.4f);
        RecordingPluginCall keepScreenOn = call("setReaderKeepScreenOn", "enabled", false);
        RecordingPluginCall volume = call("setReaderVolumeNavigation", "enabled", true);
        RecordingPluginCall toolbar = call(
            "setReaderAutoShowToolbarAtEnd", "enabled", false);

        plugin.setReaderDisplayMode(display);
        plugin.setReaderScreenOrientation(orientation);
        plugin.setReaderBrightness(brightness);
        plugin.setReaderKeepScreenOn(keepScreenOn);
        plugin.setReaderVolumeNavigation(volume);
        plugin.setReaderAutoShowToolbarAtEnd(toolbar);

        assertEquals("horizontal", settingsService.displayMode);
        assertEquals("landscape", settingsService.orientation);
        assertEquals(0.4f, settingsService.brightness, 0f);
        assertFalse(settingsService.keepScreenOn);
        assertTrue(settingsService.volumeNavigation);
        assertFalse(settingsService.autoShowToolbarAtEnd);
        assertSuccess(display, orientation, brightness, keepScreenOn, volume, toolbar);
    }

    @Test
    public void settingsFailureRejectsWithSameException() {
        IllegalStateException failure = new IllegalStateException("settings failed");
        settingsService.failure = failure;
        RecordingPluginCall call = call("setReaderDisplayMode", "mode", "vertical");

        plugin.setReaderDisplayMode(call);

        assertEquals("settings failed", call.rejectionMessage);
        assertSame(failure, call.rejectionException);
    }

    @Test
    public void stateQueriesAndVolumeEventUseExpectedPayload() {
        settingsService.volumeNavigation = true;
        RecordingPluginCall state = call(
            "setReaderState", "isActive", true, "isVertical", false);

        plugin.setReaderState(state);
        plugin.notifyVolumeKey("down");

        assertTrue(plugin.isReaderActive());
        assertFalse(plugin.isReaderVertical());
        assertTrue(plugin.isVolumeNavigationEnabled());
        assertTrue(state.resolvedData.getBool("success"));
        assertNotNull(volumeEvent);
        assertEquals("down", volumeEvent.getString("direction"));
    }

    @Test
    public void launchRoutePublishesAndIsConsumedOnce() {
        ReaderPluginHandler.setPendingLaunchRoute("/download");
        plugin.notifyLaunchRoute("/download");
        RecordingPluginCall first = call("consumeLaunchRoute");
        RecordingPluginCall second = call("consumeLaunchRoute");

        plugin.consumeLaunchRoute(first);
        plugin.consumeLaunchRoute(second);

        assertEquals("/download", launchRouteEvent.getString("route"));
        assertEquals("/download", first.resolvedData.getString("route"));
        assertEquals(0, second.resolvedData.length());
        assertEquals(1, first.completionCount);
        assertEquals(1, second.completionCount);
    }

    @Test
    public void realActivityAppliesWindowStateAndRoutesVolumeKey() throws Exception {
        readerHandler.destroy();
        readerHandler = null;

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Throwable[] failure = new Throwable[1];
            scenario.onActivity(activity -> {
                try {
                    JmcomicPlugin activePlugin = JmcomicPlugin.getInstance();
                    assertNotNull(activePlugin);

                    RecordingPluginCall orientation = call(
                        "setReaderScreenOrientation", "orientation", "portrait");
                    RecordingPluginCall brightness = call(
                        "setReaderBrightness", "brightness", 0.35f);
                    RecordingPluginCall keepScreenOn = call(
                        "setReaderKeepScreenOn", "enabled", true);
                    RecordingPluginCall volume = call(
                        "setReaderVolumeNavigation", "enabled", true);
                    RecordingPluginCall fullscreen = call(
                        "setReaderFullscreen", "enabled", true);
                    RecordingPluginCall state = call(
                        "setReaderState", "isActive", true, "isVertical", true);

                    activePlugin.setReaderScreenOrientation(orientation);
                    activePlugin.setReaderBrightness(brightness);
                    activePlugin.setReaderKeepScreenOn(keepScreenOn);
                    activePlugin.setReaderVolumeNavigation(volume);
                    activePlugin.setReaderFullscreen(fullscreen);
                    activePlugin.setReaderState(state);

                    assertSuccess(
                        orientation, brightness, keepScreenOn, volume, fullscreen, state);
                    assertEquals(
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        activity.getRequestedOrientation());
                    assertEquals(
                        0.35f,
                        activity.getWindow().getAttributes().screenBrightness,
                        0f);
                    assertTrue((activity.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
                    assertTrue(activity.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)));

                    ReaderPluginHandler activeHandler = readerHandler(activePlugin);
                    assertNotNull(field(activeHandler, "readerInsetsContainer"));

                    RecordingPluginCall exitFullscreen = call(
                        "setReaderFullscreen", "enabled", false);
                    RecordingPluginCall exitState = call(
                        "setReaderState", "isActive", false, "isVertical", false);
                    RecordingPluginCall resetOrientation = call(
                        "setReaderScreenOrientation", "orientation", "auto");
                    RecordingPluginCall resetBrightness = call(
                        "setReaderBrightness", "brightness", -1f);
                    RecordingPluginCall clearKeepScreenOn = call(
                        "setReaderKeepScreenOn", "enabled", false);
                    RecordingPluginCall clearVolume = call(
                        "setReaderVolumeNavigation", "enabled", false);

                    activePlugin.setReaderFullscreen(exitFullscreen);
                    activePlugin.setReaderState(exitState);
                    activePlugin.setReaderScreenOrientation(resetOrientation);
                    activePlugin.setReaderBrightness(resetBrightness);
                    activePlugin.setReaderKeepScreenOn(clearKeepScreenOn);
                    activePlugin.setReaderVolumeNavigation(clearVolume);

                    assertSuccess(
                        exitFullscreen,
                        exitState,
                        resetOrientation,
                        resetBrightness,
                        clearKeepScreenOn,
                        clearVolume);

                    activeHandler.destroy();
                    assertFalse(activeHandler.isReaderActive());
                    assertNull(field(activeHandler, "readerInsetsContainer"));
                } catch (Throwable error) {
                    failure[0] = error;
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (failure[0] != null) {
                throw new AssertionError(failure[0]);
            }
        }
    }

    private static RecordingPluginCall call(String methodName, Object... values) {
        JSObject data = new JSObject();
        for (int index = 0; index < values.length; index += 2) {
            data.put((String) values[index], values[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static void assertSuccess(RecordingPluginCall... calls) {
        for (RecordingPluginCall call : calls) {
            assertEquals(1, call.completionCount);
            assertNotNull(call.resolvedData);
            assertTrue(call.resolvedData.getBool("success"));
        }
    }

    private static void assertRejected(RecordingPluginCall call, String message) {
        assertEquals(message, call.rejectionMessage);
        assertEquals(1, call.completionCount);
        assertNull(call.resolvedData);
    }

    private static void injectReaderHandler(JmcomicPlugin plugin,
                                            ReaderPluginHandler handler) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("readerHandler");
        field.setAccessible(true);
        field.set(plugin, handler);
    }

    private static ReaderPluginHandler readerHandler(JmcomicPlugin plugin) throws Exception {
        return (ReaderPluginHandler) field(plugin, "readerHandler");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class FakeSettingsService extends SettingsService {

        private String displayMode;
        private String orientation;
        private float brightness;
        private boolean keepScreenOn;
        private boolean volumeNavigation;
        private boolean autoShowToolbarAtEnd;
        private RuntimeException failure;

        private FakeSettingsService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void setReaderDisplayMode(String mode) {
            failIfNeeded();
            displayMode = mode;
        }

        @Override
        public void setReaderScreenOrientation(String value) {
            failIfNeeded();
            orientation = value;
        }

        @Override
        public void setReaderBrightness(float value) {
            failIfNeeded();
            brightness = value;
        }

        @Override
        public void setReaderKeepScreenOn(boolean enabled) {
            failIfNeeded();
            keepScreenOn = enabled;
        }

        @Override
        public void setReaderVolumeNavigation(boolean enabled) {
            failIfNeeded();
            volumeNavigation = enabled;
        }

        @Override
        public boolean getReaderVolumeNavigation() {
            return volumeNavigation;
        }

        @Override
        public void setReaderAutoShowToolbarAtEnd(boolean enabled) {
            failIfNeeded();
            autoShowToolbarAtEnd = enabled;
        }

        private void failIfNeeded() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
