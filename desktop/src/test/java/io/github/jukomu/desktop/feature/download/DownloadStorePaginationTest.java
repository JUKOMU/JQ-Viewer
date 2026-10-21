package io.github.jukomu.desktop.feature.download;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadPage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 锁定下载列表分页的数据库边界，避免前端测试掩盖本子跨页问题。 */
class DownloadStorePaginationTest {
    @Test
    void completedPagePaginatesAlbumsAndKeepsAllChaptersTogether() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-pagination-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        try (Database database = new Database(paths)) {
            database.open();
            DownloadStore store = new DownloadStore(database);
            createCompleted(store, "album-1", "chapter-1", 1);
            createCompleted(store, "album-1", "chapter-2", 2);
            createCompleted(store, "album-2", "chapter-1", 3);

            DownloadStore.TaskPage first = store.page("completed", "id", "asc", null, 1);
            assertEquals(1, first.albums().size());
            assertEquals("album-1", first.albums().get(0).albumId());
            assertEquals(2, first.albums().get(0).chapters().size());
            assertEquals(2, first.totalCount());
            assertNotNull(first.nextCursor());

            DownloadStore.TaskPage second = store.page("completed", "id", "asc", first.nextCursor(), 1);
            assertEquals(List.of("album-2"), second.albums().stream()
                    .map(DownloadStore.CompletedAlbum::albumId).toList());
            assertEquals("chapter-1", first.albums().get(0).chapters().get(0).chapterId());
            assertEquals("chapter-2", first.albums().get(0).chapters().get(1).chapterId());
        }
    }

    @Test
    void failedAuthorPageContinuesAcrossNullAuthors() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-author-pagination-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        try (Database database = new Database(paths)) {
            database.open();
            DownloadStore store = new DownloadStore(database);
            createFailed(store, "album-1", "chapter-1", "Alice");
            createFailed(store, "album-2", "chapter-1", "");
            createFailed(store, "album-3", "chapter-1", "");
            DownloadStore.TaskPage first = store.page("failed", "author", "asc", null, 1);
            DownloadStore.TaskPage second = store.page("failed", "author", "asc",
                first.nextCursor(), 1);
            DownloadStore.TaskPage third = store.page("failed", "author", "asc",
                second.nextCursor(), 1);
            assertEquals("album-1", first.tasks().get(0).albumId());
            assertEquals("album-2", second.tasks().get(0).albumId());
            assertEquals("album-3", third.tasks().get(0).albumId());
        }
    }

    private static void createCompleted(DownloadStore store, String albumId,
                                        String chapterId, long createdAt) {
        String taskId = albumId + "_" + chapterId;
        store.createOrResetTask(taskId, albumId, chapterId, "Album " + albumId,
                "Chapter " + chapterId, "cover", "downloads/" + taskId, createdAt);
        store.saveManifest(taskId, 1, "Alice", "[\"Alice\"]", "[]", 1,
                true, List.<StoredDownloadPage>of());
        store.complete(taskId, 1, 1, 10, createdAt);
    }

    private static void createFailed(DownloadStore store, String albumId,
                                     String chapterId, String author) {
        String taskId = albumId + "_" + chapterId;
        store.createOrResetTask(taskId, albumId, chapterId, "Album " + albumId,
            "Chapter " + chapterId, "cover", "downloads/" + taskId, System.currentTimeMillis());
        store.saveManifest(taskId, 1, author, "[]", "[]", 1, true,
            List.<StoredDownloadPage>of());
        store.fail(taskId, 0, 0, 0, "failed");
    }
}
