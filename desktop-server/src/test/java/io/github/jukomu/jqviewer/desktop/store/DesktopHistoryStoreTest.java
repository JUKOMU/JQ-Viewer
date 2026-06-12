package io.github.jukomu.jqviewer.desktop.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopHistoryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void parseHistoryDedupesByTextAndKeepsNewestFirst() throws Exception {
        DesktopHistoryStore store = new DesktopHistoryStore(tempDir);

        store.recordParse(parse("first", "single-mode"));
        store.recordParse(parse("second", "batch-mode"));
        store.recordParse(parse("first", "batch-mode"));

        JsonArray items = store.getParseHistory(10, 0).getAsJsonArray("items");

        assertEquals(2, items.size());
        assertEquals("first", items.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("batch-mode", items.get(0).getAsJsonObject().get("mode").getAsString());
        assertEquals("second", items.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void parseHistorySupportsPagingDeleteAndClear() throws Exception {
        DesktopHistoryStore store = new DesktopHistoryStore(tempDir);
        store.recordParse(parse("one", "single-mode"));
        store.recordParse(parse("two", "single-mode"));
        store.recordParse(parse("three", "single-mode"));

        JsonArray page = store.getParseHistory(1, 1).getAsJsonArray("items");
        assertEquals(1, page.size());

        int id = store.getParseHistory(10, 0)
            .getAsJsonArray("items")
            .get(0)
            .getAsJsonObject()
            .get("id")
            .getAsInt();
        store.deleteParseItem(id);
        assertEquals(2, store.getParseHistory(10, 0).getAsJsonArray("items").size());

        store.clearParseHistory();
        assertEquals(0, store.getParseHistory(10, 0).getAsJsonArray("items").size());
    }

    private static JsonObject parse(String text, String mode) {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("mode", mode);
        return body;
    }
}
