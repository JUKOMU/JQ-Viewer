package io.github.jukomu.bridge;

import com.getcapacitor.JSObject;
import io.github.jukomu.bridge.handler.ApiPluginHandler;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.Assert.*;

public class ApiPluginContractInstrumentedTest {

    private JmcomicPlugin plugin;
    private FakeApiService apiService;

    @Before
    public void setUp() throws Exception {
        plugin = new JmcomicPlugin();
        apiService = new FakeApiService();
        injectApiHandler(plugin, apiService);
    }

    @Test
    public void requiredInputsRejectBeforeKeepAlive() {
        RecordingPluginCall search = call("search");
        plugin.search(search);
        assertRejected(search, "query is required");

        RecordingPluginCall categories = call("categories");
        plugin.categories(categories);
        assertRejected(categories, "query is required");

        RecordingPluginCall album = call("getAlbum");
        plugin.getAlbum(album);
        assertRejected(album, "id is required");

        RecordingPluginCall photo = call("getPhoto");
        plugin.getPhoto(photo);
        assertRejected(photo, "id is required");

        RecordingPluginCall comments = call("getComments");
        plugin.getComments(comments);
        assertRejected(comments, "albumId is required");

        RecordingPluginCall like = call("toggleAlbumLike");
        plugin.toggleAlbumLike(like);
        assertRejected(like, "id is required");

        RecordingPluginCall favorites = call("getFavorites");
        plugin.getFavorites(favorites);
        assertRejected(favorites, "query is required");

        RecordingPluginCall manage = call("manageFavoriteFolder");
        plugin.manageFavoriteFolder(manage);
        assertRejected(manage, "type is required (add/edit/move/del)");

        RecordingPluginCall editAll = call(
            "manageFavoriteFolder", "type", "edit", "folderId", "0");
        plugin.manageFavoriteFolder(editAll);
        assertRejected(editAll, "folderId is required for edit/del operations");

        RecordingPluginCall favorite = call("toggleAlbumFavorite");
        plugin.toggleAlbumFavorite(favorite);
        assertRejected(favorite, "id is required");
    }

    @Test
    public void validCallsForwardDefaultsAndResolveServicePayloads() throws Exception {
        JSObject query = new JSObject();
        RecordingPluginCall search = call("search", "query", query);
        plugin.search(search);
        assertInvocation("search", search, "", Category.ALL.getValue(),
            OrderBy.LATEST.getValue(), TimeOption.ALL.getValue(),
            SearchMainTag.SITE_SEARCH.getValue(), 1);

        RecordingPluginCall categories = call("categories", "query", query);
        plugin.categories(categories);
        assertInvocation("categories", categories, "", Category.ALL.getValue(),
            OrderBy.LATEST.getValue(), TimeOption.ALL.getValue(),
            SearchMainTag.SITE_SEARCH.getValue(), 1);

        RecordingPluginCall album = call("getAlbum", "id", "album-1");
        plugin.getAlbum(album);
        assertInvocation("getAlbum", album, "album-1");

        RecordingPluginCall photo = call("getPhoto", "id", "photo-1");
        plugin.getPhoto(photo);
        assertInvocation("getPhoto", photo, "photo-1");

        RecordingPluginCall comments = call("getComments", "albumId", "album-1");
        plugin.getComments(comments);
        assertInvocation("getComments", comments, "album-1", 1);

        RecordingPluginCall like = call("toggleAlbumLike", "id", "album-1");
        plugin.toggleAlbumLike(like);
        assertInvocation("toggleAlbumLike", like, "album-1");

        RecordingPluginCall favorites = call("getFavorites", "query", query);
        plugin.getFavorites(favorites);
        assertInvocation("getFavorites", favorites, 0, 1);

        RecordingPluginCall manage = call("manageFavoriteFolder", "type", "add");
        plugin.manageFavoriteFolder(manage);
        assertInvocation("manageFavoriteFolder", manage, "add", "0", "", "");

        RecordingPluginCall favorite = call(
            "toggleAlbumFavorite", "id", "album-1");
        plugin.toggleAlbumFavorite(favorite);
        assertInvocation("toggleAlbumFavorite", favorite, "album-1", "0");
    }

    @Test
    public void delayedCallbackCompletesTheKeptAliveCall() throws Exception {
        apiService.autoComplete = false;
        RecordingPluginCall call = call("getAlbum", "id", "album-1");

        plugin.getAlbum(call);

        assertTrue(call.isKeptAlive());
        assertEquals(0, call.completionCount);
        assertNotNull(apiService.pendingCallback);

        apiService.completeSuccess();

        assertEquals(1, call.completionCount);
        assertEquals("getAlbum", call.resolvedData.getString("method"));
    }

    @Test
    public void asynchronousErrorsPreserveMessageAndThrowable() {
        IllegalStateException error = new IllegalStateException("remote failed");
        apiService.failWith("remote failed", error);
        RecordingPluginCall call = call("getAlbum", "id", "album-1");

        plugin.getAlbum(call);

        assertEquals("remote failed", call.rejectionMessage);
        assertEquals(error, call.rejectionException);
        assertEquals(1, call.completionCount);
        assertTrue(call.isKeptAlive());
    }

    private void assertInvocation(String methodName, RecordingPluginCall call,
                                  Object... expectedArguments) throws Exception {
        assertEquals(methodName, apiService.method);
        assertEquals(Arrays.asList(expectedArguments), apiService.arguments);
        assertEquals(methodName, call.resolvedData.getString("method"));
        assertEquals(1, call.completionCount);
        assertTrue(call.isKeptAlive());
    }

    private static void assertRejected(RecordingPluginCall call, String message) {
        assertEquals(message, call.rejectionMessage);
        assertEquals(1, call.completionCount);
        assertFalse(call.isKeptAlive());
    }

    private static RecordingPluginCall call(String methodName, Object... entries) {
        JSObject data = new JSObject();
        for (int index = 0; index < entries.length; index += 2) {
            data.put((String) entries[index], entries[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static void injectApiHandler(JmcomicPlugin plugin,
                                         FakeApiService apiService) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("apiHandler");
        field.setAccessible(true);
        field.set(plugin, new ApiPluginHandler(apiService));
    }
}
