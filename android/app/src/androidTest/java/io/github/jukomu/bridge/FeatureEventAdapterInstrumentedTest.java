package io.github.jukomu.bridge;

import com.getcapacitor.JSObject;
import io.github.jukomu.feature.download.model.DownloadProgressData;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FeatureEventAdapterInstrumentedTest {

    private FeatureEventAdapter adapter;
    private String eventName;
    private JSObject eventData;
    private int eventCount;

    @Before
    public void setUp() {
        adapter = new FeatureEventAdapter((name, data) -> {
            eventName = name;
            eventData = data;
            eventCount++;
        });
    }

    @Test
    public void downloadProgressIncludesPositiveByteFieldsAndError() {
        adapter.onDownloadProgress(new DownloadProgressData(
            "task",
            "album",
            "chapter",
            2,
            5,
            "failed",
            "network",
            100,
            1000,
            400));

        assertEquals("downloadProgress", eventName);
        assertEquals("task", eventData.getString("taskId"));
        assertEquals("album", eventData.getString("albumId"));
        assertEquals("chapter", eventData.getString("chapterId"));
        assertEquals(2, eventData.getInteger("downloadedPages").intValue());
        assertEquals(5, eventData.getInteger("totalPages").intValue());
        assertEquals("failed", eventData.getString("status"));
        assertEquals("network", eventData.getString("error"));
        assertEquals(100L, eventData.optLong("speed"));
        assertEquals(400L, eventData.optLong("downloadedBytes"));
        assertEquals(1000L, eventData.optLong("totalSize"));
        assertEquals(1, eventCount);
    }

    @Test
    public void downloadProgressOmitsAbsentOptionalFields() {
        adapter.onDownloadProgress(new DownloadProgressData(
            "task", "album", "chapter", 0, 0, "queued", null, 0, 0, 0));

        assertFalse(eventData.has("error"));
        assertFalse(eventData.has("downloadedBytes"));
        assertFalse(eventData.has("totalSize"));
        assertTrue(eventData.has("speed"));
    }

    @Test
    public void imageReadyUsesExpectedPayload() {
        adapter.onImageReady("photo", 9, "thumbnail");

        assertEquals("imageReady", eventName);
        assertEquals("photo", eventData.getString("photoId"));
        assertEquals(9, eventData.getInteger("sortOrder").intValue());
        assertEquals("thumbnail", eventData.getString("type"));
        assertEquals(1, eventCount);
    }

    @Test
    public void imageFailedUsesExpectedPayload() {
        adapter.onImageFailed("photo", 9, "image");

        assertEquals("imageFailed", eventName);
        assertEquals("photo", eventData.getString("photoId"));
        assertEquals(9, eventData.getInteger("sortOrder").intValue());
        assertEquals("image", eventData.getString("type"));
        assertEquals(1, eventCount);
    }

    @Test
    public void relocationProgressIncludesCurrentFileOnlyWhenPresent() {
        adapter.onRelocationProgress(1, 3, "copy", "chapter/file.webp");

        assertEquals("relocationProgress", eventName);
        assertEquals(1, eventData.getInteger("current").intValue());
        assertEquals(3, eventData.getInteger("total").intValue());
        assertEquals("copy", eventData.getString("phase"));
        assertEquals("chapter/file.webp", eventData.getString("currentFile"));

        adapter.onRelocationProgress(3, 3, "done", null);

        assertEquals(2, eventCount);
        assertEquals("done", eventData.getString("phase"));
        assertFalse(eventData.has("currentFile"));
    }
}
