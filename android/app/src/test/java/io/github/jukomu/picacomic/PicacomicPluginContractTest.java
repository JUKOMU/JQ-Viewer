package io.github.jukomu.picacomic;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import io.github.jukomu.feature.cache.ImageCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PicacomicPluginContractTest {

    private final ImageCache cache = ImageCache.getInstance();
    private FakePicacomicRemoteClient fake;
    private PicacomicRuntime runtime;

    @Before
    public void setUp() {
        PicacomicCacheNamespace.clear(cache);
        fake = new FakePicacomicRemoteClient();
        runtime = PicacomicRuntime.createIsolated(() -> fake, cache);
    }

    @After
    public void tearDown() {
        runtime.close();
        PicacomicCacheNamespace.clear(cache);
    }

    @Test
    public void authStateDoesNotCreateRuntimeAndLoginResolvesSafeSnapshotOnce() throws Exception {
        PicacomicRuntime.resetProcessForTests();
        PicacomicPlugin plugin = new PicacomicPlugin();
        RecordingPicacomicPluginCall initial = call("getAuthState");

        plugin.getAuthState(initial);

        assertEquals(1, initial.completionCount);
        assertEquals("signed_out", initial.resolvedData.getString("state"));
        assertFalse(PicacomicRuntime.exists());

        RecordingPicacomicPluginCall login = call("login",
            "usernameOrEmail", "fixture-user", "password", "fixture-password");
        PicacomicPlugin injected = PicacomicPlugin.forTests(runtime);
        injected.login(login);
        assertTrue(login.completed.await(1, TimeUnit.SECONDS));

        assertEquals(1, login.completionCount);
        assertTrue(login.isKeptAlive());
        assertEquals("signed_in", login.resolvedData.getString("state"));
        assertEquals("fixture-user", login.resolvedData.getJSONObject("user")
            .getString("username"));
        assertFalse(login.resolvedData.toString().contains("fixture-password"));
        injected.handleOnDestroy();
    }

    @Test
    public void pluginRecreationKeepsSameProcessSession() throws Exception {
        runtime.login("fixture-user", "fixture-password", new PicacomicCancellationToken());
        PicacomicPlugin first = PicacomicPlugin.forTests(runtime);
        first.handleOnDestroy();
        PicacomicPlugin second = PicacomicPlugin.forTests(runtime);
        RecordingPicacomicPluginCall state = call("getAuthState");

        second.getAuthState(state);

        assertEquals(1, state.completionCount);
        assertEquals("signed_in", state.resolvedData.getString("state"));
        assertEquals("fixture-user", state.resolvedData.getJSONObject("user")
            .getString("username"));
        second.handleOnDestroy();
    }

    @Test
    public void invalidBridgeInputsUseStableCodesBeforeRuntimeLookup() {
        PicacomicPlugin plugin = PicacomicPlugin.forTests(runtime);
        RecordingPicacomicPluginCall album = call("getAlbum");
        plugin.getAlbum(album);
        assertEquals(1, album.completionCount);
        assertEquals("PICACOMIC_INVALID_ARGUMENT", album.rejectionCode);

        RecordingPicacomicPluginCall chapter = call("getPhoto",
            "albumId", "album-1", "chapterId", "chapter-1", "order", 0);
        plugin.getPhoto(chapter);
        assertEquals(1, chapter.completionCount);
        assertEquals("PICACOMIC_INVALID_ARGUMENT", chapter.rejectionCode);
        plugin.handleOnDestroy();
    }

    @Test
    public void imageRequestReturnsPendingAndEmitsSafeReadyEvent() throws Exception {
        runtime.login("fixture-user", "fixture-password", new PicacomicCancellationToken());
        PicacomicChapterDetail detail = runtime.getPhoto(
            ChapterRef.of("album-1", "chapter-1", 1), new PicacomicCancellationToken());
        PicacomicImageRef image = detail.images.get(0);
        java.util.concurrent.atomic.AtomicReference<PicacomicImageEvent> event =
            new java.util.concurrent.atomic.AtomicReference<>();
        PicacomicPlugin plugin = PicacomicPlugin.forTests(runtime, event::set);
        RecordingPicacomicPluginCall request = call("requestImages",
            "imageKeys", new JSArray().put(image.imageKey));

        plugin.requestImages(request);
        assertTrue(request.completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, request.completionCount);
        assertTrue(request.isKeptAlive());
        assertTrue(request.resolvedData.getJSONArray("pending").length() == 1);
        waitForEvent(event);
        assertEquals(PicacomicImageEvent.Type.READY, event.get().type);
        assertNotNull(event.get().toJsObject().getString("imageKey"));
        assertFalse(event.get().toJsObject().toString().contains("img.example.invalid"));
        plugin.handleOnDestroy();
    }

    @Test
    public void logoutIsIdempotentAndReturnsSignedOut() throws Exception {
        runtime.login("fixture-user", "fixture-password", new PicacomicCancellationToken());
        PicacomicPlugin plugin = PicacomicPlugin.forTests(runtime);
        RecordingPicacomicPluginCall logout = call("logout");
        plugin.logout(logout);

        assertTrue(logout.completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, logout.completionCount);
        assertEquals("signed_out", logout.resolvedData.getString("state"));
        assertEquals(1, fake.closeCalls.get());

        RecordingPicacomicPluginCall secondLogout = call("logout");
        plugin.logout(secondLogout);
        assertTrue(secondLogout.completed.await(1, TimeUnit.SECONDS));
        assertEquals(1, secondLogout.completionCount);
        assertEquals("signed_out", secondLogout.resolvedData.getString("state"));
        plugin.handleOnDestroy();
    }

    @Test
    public void pluginDestroyCancelsItsImageScopeButKeepsRuntimeSession() throws Exception {
        runtime.login("fixture-user", "fixture-password", new PicacomicCancellationToken());
        PicacomicChapterDetail detail = runtime.getPhoto(
            ChapterRef.of("album-1", "chapter-1", 1), new PicacomicCancellationToken());
        PicacomicImageRef image = detail.images.get(0);
        fake.blockImage = true;
        PicacomicPlugin plugin = PicacomicPlugin.forTests(runtime);
        RecordingPicacomicPluginCall request = call("requestImages",
            "imageKeys", new JSArray().put(image.imageKey));

        plugin.requestImages(request);
        assertTrue(request.completed.await(1, TimeUnit.SECONDS));
        assertTrue(fake.imageStarted.await(1, TimeUnit.SECONDS));
        plugin.handleOnDestroy();
        fake.releaseImage.countDown();
        waitForPendingImages();

        assertFalse(cache.has(image.cacheKey()));
        assertEquals("signed_in", runtime.getAuthState().state.getWireValue());
        runtime.close();
    }

    private static RecordingPicacomicPluginCall call(String method, Object... values) {
        JSObject data = new JSObject();
        for (int index = 0; index < values.length; index += 2) {
            data.put((String) values[index], values[index + 1]);
        }
        return new RecordingPicacomicPluginCall(method, data);
    }

    private static void waitForEvent(
        java.util.concurrent.atomic.AtomicReference<PicacomicImageEvent> event)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (event.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull("image event was not emitted", event.get());
    }

    private void waitForPendingImages() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (runtime.pendingImageCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, runtime.pendingImageCount());
    }
}
