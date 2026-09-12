package io.github.jukomu.desktop;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.history.HistoryService;
import io.github.jukomu.desktop.feature.history.model.HistoryOverviewRequest;
import io.github.jukomu.desktop.feature.history.model.HistoryOverviewResponse;
import io.github.jukomu.desktop.feature.history.model.HistoryPageResponse;
import io.github.jukomu.desktop.feature.history.model.HistoryRecordRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

            HistoryPageResponse page = history.page(1, 0, null, null);
            assertEquals(2, page.totalCount());
            assertEquals("album-b", page.items().get(0).albumId());
            assertEquals("chapter-b1", page.items().get(0).chapterId());

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
                ranges.add(new HistoryOverviewRequest.Range(key, null, null));
            }

            HistoryOverviewResponse overview = history.overview(ranges);
            assertEquals(1, overview.totalCount());
            assertEquals(1, overview.groupCounts().get("today"));

            ranges.remove(7);
            ApiException exception = assertThrows(ApiException.class, () -> history.overview(ranges));
            assertEquals("internal", exception.code());
            assertEquals(400, exception.status());
        }
    }

    private static HistoryRecordRequest item(String albumId, String chapterId) {
        return new HistoryRecordRequest(
                albumId,
                "Album " + albumId,
                "https://example.invalid/" + albumId,
                "Author",
                chapterId,
                "Chapter " + chapterId
        );
    }
}
