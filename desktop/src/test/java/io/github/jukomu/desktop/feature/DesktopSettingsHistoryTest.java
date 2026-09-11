package io.github.jukomu.desktop.feature;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.data.DesktopDatabase;
import io.github.jukomu.desktop.feature.history.DesktopHistory;
import io.github.jukomu.desktop.feature.settings.DesktopSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSettingsHistoryTest {
    @Test
    void persistsOnlySupportedSettingsAndReturnsStableDesktopValues() throws Exception {
        try (DesktopDatabase database = database()) {
            DesktopSettings settings = new DesktopSettings(database);
            assertEquals(6, settings.getAllSettings().path("preloadConcurrency").asInt());
            assertFalse(settings.getAllSettings().has("cacheRequestedMb"));

            settings.setPreloadConcurrency(4);
            settings.setDownloadConcurrency(8);
            settings.setReaderPreloadPages(20);
            settings.setReaderDisplayMode("horizontal");
            settings.setReaderAutoShowToolbarAtEnd(false);

            var result = settings.getAllSettings();
            assertEquals(4, result.path("preloadConcurrency").asInt());
            assertEquals(8, result.path("downloadConcurrency").asInt());
            assertEquals(20, result.path("readerPreloadPages").asInt());
            assertEquals("horizontal", result.path("readerDisplayMode").asText());
            assertFalse(result.path("readerAutoShowToolbarAtEnd").asBoolean());
            assertThrows(IllegalArgumentException.class, () -> settings.setDownloadConcurrency(13));
        }
    }

    @Test
    void deduplicatesOnlyTheLatestAlbumAndSupportsPagingAndRanges() throws Exception {
        try (DesktopDatabase database = database()) {
            DesktopHistory history = new DesktopHistory(database);
            history.recordBrowse(browse("a", "第一章"));
            history.recordBrowse(browse("a", "第二章"));
            history.recordBrowse(browse("b", "第一章"));

            var page = history.getBrowseHistory(1, 0, null, null);
            assertEquals(2, page.path("totalCount").asInt());
            assertEquals("b", page.path("items").get(0).path("albumId").asText());

            long now = System.currentTimeMillis();
            var ranged = history.getBrowseHistory(0, 0, now + 1, null);
            assertEquals(0, ranged.path("totalCount").asInt());

            var ranges = JsonNodeFactory.instance.arrayNode();
            for (String key : new String[]{
                    "today", "yesterday", "thisWeek", "thisMonth",
                    "lastThreeMonths", "lastSixMonths", "thisYear", "earlier"
            }) {
                ranges.addObject().put("key", key).putNull("startInclusive").putNull("endExclusive");
            }
            var overview = history.getBrowseHistoryOverview(ranges);
            assertEquals(2, overview.path("totalCount").asInt());
            assertEquals(2, overview.path("groupCounts").path("today").asInt());

            long id = page.path("items").get(0).path("id").asLong();
            assertTrue(history.deleteBrowseItem(id).path("success").asBoolean());
            assertEquals(1, history.getBrowseHistory(0, 0, null, null).path("totalCount").asInt());
        }
    }

    private static DesktopDatabase database() throws Exception {
        DesktopDatabase database = new DesktopDatabase(
                Files.createTempDirectory("jq-viewer-feature-").resolve("desktop.sqlite3")
        );
        database.open();
        return database;
    }

    private static ObjectNode browse(String albumId, String chapterTitle) {
        return JsonNodeFactory.instance.objectNode()
                .put("albumId", albumId)
                .put("albumTitle", "专辑 " + albumId)
                .put("coverUrl", "https://cover/" + albumId)
                .put("authors", "作者")
                .put("chapterId", albumId + "-1")
                .put("chapterTitle", chapterTitle);
    }
}
