package io.github.jukomu.feature.download.data;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DownloadStorePaginationInstrumentedTest {
    @Test
    public void idPagesContinueForNonNumericAlbumIds() throws Exception {
        DownloadStore store = DownloadStore.getInstance(
            InstrumentationRegistry.getInstrumentation().getTargetContext());
        String suffix = Long.toString(System.nanoTime());
        List<String> taskIds = new ArrayList<>();
        List<String> albumIds = List.of("album-1-" + suffix, "album-2-" + suffix,
            "album-3-" + suffix);
        for (String albumId : albumIds) {
            String taskId = albumId + "_chapter-1";
            taskIds.add(taskId);
            store.insertTask(taskId, albumId, "chapter-1", "Album " + albumId,
                "Chapter", "");
            store.updateCompleted(taskId, 1, 1);
        }

        try {
            assertEquals(albumIds, pageIds(store, "completed", Set.copyOf(albumIds), true));
            for (String taskId : taskIds) store.updateFailed(taskId, 0, "failed");
            assertEquals(taskIds, pageIds(store, "failed", Set.copyOf(albumIds), false));
        } finally {
            for (String taskId : taskIds) store.deleteTask(taskId);
        }
    }

    private static List<String> pageIds(DownloadStore store, String status,
                                        Set<String> expectedAlbums, boolean completed)
        throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JSONObject page = store.getTasksPage(status, "id", "asc", cursor, 1);
            JSONArray items = page.getJSONArray("items");
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.getJSONObject(index);
                String albumId = item.getString("albumId");
                if (!expectedAlbums.contains(albumId)) continue;
                ids.add(completed ? albumId : item.getString("taskId"));
            }
            cursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
            assertTrue("cursor must advance", ++pages < 1000);
        } while (cursor != null);
        return ids;
    }
}
