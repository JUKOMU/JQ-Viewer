package io.github.jukomu.jqviewer.desktop.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopOfflineFavoriteStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void supportsFolderCrudAndPagedItems() throws Exception {
        DesktopOfflineFavoriteStore store = new DesktopOfflineFavoriteStore(tempDir);
        String folderId = createFolder(store, "本地收藏");

        assertEquals("本地收藏", folders(store).get(0).getAsJsonObject().get("name").getAsString());

        store.addFavorite(addBody(folderId, item("100", "Alpha")));
        store.addFavorite(addBody(folderId, item("100", "Alpha duplicate")));
        store.addFavorite(addBody(folderId, item("200", "Beta")));
        store.addFavorite(addBody(folderId, item("300", "Gamma")));

        JsonObject page = store.getFavorites(query(folderId, "a", 2, 2));
        assertEquals(3, page.get("totalItems").getAsInt());
        assertEquals(2, page.get("totalPages").getAsInt());
        assertEquals(2, page.get("currentPage").getAsInt());
        assertEquals(1, page.getAsJsonArray("content").size());

        JsonObject rename = new JsonObject();
        rename.addProperty("folderId", folderId);
        rename.addProperty("name", "重命名");
        assertTrue(store.renameFolder(rename).get("success").getAsBoolean());
        assertEquals("重命名", folders(store).get(0).getAsJsonObject().get("name").getAsString());

        JsonObject remove = new JsonObject();
        remove.addProperty("folderId", folderId);
        remove.addProperty("albumId", "200");
        assertTrue(store.removeFavorite(remove).get("success").getAsBoolean());
        assertEquals(2, store.getAllFavorites(allBody(folderId)).getAsJsonArray("items").size());

        JsonObject delete = new JsonObject();
        delete.addProperty("folderId", folderId);
        assertTrue(store.deleteFolder(delete).get("success").getAsBoolean());
        assertEquals(0, folders(store).size());
    }

    @Test
    void supportsMergedMoveCopyBatchAndBackups() throws Exception {
        DesktopOfflineFavoriteStore store = new DesktopOfflineFavoriteStore(tempDir);
        String sourceId = createFolder(store, "source");
        String targetId = createFolder(store, "target");

        store.addFavorite(addBody(sourceId, item("100", "Alpha")));
        store.addFavorite(addBody(sourceId, item("200", "Beta")));
        store.addFavorite(addBody(targetId, item("100", "Alpha in target")));

        assertEquals(2, store.getAllFavoritesMerged().getAsJsonArray("items").size());
        assertEquals(2, store.getTotalCount().get("count").getAsInt());

        JsonObject move = new JsonObject();
        move.addProperty("sourceId", sourceId);
        move.addProperty("targetId", targetId);
        assertTrue(store.moveAll(move).get("success").getAsBoolean());
        assertEquals(0, store.getAllFavorites(allBody(sourceId)).getAsJsonArray("items").size());
        assertEquals(2, store.getAllFavorites(allBody(targetId)).getAsJsonArray("items").size());

        JsonObject copy = new JsonObject();
        copy.addProperty("sourceId", targetId);
        copy.addProperty("name", "copy");
        String copyId = store.copyFolder(copy).get("folderId").getAsString();
        assertEquals(2, store.getAllFavorites(allBody(copyId)).getAsJsonArray("items").size());

        JsonObject missingCopy = new JsonObject();
        missingCopy.addProperty("sourceId", "missing");
        missingCopy.addProperty("name", "empty");
        assertEquals("", store.copyFolder(missingCopy).get("folderId").getAsString());
        assertEquals(3, folders(store).size());

        JsonObject batch = new JsonObject();
        batch.addProperty("folderId", copyId);
        JsonArray batchItems = new JsonArray();
        batchItems.add(item("200", "Beta duplicate"));
        batchItems.add(item("300", "Gamma"));
        batch.add("items", batchItems);
        assertEquals(1, store.addFavoritesBatch(batch).get("count").getAsInt());

        JsonObject merge = new JsonObject();
        merge.addProperty("targetId", targetId);
        assertTrue(store.mergeAllToFolder(merge).get("success").getAsBoolean());
        assertEquals(3, store.getAllFavorites(allBody(targetId)).getAsJsonArray("items").size());
        assertEquals(0, store.getAllFavorites(allBody(copyId)).getAsJsonArray("items").size());

        JsonObject backup = new JsonObject();
        backup.addProperty("key", "move_test");
        backup.add("items", store.getAllFavorites(allBody(targetId)).getAsJsonArray("items"));
        assertTrue(store.saveBackup(backup).get("success").getAsBoolean());
        assertEquals(3, store.loadBackup(keyBody("move_test")).getAsJsonArray("items").size());
        assertEquals("move_test", store.listBackupKeys().getAsJsonArray("keys").get(0).getAsString());
        assertTrue(store.deleteBackup(keyBody("move_test")).get("success").getAsBoolean());
        assertFalse(store.deleteBackup(keyBody("move_test")).get("success").getAsBoolean());
    }

    private static String createFolder(DesktopOfflineFavoriteStore store, String name) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        return store.createFolder(body).get("folderId").getAsString();
    }

    private static JsonArray folders(DesktopOfflineFavoriteStore store) {
        return store.getFolders().getAsJsonArray("folders");
    }

    private static JsonObject addBody(String folderId, JsonObject item) {
        JsonObject body = new JsonObject();
        body.addProperty("folderId", folderId);
        body.add("item", item);
        return body;
    }

    private static JsonObject allBody(String folderId) {
        JsonObject body = new JsonObject();
        body.addProperty("folderId", folderId);
        return body;
    }

    private static JsonObject query(String folderId, String keyword, int page, int pageSize) {
        JsonObject body = allBody(folderId);
        body.addProperty("keyword", keyword);
        body.addProperty("page", page);
        body.addProperty("pageSize", pageSize);
        return body;
    }

    private static JsonObject keyBody(String key) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        return body;
    }

    private static JsonObject item(String id, String title) {
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("title", title);
        item.addProperty("coverUrl", "https://example.test/" + id + ".jpg");
        JsonArray authors = new JsonArray();
        authors.add("author");
        item.add("authors", authors);
        JsonArray tags = new JsonArray();
        tags.add("tag");
        item.add("tags", tags);
        return item;
    }
}
