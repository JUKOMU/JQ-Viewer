package io.github.jukomu.feature.download.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import io.github.jukomu.feature.download.api.DownloadTaskReader;
import io.github.jukomu.util.JsonUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载任务 SQLite 数据库（权威数据源）。
 * <p>
 * 表结构：
 * - download_tasks: 任务元数据
 * - download_images: 图片信息（sortOrder→filename 映射等）
 */
public class DownloadStore extends SQLiteOpenHelper implements DownloadTaskReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadStore.class);
    private static final String DB_NAME = "jq_download.db";
    private static final int DB_VERSION = 6;

    // ---- 表名 ----
    static final String TABLE_TASKS = "download_tasks";
    static final String TABLE_IMAGES = "download_images";

    // ---- tasks 列 ----
    static final String COL_TASK_ID = "task_id";
    static final String COL_ALBUM_ID = "album_id";
    static final String COL_CHAPTER_ID = "chapter_id";
    static final String COL_ALBUM_TITLE = "album_title";
    static final String COL_CHAPTER_TITLE = "chapter_title";
    static final String COL_COVER_URL = "cover_url";
    static final String COL_AUTHOR = "author";
    static final String COL_AUTHORS = "authors_json";
    static final String COL_TAGS = "tags";
    static final String COL_TOTAL_PAGES = "total_pages";
    static final String COL_DOWNLOADED_PAGES = "downloaded_pages";
    static final String COL_FIRST_IMAGE_SORT_ORDER = "first_image_sort_order";
    static final String COL_STATUS = "status";
    static final String COL_ERROR = "error";
    static final String COL_CREATED_AT = "created_at";
    static final String COL_COMPLETED_AT = "completed_at";
    static final String COL_TOTAL_SIZE = "total_size";
    static final String COL_CHAPTER_SORT_ORDER = "chapter_sort_order";
    static final String COL_IS_SINGLE_EPISODE = "is_single_episode";

    // ---- images 列 ----
    static final String COL_SORT_ORDER = "sort_order";
    static final String COL_PHOTO_ID = "photo_id";
    static final String COL_FILENAME = "filename";
    static final String COL_URL = "url";
    static final String COL_SCRAMBLE_ID = "scramble_id";
    static final String COL_QUERY_PARAMS = "query_params";

    private static DownloadStore instance;

    public static synchronized DownloadStore getInstance(Context context) {
        if (instance == null) {
            instance = new DownloadStore(context.getApplicationContext());
        }
        return instance;
    }

    private DownloadStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TASKS + " ("
            + COL_TASK_ID + " TEXT PRIMARY KEY,"
            + COL_ALBUM_ID + " TEXT NOT NULL,"
            + COL_CHAPTER_ID + " TEXT NOT NULL,"
            + COL_ALBUM_TITLE + " TEXT NOT NULL,"
            + COL_CHAPTER_TITLE + " TEXT NOT NULL,"
            + COL_COVER_URL + " TEXT NOT NULL,"
            + COL_AUTHOR + " TEXT DEFAULT '',"
            + COL_AUTHORS + " TEXT DEFAULT '[]',"
            + COL_TAGS + " TEXT DEFAULT '[]',"
            + COL_TOTAL_PAGES + " INTEGER NOT NULL,"
            + COL_DOWNLOADED_PAGES + " INTEGER DEFAULT 0,"
            + COL_FIRST_IMAGE_SORT_ORDER + " INTEGER,"
            + COL_STATUS + " TEXT NOT NULL DEFAULT 'queued',"
            + COL_ERROR + " TEXT,"
            + COL_TOTAL_SIZE + " INTEGER DEFAULT 0,"
            + COL_CHAPTER_SORT_ORDER + " INTEGER DEFAULT 0,"
            + COL_IS_SINGLE_EPISODE + " INTEGER DEFAULT -1,"
            + COL_CREATED_AT + " INTEGER NOT NULL,"
            + COL_COMPLETED_AT + " INTEGER"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_IMAGES + " ("
            + COL_TASK_ID + " TEXT NOT NULL,"
            + COL_SORT_ORDER + " INTEGER NOT NULL,"
            + COL_PHOTO_ID + " TEXT NOT NULL,"
            + COL_FILENAME + " TEXT NOT NULL,"
            + COL_URL + " TEXT NOT NULL,"
            + COL_SCRAMBLE_ID + " TEXT NOT NULL,"
            + COL_QUERY_PARAMS + " TEXT,"
            + "PRIMARY KEY (" + COL_TASK_ID + ", " + COL_SORT_ORDER + "),"
            + "FOREIGN KEY (" + COL_TASK_ID + ") REFERENCES " + TABLE_TASKS + "(" + COL_TASK_ID + ")"
            + ")");
        createQueryIndexes(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN " + COL_TOTAL_SIZE + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN " + COL_CHAPTER_SORT_ORDER + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN " + COL_IS_SINGLE_EPISODE + " INTEGER DEFAULT -1");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN " + COL_AUTHORS + " TEXT DEFAULT '[]'");
        }
        if (oldVersion < 6) {
            createQueryIndexes(db);
        }
    }

    // ========== 任务操作 ==========

    public void insertTask(String taskId, String albumId, String chapterId,
                           String albumTitle, String chapterTitle, String coverUrl) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TASK_ID, taskId);
        cv.put(COL_ALBUM_ID, albumId);
        cv.put(COL_CHAPTER_ID, chapterId);
        cv.put(COL_ALBUM_TITLE, albumTitle);
        cv.put(COL_CHAPTER_TITLE, chapterTitle);
        cv.put(COL_COVER_URL, coverUrl);
        cv.put(COL_TOTAL_PAGES, 0);
        cv.put(COL_STATUS, "queued");
        cv.put(COL_CREATED_AT, System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(TABLE_TASKS, null, cv,
            SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void updateTaskDetail(String taskId, int totalPages, String author, String authors,
                                 String tags,
                                 int sortOrder, boolean isSingleEpisode) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TOTAL_PAGES, totalPages);
        cv.put(COL_AUTHOR, author == null ? "" : author);
        cv.put(COL_AUTHORS, authors == null || authors.isBlank() ? "[]" : authors);
        cv.put(COL_TAGS, tags == null || tags.isBlank() ? "[]" : tags);
        cv.put(COL_CHAPTER_SORT_ORDER, sortOrder);
        cv.put(COL_IS_SINGLE_EPISODE, isSingleEpisode ? 1 : 0);
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateTaskDetail(String taskId, int totalPages, String author, String tags,
                                 int sortOrder, boolean isSingleEpisode) {
        updateTaskDetail(taskId, totalPages, author, "[]", tags, sortOrder, isSingleEpisode);
    }

    public void updateStatus(String taskId, String status) {
        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS, status);
        if ("downloading".equals(status)) {
            cv.putNull(COL_ERROR);
        }
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateProgress(String taskId, int downloadedPages) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED_PAGES, downloadedPages);
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateCompleted(String taskId, int downloadedPages, int firstSortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED_PAGES, downloadedPages);
        cv.put(COL_FIRST_IMAGE_SORT_ORDER, firstSortOrder);
        cv.put(COL_STATUS, "completed");
        cv.putNull(COL_ERROR);
        cv.put(COL_COMPLETED_AT, System.currentTimeMillis());
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateFailed(String taskId, int downloadedPages, String error) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED_PAGES, downloadedPages);
        cv.put(COL_STATUS, "failed");
        cv.put(COL_ERROR, error);
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateSize(String taskId, long totalSize) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TOTAL_SIZE, totalSize);
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public int updateAlbumEpisodeType(String albumId, boolean isSingleEpisode) {
        ContentValues cv = new ContentValues();
        cv.put(COL_IS_SINGLE_EPISODE, isSingleEpisode ? 1 : 0);
        return getWritableDatabase().update(TABLE_TASKS, cv,
            COL_ALBUM_ID + " = ?", new String[]{albumId});
    }

    public boolean hasActiveOrCompleted(String taskId) {
        Cursor c = getReadableDatabase().query(TABLE_TASKS,
            new String[]{COL_STATUS}, COL_TASK_ID + " = ?",
            new String[]{taskId}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    String status = c.getString(0);
                    return "queued".equals(status) || "downloading".equals(status)
                        || "paused".equals(status) || "verifying".equals(status)
                        || "completed".equals(status);
                }
            } finally {
                c.close();
            }
        }
        return false;
    }

    public List<JSONObject> getAllTasks() {
        List<JSONObject> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_TASKS, null, null,
            null, null, null, COL_CREATED_AT + " DESC", "500");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    JSONObject task = cursorToTaskJson(c);
                    if (task == null) {
                        LOGGER.warn("跳过损坏的任务记录");
                        continue;
                    }
                    list.add(task);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public List<JSONObject> getActiveTasks() {
        return queryTasks(COL_STATUS + " IN (?, ?, ?, ?)",
            new String[]{"queued", "downloading", "paused", "verifying"},
            COL_CREATED_AT + " ASC, " + COL_TASK_ID + " ASC", null);
    }

    public List<JSONObject> getCompletedTasks() {
        return queryTasks(COL_STATUS + " = ?", new String[]{"completed"},
            COL_ALBUM_ID + " ASC, " + COL_CHAPTER_ID + " ASC", null);
    }

    public List<JSONObject> getTasksByStatus(String status) {
        if (!"completed".equals(status) && !"failed".equals(status)) {
            throw new IllegalArgumentException("status 必须是 completed 或 failed");
        }
        return queryTasks(COL_STATUS + " = ?", new String[]{status}, COL_TASK_ID + " ASC", null);
    }

    public int deleteTasksByStatus(List<String> taskIds, String status) {
        SQLiteDatabase db = getWritableDatabase();
        int deleted = 0;
        db.beginTransaction();
        try {
            for (String taskId : taskIds) {
                int taskDeleted = db.delete(TABLE_TASKS,
                    COL_TASK_ID + " = ? AND " + COL_STATUS + " = ?",
                    new String[]{taskId, status});
                if (taskDeleted > 0) {
                    db.delete(TABLE_IMAGES, COL_TASK_ID + " = ?", new String[]{taskId});
                    deleted += taskDeleted;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return deleted;
    }

    public boolean hasActiveTasks() {
        try (Cursor c = getReadableDatabase().query(TABLE_TASKS, new String[]{COL_TASK_ID},
            COL_STATUS + " IN (?, ?, ?, ?)",
            new String[]{"queued", "downloading", "paused", "verifying"}, null, null, null, "1")) {
            return c.moveToFirst();
        }
    }

    public JSONObject getTasksPage(String status, String sort, String direction,
                                   String cursor, int requestedLimit) throws org.json.JSONException {
        String normalizedStatus = normalizeStatus(status);
        String normalizedSort = normalizeSort(sort);
        String normalizedDirection = "desc".equalsIgnoreCase(direction) ? "desc" : "asc";
        int limit = Math.max(1, Math.min(50, requestedLimit));
        CursorBoundary boundary = CursorBoundary.decode(cursor, normalizedStatus, normalizedSort,
            normalizedDirection);
        String queryDirection = boundary != null && boundary.previous
            ? flip(normalizedDirection) : normalizedDirection;
        // completed 先按 album 聚合并分页，再批量展开章节，保证同一本子不会跨页。
        if ("completed".equals(normalizedStatus)) {
            return completedPage(normalizedSort, normalizedDirection, queryDirection, boundary, limit);
        }
        String selection = "active".equals(normalizedStatus)
            ? COL_STATUS + " IN (?, ?, ?, ?)" : COL_STATUS + " = ?";
        String[] args = "active".equals(normalizedStatus)
            ? new String[]{"queued", "downloading", "paused", "verifying"}
            : new String[]{"failed"};
        String value = sortColumn(normalizedSort);
        String order = orderClause(value, queryDirection, COL_TASK_ID, boundary != null && boundary.previous);
        if (boundary != null) {
            selection += " AND " + keysetClause(value, queryDirection, COL_TASK_ID,
                boundary.previous);
            args = append(args, boundary.nullFlag(), boundary.nullFlag(), boundary.value,
                boundary.value, boundary.stable, boundary.stable);
        }
        List<JSONObject> tasks = queryTasks(selection, args, order, Integer.toString(limit + 1));
        boolean hasNext = tasks.size() > limit;
        if (hasNext) tasks = new ArrayList<>(tasks.subList(0, limit));
        if (boundary != null && boundary.previous) java.util.Collections.reverse(tasks);
        JSONArray items = new JSONArray();
        for (JSONObject task : tasks) items.put(task);
        JSONObject result = new JSONObject();
        result.put("items", items);
        result.put("nextCursor", (boundary != null && boundary.previous || hasNext) && !tasks.isEmpty()
            ? CursorBoundary.from(tasks.get(tasks.size() - 1), normalizedSort)
              .encode(false, normalizedStatus, normalizedSort, normalizedDirection) : JSONObject.NULL);
        result.put("previousCursor", boundary != null && (!boundary.previous || hasNext) && !tasks.isEmpty()
            ? CursorBoundary.from(tasks.get(0), normalizedSort)
              .encode(true, normalizedStatus, normalizedSort, normalizedDirection) : JSONObject.NULL);
        result.put("totalCount", "completed".equals(normalizedStatus)
            ? countCompletedAlbums() : countTasks(normalizedStatus));
        return result;
    }

    public List<JSONObject> getCompletedChapters(String albumId) {
        return queryTasks(COL_ALBUM_ID + " = ? AND " + COL_STATUS + " = ?",
            new String[]{albumId, "completed"}, COL_CHAPTER_ID + " ASC", null);
    }

    public List<String> getCompletedAlbumIds(List<String> albumIds) {
        if (albumIds == null || albumIds.isEmpty()) return List.of();
        String[] args = albumIds.toArray(new String[0]);
        String placeholders = String.join(",", java.util.Collections.nCopies(args.length, "?"));
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT DISTINCT " + COL_ALBUM_ID + " FROM " + TABLE_TASKS + " WHERE " + COL_STATUS
                + "='completed' AND " + COL_ALBUM_ID + " IN (" + placeholders + ")", args)) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }

    public List<JSONObject> getAlbumTaskStatuses(String albumId) {
        return queryTasks(COL_ALBUM_ID + " = ?", new String[]{albumId},
            COL_CHAPTER_ID + " ASC", null);
    }

    public List<JSONObject> getActiveProgress() {
        return getActiveTasks();
    }

    public List<String> getCompletedResourceKeys(List<String> albumIds, List<String> chapterIds) {
        if (albumIds == null || chapterIds == null || albumIds.size() != chapterIds.size()) return List.of();
        if (albumIds.isEmpty()) return List.of();
        String predicate = String.join(" OR ", java.util.Collections.nCopies(albumIds.size(),
            "(" + COL_ALBUM_ID + "=? AND " + COL_CHAPTER_ID + "=?)"));
        String[] args = new String[albumIds.size() * 2];
        for (int i = 0; i < albumIds.size(); i++) {
            args[i * 2] = albumIds.get(i);
            args[i * 2 + 1] = chapterIds.get(i);
        }
        java.util.Set<String> completed = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT " + COL_ALBUM_ID + "," + COL_CHAPTER_ID + " FROM " + TABLE_TASKS
                + " WHERE " + COL_STATUS + "='completed' AND (" + predicate + ")", args)) {
            while (c.moveToNext()) completed.add(c.getString(0) + "|" + c.getString(1));
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < albumIds.size(); i++) {
            String key = albumIds.get(i) + "|" + chapterIds.get(i);
            if (completed.contains(key)) result.add(key);
        }
        return result;
    }

    public int countTasks(String status) {
        String selection = "active".equals(status)
            ? COL_STATUS + " IN (?, ?, ?, ?)" : COL_STATUS + " = ?";
        String[] args = "active".equals(status)
            ? new String[]{"queued", "downloading", "paused", "verifying"}
            : new String[]{status};
        if ("completed".equals(status)) selection = COL_STATUS + " = ?";
        try (Cursor c = getReadableDatabase().query(TABLE_TASKS,
            new String[]{"COUNT(*)"}, selection, args, null, null, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countCompletedAlbums() {
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT COUNT(DISTINCT " + COL_ALBUM_ID + ") FROM " + TABLE_TASKS
                + " WHERE " + COL_STATUS + "='completed'", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private List<JSONObject> queryTasks(String selection, String[] args, String order, String limit) {
        List<JSONObject> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE_TASKS, null, selection, args,
            null, null, order, limit)) {
            while (c.moveToNext()) {
                JSONObject task = cursorToTaskJson(c);
                if (task != null) list.add(task);
            }
        }
        return list;
    }

    private JSONObject completedPage(String sort, String direction, String queryDirection,
                                     CursorBoundary boundary, int limit) throws org.json.JSONException {
        String value = switch (sort) {
            case "title" -> "g.album_title";
            case "id" -> "CAST(g.album_id AS INTEGER)";
            case "author" -> "r.author";
            default -> "g.latest_completed_at";
        };
        String selection = boundary == null ? "" : " AND " + keysetClause(value, queryDirection,
            "g.album_id", boundary.previous)
                                                             .replace("task_id", "g.album_id");
        String order = orderClause(value, queryDirection, "g.album_id", boundary != null && boundary.previous);
        // 代表章节使用最小 chapter_id；author/cover/isSingleEpisode 均从同一条记录读取。
        String sql = "SELECT g.album_id,g.album_title,g.latest_completed_at,g.total_size,g.rep_chapter_id,"
            + "r.cover_url,r.author,r.authors_json,r.is_single_episode FROM (SELECT album_id,"
            + "MIN(album_title) album_title,CAST(MAX(completed_at) AS INTEGER) latest_completed_at,SUM(total_size) total_size,"
            + "MIN(chapter_id) rep_chapter_id FROM download_tasks WHERE status='completed' GROUP BY album_id) g "
            + "JOIN download_tasks r ON r.album_id=g.album_id AND r.chapter_id=g.rep_chapter_id "
            + "AND r.status='completed' WHERE 1=1" + selection + " ORDER BY " + order + " LIMIT ?";
        List<JSONObject> rows = new ArrayList<>();
        String[] args = boundary == null ? new String[0]
            : new String[]{Integer.toString(boundary.nullFlag()), Integer.toString(boundary.nullFlag()),
            boundary.value, boundary.value, boundary.stable, boundary.stable};
        try {
            try (Cursor c = getReadableDatabase().rawQuery(sql, append(args, Integer.toString(limit + 1)))) {
                while (c.moveToNext()) {
                    JSONObject group = new JSONObject();
                    group.put("albumId", c.getString(0));
                    group.put("albumTitle", c.getString(1));
                    group.put("coverUrl", c.getString(5));
                    group.put("author", c.getString(6));
                    group.put("completedAt", c.getLong(2));
                    group.put("totalSize", c.getLong(3));
                    if (!c.isNull(8) && c.getInt(8) >= 0) {
                        group.put("isSingleEpisode", c.getInt(8) == 1);
                    }
                    rows.add(group);
                }
            }

            Map<String, List<JSONObject>> chaptersByAlbum = new HashMap<>();
            if (!rows.isEmpty()) {
                List<String> albumIds = new ArrayList<>();
                for (JSONObject row : rows) albumIds.add(row.optString("albumId"));
                String placeholders = String.join(",",
                    java.util.Collections.nCopies(albumIds.size(), "?"));
                try (Cursor chapters = getReadableDatabase().rawQuery(
                    "SELECT * FROM " + TABLE_TASKS + " WHERE " + COL_STATUS + "='completed' AND "
                        + COL_ALBUM_ID + " IN (" + placeholders + ") ORDER BY " + COL_CHAPTER_ID
                        + " ASC", albumIds.toArray(new String[0]))) {
                    while (chapters.moveToNext()) {
                        JSONObject chapter = cursorToTaskJson(chapters);
                        if (chapter != null) {
                            chaptersByAlbum.computeIfAbsent(chapter.optString("albumId"),
                                ignored -> new ArrayList<>()).add(chapter);
                        }
                    }
                }
            }

            for (JSONObject group : rows) {
                JSONArray chapters = new JSONArray();
                List<JSONObject> albumChapters = chaptersByAlbum.get(group.optString("albumId"));
                if (albumChapters != null) {
                    for (JSONObject chapter : albumChapters) chapters.put(chapter);
                }
                group.put("chapters", chapters);
                group.put("type", chapters.length() == 1 ? "single" : "multi");
            }
        } catch (Exception error) {
            throw new IllegalStateException("读取已完成本子分页失败", error);
        }
        boolean hasNext = rows.size() > limit;
        if (hasNext) rows = new ArrayList<>(rows.subList(0, limit));
        if (boundary != null && boundary.previous) java.util.Collections.reverse(rows);
        JSONArray items = new JSONArray();
        for (JSONObject row : rows) items.put(row);
        JSONObject result = new JSONObject();
        result.put("items", items);
        result.put("nextCursor", (boundary != null && boundary.previous || hasNext) && !rows.isEmpty()
            ? CursorBoundary.from(rows.get(rows.size() - 1), sort)
              .encode(false, "completed", sort, direction) : JSONObject.NULL);
        result.put("previousCursor", boundary != null && (!boundary.previous || hasNext) && !rows.isEmpty()
            ? CursorBoundary.from(rows.get(0), sort)
              .encode(true, "completed", sort, direction) : JSONObject.NULL);
        result.put("totalCount", countCompletedAlbums());
        return result;
    }

    private static String normalizeStatus(String status) {
        if ("active".equals(status) || "completed".equals(status) || "failed".equals(status)) return status;
        throw new IllegalArgumentException("status 无效");
    }

    private static String normalizeSort(String sort) {
        if ("time".equals(sort) || "title".equals(sort) || "id".equals(sort) || "author".equals(sort)) return sort;
        throw new IllegalArgumentException("sort 无效");
    }

    private static String sortColumn(String sort) {
        return switch (sort) {
            case "title" -> COL_ALBUM_TITLE;
            // ID 保持字符串存储，排序和游标比较统一使用整数值。
            case "id" -> "CAST(" + COL_ALBUM_ID + " AS INTEGER)";
            case "author" -> COL_AUTHOR;
            default -> COL_CREATED_AT;
        };
    }

    private static String orderClause(String value, String direction, String stable, boolean reverseNullRank) {
        String nonEmptyValue = "CASE WHEN " + value + " IS NULL OR TRIM(" + value + ")='' THEN NULL ELSE " + value + " END";
        String valueOrder = nonEmptyValue + ("desc".equals(direction) ? " DESC" : " ASC");
        return "CASE WHEN " + value + " IS NULL OR TRIM(" + value + ")='' THEN 1 ELSE 0 END "
            + (reverseNullRank ? "DESC," : "ASC,")
            + valueOrder + "," + stable + ("desc".equals(direction) ? " DESC" : " ASC");
    }

    private static String keysetClause(String value, String direction, String stable,
                                       boolean reverseNullRank) {
        String op = "desc".equals(direction) ? "<" : ">";
        String nullOp = reverseNullRank ? "<" : ">";
        String nullFlag = "CASE WHEN " + value + " IS NULL OR TRIM(" + value + ")='' THEN 1 ELSE 0 END";
        // NULL 与空字符串共享 null-rank；NULL 没有可比较值时，直接使用稳定键继续分页。
        return "(" + nullFlag + " " + nullOp + " CAST(? AS INTEGER) OR ("
            + nullFlag + "=CAST(? AS INTEGER) AND "
            + "((" + nullFlag + "=0 AND (" + value + " " + op + " ? OR ("
            + value + "=? AND " + stable + " " + op + " ?)))"
            + " OR (" + nullFlag + "=1 AND " + stable + " " + op + " ?))))";
    }

    private static String[] append(String[] values, Object... suffix) {
        String[] result = java.util.Arrays.copyOf(values, values.length + suffix.length);
        for (int i = 0; i < suffix.length; i++) result[values.length + i] = String.valueOf(suffix[i]);
        return result;
    }

    private static String[] append(String[] values, String value) {
        return append(values, (Object) value);
    }

    private static void createQueryIndexes(SQLiteDatabase db) {
        // 旧版本测试库/用户库可能只有最小任务字段；仅在列齐全时创建对应索引，
        // 避免升级因 no such column 失败，完整 v5 schema 仍会创建两个索引。
        if (hasColumn(db, TABLE_TASKS, COL_STATUS)
            && hasColumn(db, TABLE_TASKS, COL_CREATED_AT)
            && hasColumn(db, TABLE_TASKS, COL_TASK_ID)) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_download_tasks_status_created ON "
                + TABLE_TASKS + "(status, created_at DESC, task_id)");
        }
        if (hasColumn(db, TABLE_TASKS, COL_STATUS)
            && hasColumn(db, TABLE_TASKS, COL_ALBUM_ID)
            && hasColumn(db, TABLE_TASKS, COL_CHAPTER_ID)) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_download_tasks_status_album ON "
                + TABLE_TASKS + "(status, album_id, chapter_id)");
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(nameIndex))) return true;
            }
            return false;
        }
    }

    private static String flip(String direction) {
        return "desc".equals(direction) ? "asc" : "desc";
    }

    private static final class CursorBoundary {
        final String value;
        final String stable;
        final boolean previous;

        CursorBoundary(String value, String stable, boolean previous) {
            this.value = value;
            this.stable = stable;
            this.previous = previous;
        }

        int nullFlag() {
            // 与 SQLite TRIM 的默认规则一致，只将普通空格视为空值。
            return value == null || value.replace(" ", "").isEmpty() ? 1 : 0;
        }

        String encode(boolean previous, String status, String sort, String direction) {
            String raw = status + "\u001f" + sort + "\u001f" + direction + "\u001f"
                + (previous ? "prev" : "next") + "\u001f" + value + "\u001f" + stable;
            return android.util.Base64.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
        }

        // cursor 绑定查询条件，避免切换状态/排序后误用旧边界；prev 由查询层反转方向。
        static CursorBoundary decode(String cursor, String status, String sort, String direction) {
            if (cursor == null || cursor.isBlank()) return null;
            String raw = new String(android.util.Base64.decode(cursor, android.util.Base64.URL_SAFE), java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = raw.split("\u001f", -1);
            if (parts.length != 6 || !status.equals(parts[0]) || !sort.equals(parts[1])
                || !direction.equals(parts[2])) throw new IllegalArgumentException("cursor 无效");
            return new CursorBoundary(parts[4], parts[5], "prev".equals(parts[3]));
        }

        static CursorBoundary from(JSONObject task, String sort) {
            boolean group = task.has("chapters");
            String value = "time".equals(sort) ? Long.toString(group
                                                               ? task.optLong("completedAt") : task.optLong("createdAt"))
                : "title".equals(sort) ? task.optString("albumTitle")
                  : "author".equals(sort) ? task.optString("author") : task.optString("albumId");
            String stable = group ? task.optString("albumId") : task.optString("taskId");
            return new CursorBoundary(value, stable, false);
        }
    }

    public JSONObject getTask(String taskId) {
        Cursor c = getReadableDatabase().query(TABLE_TASKS, null,
            COL_TASK_ID + " = ?", new String[]{taskId},
            null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    return cursorToTaskJson(c);
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

    public JSONObject updateMetadata(String taskId, JSONObject metadata) {
        ContentValues values = new ContentValues();
        values.put(COL_ALBUM_TITLE, metadata.optString("albumTitle"));
        values.put(COL_CHAPTER_TITLE, metadata.optString("chapterTitle"));
        values.put(COL_AUTHOR, metadata.optString("author", ""));
        JSONArray authors = metadata.optJSONArray("authors");
        JSONArray tags = metadata.optJSONArray("tags");
        values.put(COL_AUTHORS, authors == null ? "[]" : authors.toString());
        values.put(COL_TAGS, tags == null ? "[]" : tags.toString());
        values.put(COL_CHAPTER_SORT_ORDER, metadata.optInt("chapterSortOrder"));
        values.put(COL_IS_SINGLE_EPISODE, metadata.optBoolean("isSingleEpisode") ? 1 : 0);
        if (getWritableDatabase().update(TABLE_TASKS, values,
            COL_TASK_ID + " = ?", new String[]{taskId}) != 1) {
            throw new IllegalStateException("下载任务不存在: " + taskId);
        }
        return getTask(taskId);
    }

    /**
     * 旧版本仅保存单作者时，在导出预检阶段补齐作者数组。
     */
    public JSONObject completeMetadata(String taskId) {
        JSONObject task = getTask(taskId);
        if (task == null) return null;
        JSONArray authors = task.optJSONArray("authors");
        if (authors != null && authors.length() > 0) return task;
        String author = task.optString("author", "").trim();
        if (author.isEmpty()) return task;
        ContentValues values = new ContentValues();
        values.put(COL_AUTHORS, JsonUtils.toJsonArray(List.of(author)).toString());
        getWritableDatabase().update(TABLE_TASKS, values, COL_TASK_ID + " = ?", new String[]{taskId});
        return getTask(taskId);
    }

    public void deleteTask(String taskId) {
        getWritableDatabase().delete(TABLE_TASKS,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    // ========== 图片操作 ==========

    public void insertImages(String taskId, List<io.github.jukomu.jmcomic.api.model.JmImage> images) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            for (io.github.jukomu.jmcomic.api.model.JmImage img : images) {
                cv.clear();
                cv.put(COL_TASK_ID, taskId);
                cv.put(COL_SORT_ORDER, img.getSortOrder());
                cv.put(COL_PHOTO_ID, img.getPhotoId());
                cv.put(COL_FILENAME, img.getFilename());
                cv.put(COL_URL, img.getUrl());
                cv.put(COL_SCRAMBLE_ID, img.getScrambleId());
                cv.put(COL_QUERY_PARAMS, img.getQueryParams());
                db.insertWithOnConflict(TABLE_IMAGES, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<JSONObject> getImages(String taskId) {
        List<JSONObject> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_IMAGES, null,
            COL_TASK_ID + " = ?", new String[]{taskId},
            null, null, COL_SORT_ORDER + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    JSONObject obj = new JSONObject();
                    try {
                        obj.put("taskId", c.getString(c.getColumnIndexOrThrow(COL_TASK_ID)));
                        obj.put("sortOrder", c.getInt(c.getColumnIndexOrThrow(COL_SORT_ORDER)));
                        obj.put("photoId", c.getString(c.getColumnIndexOrThrow(COL_PHOTO_ID)));
                        obj.put("filename", c.getString(c.getColumnIndexOrThrow(COL_FILENAME)));
                        obj.put("url", c.getString(c.getColumnIndexOrThrow(COL_URL)));
                        obj.put("scrambleId", c.getString(c.getColumnIndexOrThrow(COL_SCRAMBLE_ID)));
                        obj.put("queryParams", c.getString(c.getColumnIndexOrThrow(COL_QUERY_PARAMS)));
                        list.add(obj);
                    } catch (Exception e) {
                        LOGGER.debug("跳过无效图片记录", e);
                    }
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public void deleteImages(String taskId) {
        getWritableDatabase().delete(TABLE_IMAGES,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    // ========== 启动校验 ==========

    public void validateOnStartup() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_STATUS, "failed");
        values.put(COL_ERROR, "应用重启，下载或校验中断");
        db.update(TABLE_TASKS, values,
            COL_STATUS + " IN (?, ?, ?, ?)",
            new String[]{"queued", "downloading", "paused", "verifying"});
    }

    private JSONObject cursorToTaskJson(Cursor c) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("taskId", c.getString(c.getColumnIndexOrThrow(COL_TASK_ID)));
            obj.put("albumId", c.getString(c.getColumnIndexOrThrow(COL_ALBUM_ID)));
            obj.put("chapterId", c.getString(c.getColumnIndexOrThrow(COL_CHAPTER_ID)));
            obj.put("albumTitle", c.getString(c.getColumnIndexOrThrow(COL_ALBUM_TITLE)));
            obj.put("chapterTitle", c.getString(c.getColumnIndexOrThrow(COL_CHAPTER_TITLE)));
            obj.put("coverUrl", c.getString(c.getColumnIndexOrThrow(COL_COVER_URL)));
            int authorIdx = c.getColumnIndex(COL_AUTHOR);
            if (authorIdx >= 0) obj.put("author", c.getString(authorIdx));
            int authorsIdx = c.getColumnIndex(COL_AUTHORS);
            if (authorsIdx >= 0) {
                String authors = c.getString(authorsIdx);
                obj.put("authors", authors == null || authors.isBlank() ? new JSONArray() : JsonUtils.parseJsonArray(authors));
            }
            int tagsIdx = c.getColumnIndex(COL_TAGS);
            if (tagsIdx >= 0) {
                String tags = c.getString(tagsIdx);
                obj.put("tags", tags == null || tags.isBlank() ? new JSONArray() : JsonUtils.parseJsonArray(tags));
            }
            int firstSOIdx = c.getColumnIndex(COL_FIRST_IMAGE_SORT_ORDER);
            if (!c.isNull(firstSOIdx)) {
                obj.put("firstImageSortOrder", c.getInt(firstSOIdx));
            }
            obj.put("totalPages", c.getInt(c.getColumnIndexOrThrow(COL_TOTAL_PAGES)));
            obj.put("downloadedPages", c.getInt(c.getColumnIndexOrThrow(COL_DOWNLOADED_PAGES)));
            obj.put("status", c.getString(c.getColumnIndexOrThrow(COL_STATUS)));
            int errIdx = c.getColumnIndex(COL_ERROR);
            if (!c.isNull(errIdx)) {
                obj.put("error", c.getString(errIdx));
            }
            obj.put("createdAt", c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)));
            int compIdx = c.getColumnIndex(COL_COMPLETED_AT);
            if (!c.isNull(compIdx)) {
                obj.put("completedAt", c.getLong(compIdx));
            }
            int sizeIdx = c.getColumnIndex(COL_TOTAL_SIZE);
            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                obj.put("totalSize", c.getLong(sizeIdx));
            }
            int sortIdx = c.getColumnIndex(COL_CHAPTER_SORT_ORDER);
            if (sortIdx >= 0 && !c.isNull(sortIdx)) {
                obj.put("chapterSortOrder", c.getInt(sortIdx));
            }
            int singleIdx = c.getColumnIndex(COL_IS_SINGLE_EPISODE);
            if (singleIdx >= 0 && !c.isNull(singleIdx)) {
                int value = c.getInt(singleIdx);
                if (value >= 0) {
                    obj.put("isSingleEpisode", value == 1);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("转换任务记录失败", e);
            return null;
        }
        return obj;
    }
}
