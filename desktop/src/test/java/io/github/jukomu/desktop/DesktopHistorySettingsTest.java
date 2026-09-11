package io.github.jukomu.desktop;

import io.github.jukomu.desktop.data.DesktopDatabase;
import io.github.jukomu.desktop.data.DesktopHistoryStore;
import io.github.jukomu.desktop.data.DesktopSettingsStore;
import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.service.DesktopHistoryService;
import io.github.jukomu.desktop.service.DesktopSettingsService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopHistorySettingsTest {
    @Test
    void deduplicatesTheLatestAlbumAndPaginatesByTimestampAndId() throws Exception {
        try (DesktopDatabase database = database()) {
            DesktopHistoryService history = new DesktopHistoryService(new DesktopHistoryStore(database));
            history.recordBrowse(record("album-1", "chapter-1", "第一章"));
            history.recordBrowse(record("album-1", "chapter-2", "第二章"));
            history.recordBrowse(record("album-2", "chapter-3", "第三章"));

            DesktopDtos.HistoryPage page = history.getBrowseHistory(1, 1, null, null);

            assertEquals(2, page.totalCount());
            assertEquals(1, page.items().size());
            assertEquals("album-1", page.items().getFirst().albumId());
            assertEquals("chapter-2", page.items().getFirst().chapterId());
        }
    }

    @Test
    void returnsStableRangeCountsAndRejectsIncompleteRanges() throws Exception {
        try (DesktopDatabase database = database()) {
            DesktopHistoryService history = new DesktopHistoryService(new DesktopHistoryStore(database));
            history.recordBrowse(record("album-1", "chapter-1", "第一章"));
            history.recordBrowse(record("album-2", "chapter-2", "第二章"));
            history.recordBrowse(record("album-3", "chapter-3", "第三章"));
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "UPDATE browse_history SET timestamp = ? WHERE album_id = ?")) {
                updateTimestamp(statement, 100L, "album-1");
                updateTimestamp(statement, 100L, "album-2");
                updateTimestamp(statement, 50L, "album-3");
            }

            List<DesktopDtos.BrowseHistoryRange> ranges = new ArrayList<>();
            ranges.add(new DesktopDtos.BrowseHistoryRange("today", 100L, null));
            ranges.add(new DesktopDtos.BrowseHistoryRange("yesterday", 50L, 100L));
            ranges.add(new DesktopDtos.BrowseHistoryRange("thisWeek", 0L, 50L));
            ranges.add(new DesktopDtos.BrowseHistoryRange("thisMonth", 0L, 0L));
            ranges.add(new DesktopDtos.BrowseHistoryRange("lastThreeMonths", 0L, 0L));
            ranges.add(new DesktopDtos.BrowseHistoryRange("lastSixMonths", 0L, 0L));
            ranges.add(new DesktopDtos.BrowseHistoryRange("thisYear", 0L, 0L));
            ranges.add(new DesktopDtos.BrowseHistoryRange("earlier", null, 0L));

            DesktopDtos.BrowseHistoryOverview overview = history.getBrowseHistoryOverview(ranges);

            assertEquals(3, overview.totalCount());
            assertEquals(2, overview.groupCounts().get("today"));
            assertEquals(1, overview.groupCounts().get("yesterday"));
            assertEquals(0, overview.groupCounts().get("thisYear"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> history.getBrowseHistoryOverview(ranges.subList(0, 7))
            );
        }
    }

    @Test
    void persistsBasicSettingsWithValidation() throws Exception {
        try (DesktopDatabase database = database()) {
            DesktopSettingsService settings = new DesktopSettingsService(new DesktopSettingsStore(database));

            DesktopDtos.AllSettings defaults = settings.getAllSettings();
            assertEquals(15, defaults.readerPreloadPages());
            assertEquals(6, defaults.preloadConcurrency());
            assertEquals("vertical", defaults.readerDisplayMode());
            assertTrue(defaults.readerKeepScreenOn());

            settings.setReaderPreloadPages(24);
            settings.setPreloadConcurrency(3);
            settings.setReaderDisplayMode("horizontal");
            settings.setReaderAutoShowToolbarAtEnd(false);
            settings.setDownloadConcurrency(2);

            DesktopDtos.AllSettings updated = settings.getAllSettings();
            assertEquals(24, updated.readerPreloadPages());
            assertEquals(3, updated.preloadConcurrency());
            assertEquals(2, updated.downloadConcurrency());
            assertEquals("horizontal", updated.readerDisplayMode());
            assertFalse(updated.readerAutoShowToolbarAtEnd());
            assertThrows(IllegalArgumentException.class, () -> settings.setReaderPreloadPages(4));
            assertThrows(IllegalArgumentException.class, () -> settings.setReaderDisplayMode("invalid"));
        }
    }

    private static DesktopDatabase database() throws Exception {
        Path path = Files.createTempDirectory("jq-viewer-history-").resolve("data/desktop.sqlite3");
        DesktopDatabase database = new DesktopDatabase(path);
        database.open();
        return database;
    }

    private static DesktopDtos.RecordBrowseRequest record(
            String albumId,
            String chapterId,
            String chapterTitle
    ) {
        return new DesktopDtos.RecordBrowseRequest(
                albumId,
                albumId,
                "https://example.test/cover.jpg",
                "作者",
                chapterId,
                chapterTitle
        );
    }

    private static void updateTimestamp(PreparedStatement statement, long timestamp, String albumId)
            throws Exception {
        statement.setLong(1, timestamp);
        statement.setString(2, albumId);
        statement.executeUpdate();
    }
}
