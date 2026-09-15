package io.github.jukomu.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.favorite.OfflineFavoriteService;
import io.github.jukomu.desktop.feature.favorite.data.OfflineFavoriteStore;
import io.github.jukomu.desktop.feature.favorite.model.OfflineFavoriteItem;
import io.github.jukomu.desktop.feature.favorite.model.OfflineFavoritePageResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineFavoriteServiceTest {
    @Test
    void persistsFoldersItemsPaginationAndBackupsAcrossRestart() throws Exception {
        Path path = Files.createTempDirectory("jq-viewer-favorites-").resolve("desktop.sqlite3");
        String folderId;
        try (Database database = new Database(path)) {
            database.open();
            OfflineFavoriteService favorites = service(database);
            folderId = favorites.createFolder("Reading").folderId();

            assertTrue(favorites.addItem(folderId, item("album-1", "first")).success());
            assertFalse(favorites.addItem(folderId, item("album-1", "duplicate")).success());
            assertEquals(2, favorites.addItemsBatch(folderId, List.of(
                    item("album-2", "second"),
                    item("album-3", "third")
            )).count());

            OfflineFavoritePageResponse filtered = favorites.page(folderId, "second", 1, 20);
            assertEquals(1, filtered.totalItems());
            assertEquals("album-2", filtered.content().getFirst().id());

            OfflineFavoritePageResponse lastPage = favorites.page(folderId, null, 99, 2);
            assertEquals(3, lastPage.totalItems());
            assertEquals(2, lastPage.totalPages());
            assertEquals(2, lastPage.currentPage());
            assertEquals("album-3", lastPage.content().getFirst().id());
            assertEquals(3, favorites.totalCount().count());

            favorites.saveBackup("backup-1", favorites.allItems(folderId).items());
        }

        try (Database reopened = new Database(path)) {
            reopened.open();
            OfflineFavoriteService favorites = service(reopened);
            assertEquals(1, favorites.folders().folders().size());
            assertEquals(3, favorites.folders().folders().getFirst().count());
            assertEquals(3, favorites.allItems(folderId).items().size());
            assertEquals(List.of("backup-1"), favorites.backupKeys().keys());
            assertNull(favorites.loadBackup("missing").items());
            assertEquals(3, favorites.loadBackup("backup-1").items().size());
            assertTrue(favorites.deleteBackup("backup-1").success());
            assertFalse(favorites.deleteBackup("backup-1").success());
        }
    }

    @Test
    void copiesMovesMergesAndDeletesWithinDomainTransactions() throws Exception {
        Path path = Files.createTempDirectory("jq-viewer-favorite-operations-")
                .resolve("desktop.sqlite3");
        try (Database database = new Database(path)) {
            database.open();
            OfflineFavoriteService favorites = service(database);
            String sourceId = favorites.createFolder("Source").folderId();
            String targetId = favorites.createFolder("Target").folderId();
            favorites.addItemsBatch(sourceId, List.of(
                    item("album-1", "first"), item("album-2", "second")));
            favorites.addItemsBatch(targetId, List.of(
                    item("album-2", "existing"), item("album-3", "third")));

            String copyId = favorites.copyFolder(sourceId, "Copy").folderId();
            assertFalse(copyId.isEmpty());
            assertEquals(2, favorites.allItems(copyId).items().size());

            assertTrue(favorites.moveAllItems(sourceId, targetId).success());
            assertEquals(0, favorites.allItems(sourceId).items().size());
            assertEquals(3, favorites.allItems(targetId).items().size());
            assertEquals("existing", favorites.allItems(targetId).items().getFirst().title());
            assertFalse(favorites.moveAllItems(targetId, targetId).success());

            assertTrue(favorites.mergeAllToFolder(targetId).success());
            assertEquals(3, favorites.allItems(targetId).items().size());
            assertEquals(0, favorites.allItems(copyId).items().size());
            assertEquals(3, favorites.allItemsMerged().items().size());

            assertTrue(favorites.deleteFolder(targetId).success());
            assertFalse(favorites.deleteFolder(targetId).success());
            assertEquals(0, favorites.allItems(targetId).items().size());
        }
    }

    @Test
    void validatesWholeBatchBeforeWritingAndKeepsMissingFolderOperationsBounded() throws Exception {
        Path path = Files.createTempDirectory("jq-viewer-favorite-boundaries-")
                .resolve("desktop.sqlite3");
        try (Database database = new Database(path)) {
            database.open();
            OfflineFavoriteService favorites = service(database);
            String folderId = favorites.createFolder("").folderId();
            List<OfflineFavoriteItem> invalid = Arrays.asList(item("album-1", "first"), null);

            ApiException batchError = assertThrows(
                    ApiException.class,
                    () -> favorites.addItemsBatch(folderId, invalid));
            assertEquals("item不能为空", batchError.getMessage());
            assertEquals(0, favorites.allItems(folderId).items().size());
            assertThrows(ApiException.class, () -> favorites.page(folderId, null, 1, 0));

            assertFalse(favorites.renameFolder(folderId, "  ").success());
            assertFalse(favorites.addItem("missing", item("album-1", "first")).success());
            assertEquals(0, favorites.addItemsBatch(
                    "missing", List.of(item("album-1", "first"))).count());
            assertEquals("", favorites.copyFolder("missing", "Copy").folderId());
            assertFalse(favorites.mergeAllToFolder("missing").success());
        }
    }

    @Test
    void favoriteWritesDoNotJoinAnotherServicesTransaction() throws Exception {
        Path path = Files.createTempDirectory("jq-viewer-favorite-isolation-")
                .resolve("desktop.sqlite3");
        try (Database database = new Database(path)) {
            Connection shared = database.open();
            OfflineFavoriteService favorites = service(database);
            boolean autoCommit = shared.getAutoCommit();
            shared.setAutoCommit(false);
            try {
                String folderId = favorites.createFolder("Independent").folderId();

                shared.rollback();

                assertEquals(folderId, favorites.folders().folders().getFirst().folderId());
            } finally {
                shared.setAutoCommit(autoCommit);
            }
        }
    }

    private static OfflineFavoriteService service(Database database) {
        return new OfflineFavoriteService(new OfflineFavoriteStore(database, new ObjectMapper()));
    }

    private static OfflineFavoriteItem item(String id, String title) {
        return new OfflineFavoriteItem(
                id, title, "https://example.invalid/" + id,
                List.of("Alice"), List.of("tag"));
    }
}
