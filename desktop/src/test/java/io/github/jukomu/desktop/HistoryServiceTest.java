package io.github.jukomu.desktop;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.history.HistoryService;
import io.github.jukomu.desktop.feature.history.model.HistoryOverviewRequest;
import io.github.jukomu.desktop.feature.history.model.HistoryOverviewResponse;
import io.github.jukomu.desktop.feature.history.model.HistoryPageResponse;
import io.github.jukomu.desktop.feature.history.model.HistoryRecordRequest;
import io.github.jukomu.desktop.feature.history.model.ParseHistoryPageResponse;
import io.github.jukomu.desktop.feature.history.model.ParseHistoryRecordRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            HistoryPageResponse page = history.page(1, 0, null, null);
            assertEquals(2, page.totalCount());
            assertEquals("album-b", page.items().get(0).albumId());
            assertEquals("chapter-b1", page.items().get(0).chapterId());
            assertEquals(42L, page.items().get(0).fileId());

            HistoryPageResponse secondPage = history.page(1, 1, null, null);
            assertEquals("album-a", secondPage.items().get(0).albumId());
            assertEquals("chapter-a2", secondPage.items().get(0).chapterId());

            long boundedCount = history.page(0, 0, 0L, System.currentTimeMillis() + 10_000)
                    .totalCount();
            assertEquals(2, boundedCount);

            long newestId = page.items().get(0).id();
            history.delete(newestId);
            assertEquals(1, history.page(0, 0, null, null).totalCount());
            history.clear();
            assertEquals(0, history.page(0, 0, null, null).totalCount());
        }
    }

    @Test
    void validatesOverviewGroupsAndReturnsCounts() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-history-overview-").resolve("history.sqlite3");
        try (Database database = new Database(databasePath)) {
            database.open();
            HistoryService history = new HistoryService(database);
            history.record(item("album-a", "chapter-a1"));

            List<HistoryOverviewRequest.Range> ranges = new ArrayList<>();
            for (String key : new String[]{
                    "today", "yesterday", "thisWeek", "thisMonth",
                    "lastThreeMonths", "lastSixMonths", "thisYear", "earlier"
            }) {
                ranges.add("thisWeek".equals(key)
                        ? new HistoryOverviewRequest.Range(key, 2L, 1L)
                        : new HistoryOverviewRequest.Range(key, null, null));
            }

            HistoryOverviewResponse overview = history.overview(ranges);
            assertEquals(1, overview.totalCount());
            assertEquals(1, overview.groupCounts().get("today"));
            assertEquals(0, overview.groupCounts().get("thisWeek"));

            assertEquals(0, history.page(10, 0, 1L, 1L).totalCount());
            assertEquals(0, history.page(10, 0, 2L, 1L).totalCount());

            ranges.remove(7);
            ApiException exception = assertThrows(ApiException.class, () -> history.overview(ranges));
            assertEquals("internal", exception.code());
            assertEquals(400, exception.status());
        }
    }

    @Test
    void persistsNormalizedParseHistoryAndSupportsPaginationDeleteAndClear() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-parse-history-")
                .resolve("history.sqlite3");
        long newestId;
        try (Database database = new Database(databasePath)) {
            database.open();
            HistoryService history = new HistoryService(database);

            history.addParseHistory(new ParseHistoryRecordRequest("  Keyword  ", "batch-mode"));
            history.addParseHistory(new ParseHistoryRecordRequest("second", null));
            history.addParseHistory(new ParseHistoryRecordRequest("keyword", "single-mode"));
            history.addParseHistory(new ParseHistoryRecordRequest("   ", "batch-mode"));

            ParseHistoryPageResponse firstPage = history.parsePage(1, 0);
            assertEquals(2, firstPage.totalCount());
            assertEquals("keyword", firstPage.items().get(0).text());
            assertEquals("single-mode", firstPage.items().get(0).mode());
            newestId = firstPage.items().get(0).id();

            ParseHistoryPageResponse secondPage = history.parsePage(1, 1);
            assertEquals("second", secondPage.items().get(0).text());
            assertEquals("single-mode", secondPage.items().get(0).mode());

            assertTrue(history.deleteParseItem(newestId).success());
            assertFalse(history.deleteParseItem(newestId).success());
        }

        try (Database database = new Database(databasePath)) {
            database.open();
            HistoryService history = new HistoryService(database);
            ParseHistoryPageResponse recovered = history.parsePage(0, 0);
            assertEquals(1, recovered.totalCount());
            assertEquals("second", recovered.items().get(0).text());

            history.clearParseHistory();
            assertEquals(0, history.parsePage(0, 0).totalCount());
        }
    }

    private static HistoryRecordRequest item(String albumId, String chapterId) {
        return new HistoryRecordRequest(
                albumId,
                "Album " + albumId,
                "https://example.invalid/" + albumId,
                "Author",
                chapterId,
                "Chapter " + chapterId,
                42L
        );
    }
}
