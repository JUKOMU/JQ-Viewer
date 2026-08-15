package io.github.jukomu.bridge;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import io.github.jukomu.bridge.handler.FavoritePluginHandler;
import io.github.jukomu.feature.favorite.data.FavoriteStore;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class FavoritePluginContractInstrumentedTest {

    private IsolatedDatabaseContext context;
    private JmcomicPlugin plugin;

    @Before
    public void setUp() throws Exception {
        resetStoreSingleton();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(targetContext.getCacheDir(),
            "favorite-plugin-contract-" + System.nanoTime());
        context = new IsolatedDatabaseContext(targetContext, directory);
        plugin = new ContextPlugin(context);
        injectFavoriteHandler(plugin, FavoriteStore.getInstance(context));
    }

    @After
    public void tearDown() throws Exception {
        closeAndResetStoreSingleton();
        context.deleteTestDatabases();
    }

    @Test
    public void missingInputsAndPaginationDefaultsPreservePayloadShape() throws Exception {
        RecordingPluginCall create = call("createOfflineFolder");
        plugin.createOfflineFolder(create);
        String folderId = create.resolvedData.getString("folderId");
        assertFalse(folderId.isEmpty());

        JSObject item = new JSObject();
        item.put("id", "album-1");
        item.put("title", "first");
        RecordingPluginCall add = call("addOfflineFavorite",
            "folderId", folderId, "item", item);
        plugin.addOfflineFavorite(add);
        assertTrue(add.resolvedData.getBoolean("success"));

        RecordingPluginCall page = call("getOfflineFavorites", "folderId", folderId);
        plugin.getOfflineFavorites(page);
        assertEquals(1, page.resolvedData.getInt("totalItems"));
        assertEquals(1, page.resolvedData.getInt("totalPages"));
        assertEquals(1, page.resolvedData.getInt("currentPage"));
        assertEquals(1, page.resolvedData.getJSONArray("content").length());

        RecordingPluginCall emptyPage = call("getOfflineFavorites");
        plugin.getOfflineFavorites(emptyPage);
        assertEquals(0, emptyPage.resolvedData.getInt("totalItems"));
        assertEquals(1, emptyPage.resolvedData.getInt("totalPages"));
        assertEquals(1, emptyPage.resolvedData.getInt("currentPage"));

        assertNotKeptAlive(create, add, page, emptyPage);
    }

    @Test
    public void booleanCountBatchAndNullableBackupPayloadsRemainStable() throws Exception {
        String folderId = createFolder("source");
        RecordingPluginCall invalidRename = call("renameOfflineFolder",
            "folderId", folderId);
        plugin.renameOfflineFolder(invalidRename);
        assertFalse(invalidRename.resolvedData.getBoolean("success"));

        JSONArray items = new JSONArray();
        items.put(item("album-1", "first"));
        items.put(item("album-2", "second"));
        RecordingPluginCall batch = call("addOfflineFavoritesBatch",
            "folderId", folderId, "items", items);
        plugin.addOfflineFavoritesBatch(batch);
        assertEquals(2, batch.resolvedData.getInt("count"));

        RecordingPluginCall count = call("getOfflineFavoritesTotalCount");
        plugin.getOfflineFavoritesTotalCount(count);
        assertEquals(2, count.resolvedData.getInt("count"));

        RecordingPluginCall missingBackup = call("loadOfflineBackup", "key", "missing");
        plugin.loadOfflineBackup(missingBackup);
        assertTrue(missingBackup.resolvedData.isNull("items"));

        RecordingPluginCall save = call("saveOfflineBackup",
            "key", "backup-1", "items", items);
        plugin.saveOfflineBackup(save);
        assertTrue(save.resolvedData.getBoolean("success"));
        RecordingPluginCall keys = call("listOfflineBackupKeys");
        plugin.listOfflineBackupKeys(keys);
        assertEquals("backup-1", keys.resolvedData.getJSONArray("keys").getString(0));
        RecordingPluginCall load = call("loadOfflineBackup", "key", "backup-1");
        plugin.loadOfflineBackup(load);
        assertEquals(2, load.resolvedData.getJSONArray("items").length());
        RecordingPluginCall delete = call("deleteOfflineBackup", "key", "backup-1");
        plugin.deleteOfflineBackup(delete);
        assertTrue(delete.resolvedData.getBoolean("success"));

        assertNotKeptAlive(invalidRename, batch, count, missingBackup,
            save, keys, load, delete);
    }

    @Test
    public void folderCopyMoveMergeAndDeleteReturnCurrentResultTypes() throws Exception {
        String sourceId = createFolder("source");
        String targetId = createFolder("target");
        plugin.addOfflineFavorite(call("addOfflineFavorite",
            "folderId", sourceId, "item", item("album-1", "first")));

        RecordingPluginCall copy = call("copyOfflineFolder",
            "sourceId", sourceId, "name", "copy");
        plugin.copyOfflineFolder(copy);
        assertFalse(copy.resolvedData.getString("folderId").isEmpty());

        RecordingPluginCall move = call("moveAllOfflineFavorites",
            "sourceId", sourceId, "targetId", targetId);
        plugin.moveAllOfflineFavorites(move);
        assertTrue(move.resolvedData.getBoolean("success"));

        RecordingPluginCall merge = call("mergeOfflineAllToFolder", "targetId", targetId);
        plugin.mergeOfflineAllToFolder(merge);
        assertTrue(merge.resolvedData.getBoolean("success"));

        RecordingPluginCall mergedItems = call("getAllOfflineFavoritesMerged");
        plugin.getAllOfflineFavoritesMerged(mergedItems);
        assertEquals(1, mergedItems.resolvedData.getJSONArray("items").length());

        RecordingPluginCall remove = call("removeOfflineFavorite",
            "folderId", targetId, "albumId", "album-1");
        plugin.removeOfflineFavorite(remove);
        assertTrue(remove.resolvedData.getBoolean("success"));
        RecordingPluginCall delete = call("deleteOfflineFolder", "folderId", sourceId);
        plugin.deleteOfflineFolder(delete);
        assertTrue(delete.resolvedData.getBoolean("success"));

        assertNotKeptAlive(copy, move, merge, mergedItems, remove, delete);
    }

    @Test
    public void favoriteWrapperRejectsUnexpectedInputFailureWithThrowable() {
        RecordingPluginCall call = new RecordingPluginCall(
            "createOfflineFolder", new JSObject()) {
            @Override
            public String getString(String name, String defaultValue) {
                throw new IllegalStateException("input unavailable");
            }
        };

        plugin.createOfflineFolder(call);

        assertEquals("input unavailable", call.rejectionMessage);
        assertTrue(call.rejectionException instanceof IllegalStateException);
        assertEquals(1, call.completionCount);
        assertFalse(call.isKeptAlive());
    }

    @Test
    public void allFavoriteMethodsRemainNonKeepAlive() throws Exception {
        String folderId = createFolder("all-methods");
        JSONArray items = new JSONArray().put(item("album-1", "first"));
        RecordingPluginCall[] calls = new RecordingPluginCall[]{
            call("getOfflineFolders"),
            call("createOfflineFolder", "name", "created"),
            call("renameOfflineFolder", "folderId", folderId, "name", "renamed"),
            call("deleteOfflineFolder", "folderId", "missing"),
            call("addOfflineFavorite", "folderId", folderId,
                "item", item("album-2", "second")),
            call("removeOfflineFavorite", "folderId", folderId, "albumId", "missing"),
            call("getOfflineFavorites", "folderId", folderId),
            call("getAllOfflineFavorites", "folderId", folderId),
            call("getOfflineFavoritesTotalCount"),
            call("getAllOfflineFavoritesMerged"),
            call("moveAllOfflineFavorites", "sourceId", "missing", "targetId", folderId),
            call("copyOfflineFolder", "sourceId", folderId, "name", "copy"),
            call("addOfflineFavoritesBatch", "folderId", folderId, "items", items),
            call("mergeOfflineAllToFolder", "targetId", folderId),
            call("saveOfflineBackup", "key", "all", "items", items),
            call("loadOfflineBackup", "key", "all"),
            call("deleteOfflineBackup", "key", "all"),
            call("listOfflineBackupKeys")
        };

        plugin.getOfflineFolders(calls[0]);
        plugin.createOfflineFolder(calls[1]);
        plugin.renameOfflineFolder(calls[2]);
        plugin.deleteOfflineFolder(calls[3]);
        plugin.addOfflineFavorite(calls[4]);
        plugin.removeOfflineFavorite(calls[5]);
        plugin.getOfflineFavorites(calls[6]);
        plugin.getAllOfflineFavorites(calls[7]);
        plugin.getOfflineFavoritesTotalCount(calls[8]);
        plugin.getAllOfflineFavoritesMerged(calls[9]);
        plugin.moveAllOfflineFavorites(calls[10]);
        plugin.copyOfflineFolder(calls[11]);
        plugin.addOfflineFavoritesBatch(calls[12]);
        plugin.mergeOfflineAllToFolder(calls[13]);
        plugin.saveOfflineBackup(calls[14]);
        plugin.loadOfflineBackup(calls[15]);
        plugin.deleteOfflineBackup(calls[16]);
        plugin.listOfflineBackupKeys(calls[17]);

        assertNotKeptAlive(calls);
    }

    private String createFolder(String name) throws Exception {
        RecordingPluginCall create = call("createOfflineFolder", "name", name);
        plugin.createOfflineFolder(create);
        assertNotNull(create.resolvedData);
        return create.resolvedData.getString("folderId");
    }

    private static JSObject item(String id, String title) {
        JSObject item = new JSObject();
        item.put("id", id);
        item.put("title", title);
        item.put("authors", new JSONArray());
        item.put("tags", new JSONArray());
        return item;
    }

    private static RecordingPluginCall call(String methodName, Object... entries) {
        JSObject data = new JSObject();
        for (int index = 0; index < entries.length; index += 2) {
            data.put((String) entries[index], entries[index + 1]);
        }
        return new RecordingPluginCall(methodName, data);
    }

    private static void assertNotKeptAlive(RecordingPluginCall... calls) {
        for (RecordingPluginCall call : calls) {
            assertFalse(call.getMethodName(), call.isKeptAlive());
            assertEquals(call.getMethodName(), 1, call.completionCount);
        }
    }

    private static void injectFavoriteHandler(JmcomicPlugin plugin,
                                              FavoriteStore favoriteStore) throws Exception {
        Field field = JmcomicPlugin.class.getDeclaredField("favoriteHandler");
        field.setAccessible(true);
        field.set(plugin, new FavoritePluginHandler(favoriteStore));
    }

    private static void resetStoreSingleton() throws Exception {
        Field field = FavoriteStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static void closeAndResetStoreSingleton() throws Exception {
        Field field = FavoriteStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        FavoriteStore store = (FavoriteStore) field.get(null);
        if (store != null) {
            store.close();
        }
        field.set(null, null);
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
