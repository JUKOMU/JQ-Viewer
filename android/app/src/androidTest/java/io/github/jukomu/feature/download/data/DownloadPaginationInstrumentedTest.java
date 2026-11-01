package io.github.jukomu.feature.download.data;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jukomu.feature.download.DownloadService;
import io.github.jukomu.feature.download.storage.FileStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class DownloadPaginationInstrumentedTest {
    @Test
    public void stringBoundCursorsTraverseTiedValuesAndReversePages() throws Exception {
        for (String status : Arrays.asList("active", "failed", "completed")) {
            for (String sort : Arrays.asList("time", "id", "title", "author")) {
                for (String direction : Arrays.asList("asc", "desc")) {
                    try (Fixture fixture = new Fixture()) {
                        // 不同位数 ID 检查数值排序，同一本子的多个任务检查稳定键。
                        for (String albumId : Arrays.asList("56", "123456", "2")) {
                            fixture.add(albumId, "chapter1", status, 1);
                        }
                        if (!"completed".equals(status)) fixture.add("56", "chapter2", status, 1);
                        fixture.store.getWritableDatabase().execSQL(
                            "UPDATE download_tasks SET created_at=1,completed_at=100");
                        List<String> actual = new ArrayList<>();
                        List<JSONObject> pages = new ArrayList<>();
                        String cursor = null;
                        do {
                            JSONObject page = fixture.store.getTasksPage(status, sort, direction, cursor, 1);
                            pages.add(page);
                            actual.addAll(ids(page, status));
                            cursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
                            assertTrue("cursor must advance", pages.size() <= 4);
                        } while (cursor != null);
                        List<String> expected = new ArrayList<>("completed".equals(status)
                            ? ("id".equals(sort) ? Arrays.asList("2", "56", "123456")
                                : Arrays.asList("123456", "2", "56"))
                            : ("id".equals(sort)
                                ? Arrays.asList("2_chapter1", "56_chapter1", "56_chapter2", "123456_chapter1")
                                : Arrays.asList("123456_chapter1", "2_chapter1", "56_chapter1", "56_chapter2")));
                        if ("desc".equals(direction)) Collections.reverse(expected);
                        assertEquals(status + "/" + sort + "/" + direction, expected, actual);
                        for (int i = 1; i < pages.size(); i++) {
                            JSONObject previous = fixture.store.getTasksPage(status, sort, direction,
                                pages.get(i).getString("previousCursor"), 1);
                            assertEquals(ids(pages.get(i - 1), status), ids(previous, status));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void completedTimeUsesNumericOrderingAndKeepsAlbumChaptersTogether() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.add("album1", "chapter1", "completed", 1);
            fixture.add("album1", "chapter2", "completed", 2);
            fixture.add("album2", "chapter1", "completed", 3);
            for (String direction : Arrays.asList("asc", "desc")) {
                JSONObject first = fixture.store.getTasksPage("completed", "time", direction, null, 1);
                JSONObject second = fixture.store.getTasksPage("completed", "time", direction,
                    first.getString("nextCursor"), 1);
                assertEquals(Arrays.asList("asc".equals(direction) ? "album1" : "album2"), ids(first, "completed"));
                assertEquals(Arrays.asList("asc".equals(direction) ? "album2" : "album1"), ids(second, "completed"));
                JSONObject album1 = ("asc".equals(direction) ? first : second).getJSONArray("items").getJSONObject(0);
                assertEquals(2, album1.getJSONArray("chapters").length());
                assertTrue(second.isNull("nextCursor"));
            }
        }
    }

    @Test
    public void authorPagesMatchSqlNullRankForSpacesAndTabs() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.add("album1", "chapter1", "failed", 1);
            fixture.add("album1", "chapter2", "failed", 1);
            fixture.add("album2", "chapter1", "failed", 1);
            fixture.store.getWritableDatabase().execSQL(
                "UPDATE download_tasks SET author=NULL WHERE task_id='album1_chapter1'");
            fixture.store.getWritableDatabase().execSQL(
                "UPDATE download_tasks SET author=' ' WHERE task_id='album1_chapter2'");
            fixture.add("album3", "chapter1", "failed", 1);
            fixture.store.updateTaskDetail("album3_chapter1", 1, "\t", "[]", "[]", 1, true);
            for (String direction : Arrays.asList("asc", "desc")) {
                List<String> actual = new ArrayList<>();
                String cursor = null;
                int requests = 0;
                do {
                    JSONObject page = fixture.store.getTasksPage("failed", "author", direction, cursor, 1);
                    actual.addAll(ids(page, "failed"));
                    cursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
                    assertTrue(++requests <= 4);
                } while (cursor != null);
                assertEquals("asc".equals(direction)
                    ? Arrays.asList("album3_chapter1", "album2_chapter1", "album1_chapter1", "album1_chapter2")
                    : Arrays.asList("album2_chapter1", "album3_chapter1", "album1_chapter2", "album1_chapter1"), actual);
            }
        }
    }

    @Test
    public void clearContinuesAfterFileDeletionFailureAndRetainsItsRecord() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.add("bad", "chapter", "completed", 1);
            fixture.add("good", "chapter", "completed", 2);
            File directory = fixture.files.ensureChapterDir("bad", "chapter");
            assertTrue(new File(directory, "image.jpg").createNewFile());
            fixture.files.ensureChapterDir("good", "chapter");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            DownloadService service = new DownloadService(fixture.store, fixture.files, () -> null,
                executor, null, fixture.context);
            try {
                assertTrue(directory.setWritable(false, false));
                org.junit.Assume.assumeFalse(directory.canWrite());
                JSONObject result = service.clearDownloadTasks("completed");
                assertEquals(1, result.getInt("deletedCount"));
                assertEquals(1, result.getInt("failedCount"));
                assertNotNull(fixture.store.getTask("bad_chapter"));
                assertNull(fixture.store.getTask("good_chapter"));
                assertTrue(new File(directory, "image.jpg").isFile());
            } finally {
                assertTrue(directory.setWritable(true, true));
                executor.shutdownNow();
            }
        }
    }

    private static List<String> ids(JSONObject page, String status) throws Exception {
        JSONArray items = page.getJSONArray("items");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            result.add(items.getJSONObject(i).getString("completed".equals(status) ? "albumId" : "taskId"));
        }
        return result;
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File databaseFile = new File(context.getCacheDir(), "download-pages-" + UUID.randomUUID() + ".db");
        final File directory = new File(context.getCacheDir(), "download-files-" + UUID.randomUUID());
        final DownloadStore store;
        final FileStore files;

        Fixture() throws Exception {
            Context isolated = new ContextWrapper(context) {
                @Override public File getDatabasePath(String name) { return databaseFile; }
                @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory) {
                    return SQLiteDatabase.openOrCreateDatabase(databaseFile.getPath(), factory);
                }
                @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                    return SQLiteDatabase.openOrCreateDatabase(databaseFile.getPath(), factory, handler);
                }
            };
            Constructor<DownloadStore> constructor = DownloadStore.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            store = constructor.newInstance(isolated);
            Constructor<FileStore> fileConstructor = FileStore.class.getDeclaredConstructor();
            fileConstructor.setAccessible(true);
            files = fileConstructor.newInstance();
            assertTrue(directory.mkdirs());
            Field base = FileStore.class.getDeclaredField("baseDir");
            base.setAccessible(true);
            base.set(files, directory);
        }

        void add(String album, String chapter, String status, long time) throws Exception {
            String id = album + "_" + chapter;
            store.insertTask(id, album, chapter, "Same title", chapter, "");
            store.updateTaskDetail(id, 1, "Alice", "[]", "[]", 1, true);
            store.getWritableDatabase().execSQL(
                "UPDATE download_tasks SET status=?,created_at=?,completed_at=? WHERE task_id=?",
                new Object[]{"active".equals(status) ? "queued" : status, time, time, id});
        }

        @Override public void close() {
            for (JSONObject task : store.getAllTasks()) {
                files.deleteChapter(task.optString("albumId"), task.optString("chapterId"));
            }
            store.close();
            SQLiteDatabase.deleteDatabase(databaseFile);
            directory.delete();
        }
    }
}
