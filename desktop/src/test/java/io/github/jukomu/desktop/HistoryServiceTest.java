package io.github.jukomu.desktop;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.history.HistoryService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistoryServiceTest {
    @Test
    void updatesOnlyTheNewestEntryForTheSameAlbumAndPaginatesByTimestamp() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-history-").resolve("history.sqlite3");
        try (Database database = new Database(databasePath)) {
            database.open();
            HistoryService history = new HistoryService(database);

            history.record(item("album-a", "chapter-a1"));
            history.record(item("album-a", "chapter-a2"));
            history.record(item("album-b", "chapter-b1"));

            ObjectNode page = history.page(1, 0, null, null);
            assertEquals(2, page.path("totalCount").asInt());
            assertEquals("album-b", page.path("items").get(0).path("albumId").asText());
            assertEquals("chapter-b1", page.path("items").get(0).path("chapterId").asText());

            ObjectNode secondPage = history.page(1, 1, null, null);
            assertEquals("album-a", secondPage.path("items").get(0).path("albumId").asText());
            assertEquals("chapter-a2", secondPage.path("items").get(0).path("chapterId").asText());

            long boundedCount = history.page(0, 0, 0L, System.currentTimeMillis() + 10_000)
                    .path("totalCount").asLong();
            assertEquals(2, boundedCount);

            long newestId = page.path("items").get(0).path("id").asLong();
            history.delete(newestId);
            assertEquals(1, history.page(0, 0, null, null).path("totalCount").asInt());
            history.clear();
            assertEquals(0, history.page(0, 0, null, null).path("totalCount").asInt());
        }
    }

    @Test
    void validatesOverviewGroupsAndReturnsCounts() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-history-overview-").resolve("history.sqlite3");
        try (Database database = new Database(databasePath)) {
            database.open();
            HistoryService history = new HistoryService(database);
            history.record(item("album-a", "chapter-a1"));

            ArrayNode ranges = JsonNodeFactory.instance.arrayNode();
            for (String key : new String[]{
                    "today", "yesterday", "thisWeek", "thisMonth",
                    "lastThreeMonths", "lastSixMonths", "thisYear", "earlier"
            }) {
                ranges.addObject().put("key", key).putNull("startInclusive").putNull("endExclusive");
            }

            ObjectNode overview = history.overview(ranges);
            assertEquals(1, overview.path("totalCount").asInt());
            assertEquals(1, overview.path("groupCounts").path("today").asInt());

            ranges.remove(7);
            ApiException exception = assertThrows(ApiException.class, () -> history.overview(ranges));
            assertEquals("internal", exception.code());
            assertEquals(400, exception.status());
        }
    }

    private static ObjectNode item(String albumId, String chapterId) {
        return JsonNodeFactory.instance.objectNode()
                .put("albumId", albumId)
                .put("albumTitle", "Album " + albumId)
                .put("coverUrl", "https://example.invalid/" + albumId)
                .put("authors", "Author")
                .put("chapterId", chapterId)
                .put("chapterTitle", "Chapter " + chapterId);
    }
}
