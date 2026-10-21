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

    @Test
    void timePagesAndReversePagesUseNumericCompletedTimestamps() throws Exception {
        for (String direction : List.of("asc", "desc")) {
            Path root = Files.createTempDirectory("jq-viewer-time-pages-");
            try (Database database = new Database(root.resolve("test.db"))) {
                database.open();
                DownloadStore store = new DownloadStore(database);
                for (int i = 1; i <= 5; i++) createCompleted(store, "album-" + i, "chapter-1", i);
                List<List<String>> pages = new java.util.ArrayList<>();
                List<DownloadStore.TaskPage> responses = new java.util.ArrayList<>();
                String cursor = null;
                do {
                    DownloadStore.TaskPage page = store.page("completed", "time", direction, cursor, 2);
                    pages.add(page.albums().stream().map(DownloadStore.CompletedAlbum::albumId).toList());
                    responses.add(page);
                    cursor = page.nextCursor();
                    org.junit.jupiter.api.Assertions.assertTrue(pages.size() <= 3, "cursor must advance");
                } while (cursor != null);
                assertEquals("asc".equals(direction)
                    ? List.of("album-1", "album-2", "album-3", "album-4", "album-5")
                    : List.of("album-5", "album-4", "album-3", "album-2", "album-1"),
                    pages.stream().flatMap(List::stream).toList());
                for (int i = 1; i < responses.size(); i++) {
                    DownloadStore.TaskPage previous = store.page("completed", "time", direction,
                        responses.get(i).previousCursor(), 2);
                    assertEquals(pages.get(i - 1), previous.albums().stream()
                        .map(DownloadStore.CompletedAlbum::albumId).toList());
                }
            }
        }
    }

    @Test
    void allSortModesKeepTiedTasksAndAlbumsInBothDirections() throws Exception {
        for (String status : List.of("active", "failed", "completed")) {
            for (String sort : List.of("id", "title", "author", "time")) {
                for (String direction : List.of("asc", "desc")) {
                    Path root = Files.createTempDirectory("jq-viewer-tied-pages-");
                    try (Database database = new Database(root.resolve("test.db"))) {
                        database.open();
                        DownloadStore store = new DownloadStore(database);
                        for (int i = 1; i <= 3; i++) {
                            if ("completed".equals(status)) createCompleted(store, "album-" + i, "chapter-1", 1);
                            else createFailed(store, "album-1", "chapter-" + i, "Alice");
                        }
                        database.connection().createStatement().executeUpdate(
                            "UPDATE download_tasks SET created_at=1,album_title='Same',completed_at=1"
                                + ("active".equals(status) ? ",status='queued'" : ""));
                        List<String> ids = new java.util.ArrayList<>();
                        List<DownloadStore.TaskPage> pages = new java.util.ArrayList<>();
                        String cursor = null;
                        do {
                            DownloadStore.TaskPage page = store.page(status, sort, direction, cursor, 1);
                            pages.add(page);
                            ids.addAll(pageIds(page));
                            cursor = page.nextCursor();
                            org.junit.jupiter.api.Assertions.assertTrue(pages.size() <= 3, "cursor must advance");
                        } while (cursor != null);
                        List<String> expected = "completed".equals(status)
                            ? List.of("album-1", "album-2", "album-3")
                            : List.of("album-1_chapter-1", "album-1_chapter-2", "album-1_chapter-3");
                        if ("desc".equals(direction)) {
                            expected = new java.util.ArrayList<>(expected);
                            java.util.Collections.reverse(expected);
                        }
                        assertEquals(expected, ids, status + "/" + sort + "/" + direction);
                        for (int i = 1; i < pages.size(); i++) {
                            assertEquals(pageIds(pages.get(i - 1)), pageIds(store.page(status, sort,
                                direction, pages.get(i).previousCursor(), 1)));
                        }
                    }
                }
            }
        }
    }

    @Test
    void emptyAndWhitespaceAuthorsShareStableNullRank() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-empty-author-");
        try (Database database = new Database(root.resolve("test.db"))) {
            database.open();
            DownloadStore store = new DownloadStore(database);
            createFailed(store, "album-1", "chapter-1", " ");
            createFailed(store, "album-2", "chapter-1", "");
            createFailed(store, "album-3", "chapter-1", "Alice");
            for (String direction : List.of("asc", "desc")) {
                List<String> ids = new java.util.ArrayList<>();
                String cursor = null;
                do {
                    var page = store.page("failed", "author", direction, cursor, 1);
                    ids.addAll(page.tasks().stream().map(task -> task.albumId()).toList());
                    cursor = page.nextCursor();
                    org.junit.jupiter.api.Assertions.assertTrue(ids.size() <= 3);
                } while (cursor != null);
                assertEquals("asc".equals(direction) ? List.of("album-3", "album-1", "album-2")
                    : List.of("album-3", "album-2", "album-1"), ids);
            }
        }
    }

    @Test
    void statusFilteredDeletionDoesNotDeletePagesOfChangedTasks() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-status-delete-");
        try (Database database = new Database(root.resolve("test.db"))) {
            database.open();
            DownloadStore store = new DownloadStore(database);
            createFailed(store, "album-1", "chapter-1", "Alice");
            String id = "album-1_chapter-1";
            store.saveManifest(id, 1, "Alice", "[]", "[]", 1, true,
                List.of(new StoredDownloadPage(id, 1, "chapter-1", "1.jpg", "1.jpg", "", "", "", false)));
            store.updateStatus(id, "downloading", null);
            assertEquals(0, store.deleteTasksByStatus(List.of(id), "failed"));
            assertEquals(1, store.pages(id).size());
            store.updateStatus(id, "failed", "error");
            assertEquals(1, store.deleteTasksByStatus(List.of(id), "failed"));
            assertEquals(0, store.pages(id).size());
        }
    }

    private static List<String> pageIds(DownloadStore.TaskPage page) {
        return page.albums().isEmpty() ? page.tasks().stream().map(task -> task.taskId()).toList()
            : page.albums().stream().map(DownloadStore.CompletedAlbum::albumId).toList();
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
